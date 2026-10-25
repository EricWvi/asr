import { syntaxTree } from "@codemirror/language";
import { type EditorState, type Range, StateField } from "@codemirror/state";
import { Decoration, type DecorationSet, EditorView, WidgetType } from "@codemirror/view";
import type { SyntaxNode } from "@lezer/common";
import { notifyError } from "@/lib/toast";
import { MathWidget, MermaidWidget, TableWidget, tableModel } from "./embeds";

/*
 * Live preview, as in design/pc.html: lines touched by the selection show
 * their Markdown source with the marks in the document's deep tint; every
 * other line hides the marks and shows only the rendered look. Task boxes
 * stay rendered everywhere, and a fenced code block shows its fences while
 * the cursor is anywhere inside it. Math blocks, Mermaid charts and tables
 * likewise show their source while the cursor is inside them, with math
 * and charts previewed below it; elsewhere they are replaced by the
 * rendering (editor/embeds.ts).
 *
 * Images and those blocks render as block widgets, which only a state field may provide, so
 * the whole set is rebuilt here on every change of text, selection or
 * parse tree. Notes are short enough for that.
 */

/** Lines the selection touches, by line number. */
function activeLines(state: EditorState): Set<number> {
  const lines = new Set<number>();
  for (const r of state.selection.ranges) {
    const last = state.doc.lineAt(r.to).number;
    for (let n = state.doc.lineAt(r.from).number; n <= last; n++) lines.add(n);
  }
  return lines;
}

class BulletWidget extends WidgetType {
  constructor(readonly depth: number) {
    super();
  }
  eq(other: BulletWidget) {
    return other.depth == this.depth;
  }
  toDOM() {
    const el = document.createElement("span");
    el.className = "cm-md-lm cm-md-bullet";
    el.dataset.shape = ["disc", "circle", "square"][this.depth % 3]!;
    return el;
  }
}

class CheckboxWidget extends WidgetType {
  constructor(readonly checked: boolean) {
    super();
  }
  eq(other: CheckboxWidget) {
    return other.checked == this.checked;
  }
  toDOM(view: EditorView) {
    const box = document.createElement("span");
    box.className = "cm-md-box";
    box.setAttribute("role", "checkbox");
    box.setAttribute("aria-checked", String(this.checked));
    if (this.checked)
      box.innerHTML =
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5 9-10"/></svg>';
    // Toggle without moving the cursor or focusing the editor.
    box.addEventListener("mousedown", (e) => {
      e.preventDefault();
      toggleTask(view, view.posAtDOM(box));
    });
    return box;
  }
  ignoreEvent() {
    return true;
  }
}

/** Flips the task box of the list item that starts at pos. */
function toggleTask(view: EditorView, pos: number) {
  const line = view.state.doc.lineAt(pos);
  const m = /\[([ xX])\]/.exec(line.text.slice(pos - line.from));
  if (!m) return;
  const from = pos + m.index + 1;
  view.dispatch({ changes: { from, to: from + 1, insert: m[1] == " " ? "x" : " " }, userEvent: "input.toggle" });
}

class RuleWidget extends WidgetType {
  eq() {
    return true;
  }
  toDOM() {
    const el = document.createElement("span");
    el.className = "cm-md-rule";
    return el;
  }
}

const copyIcon =
  '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="8" y="8" width="14" height="14" rx="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>';
const checkIcon =
  '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>';

/** The opening fence away from the cursor: the language on the left, a copy button on the right. */
class FenceHeadWidget extends WidgetType {
  constructor(
    readonly label: string,
    readonly code: string,
  ) {
    super();
  }
  eq(other: FenceHeadWidget) {
    return other.label == this.label && other.code == this.code;
  }
  toDOM() {
    const el = document.createElement("span");
    el.className = "cm-md-fence-head";
    const lang = el.appendChild(document.createElement("span"));
    lang.className = "cm-md-lang";
    lang.textContent = this.label;
    const btn = el.appendChild(document.createElement("button"));
    btn.type = "button";
    btn.className = "cm-md-copy";
    btn.title = "复制";
    btn.innerHTML = copyIcon;
    // Keeps the cursor where it is, so the block stays rendered.
    btn.addEventListener("mousedown", (e) => e.preventDefault());
    btn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(this.code);
        btn.innerHTML = checkIcon;
        setTimeout(() => (btn.innerHTML = copyIcon), 1500);
      } catch (err) {
        notifyError(err, "复制失败");
      }
    });
    return el;
  }
}

export class ImageWidget extends WidgetType {
  constructor(
    readonly src: string,
    readonly alt: string,
  ) {
    super();
  }
  eq(other: ImageWidget) {
    return other.src == this.src && other.alt == this.alt;
  }
  toDOM(view: EditorView) {
    const wrap = document.createElement("div");
    wrap.className = "cm-md-img";
    const img = document.createElement("img");
    img.src = this.src;
    img.alt = this.alt;
    img.draggable = false;
    img.onload = () => view.requestMeasure();
    img.onerror = () => {
      wrap.classList.add("cm-md-img-broken");
      wrap.textContent = this.alt ? `图片无法加载：${this.alt}` : "图片无法加载";
      view.requestMeasure();
    };
    wrap.append(img);
    return wrap;
  }
  // Let clicks through, so clicking an image puts the cursor on its line.
  ignoreEvent() {
    return false;
  }
}

const modKey = /Mac|iPhone|iPad/.test(navigator.platform) ? "⌘" : "Ctrl";
const linkTitle = `${modKey}+点击打开链接`;

const hide = Decoration.replace({});
const rule = Decoration.replace({ widget: new RuleWidget() });
const line = (cls: string, style?: string) =>
  Decoration.line({ class: cls, attributes: style ? { style } : undefined });

const listDepth = (node: SyntaxNode) => {
  let depth = -1;
  for (let p: SyntaxNode | null = node; p; p = p.parent)
    if (p.name == "BulletList" || p.name == "OrderedList") depth++;
  return depth;
};

function build(state: EditorState): DecorationSet {
  const doc = state.doc;
  const active = activeLines(state);
  const out: Range<Decoration>[] = [];
  /** Lines inside code and math blocks: their blank lines are not compressed. */
  const codeLines = new Set<number>();
  /** Lines that already got a list line decoration. */
  const listLines = new Set<number>();
  const isActive = (pos: number) => active.has(doc.lineAt(pos).number);

  const anyActive = (first: number, last: number) => {
    for (let n = first; n <= last; n++) if (active.has(n)) return true;
    return false;
  };
  // Replaces whole lines with a rendered block.
  const replaceLines = (first: number, last: number, widget: WidgetType) =>
    out.push(Decoration.replace({ widget, block: true }).range(doc.line(first).from, doc.line(last).to));
  // Shows a rendered block below the line.
  const below = (n: number, widget: WidgetType) =>
    out.push(Decoration.widget({ widget, block: true, side: 1 }).range(doc.line(n).to));

  // Hides [from, to) unless the cursor is on that line.
  const hideMark = (from: number, to: number) => {
    if (from < to && !isActive(from)) out.push(hide.range(from, to));
  };

  syntaxTree(state).iterate({
    enter(ref) {
      const node = ref.node;
      switch (ref.name) {
        case "ATXHeading1":
        case "ATXHeading2":
        case "ATXHeading3":
        case "ATXHeading4":
        case "ATXHeading5":
        case "ATXHeading6": {
          const l = doc.lineAt(ref.from);
          out.push(line(`cm-md-h cm-md-h${ref.name.slice(-1)}`).range(l.from));
          const mark = node.getChild("HeaderMark");
          if (mark && mark.from == ref.from) {
            // The leading # and the space after it hang in the left margin.
            const end = Math.min(mark.to + 1, l.to);
            if (isActive(ref.from)) out.push(Decoration.mark({ class: "cm-md-hang" }).range(mark.from, end));
            else out.push(hide.range(mark.from, end));
          }
          // A closing run of # is hidden too.
          const close = node.lastChild;
          if (close && close != mark && close.name == "HeaderMark") hideMark(close.from, close.to);
          return false;
        }
        case "Blockquote": {
          const first = doc.lineAt(ref.from).number;
          const last = doc.lineAt(ref.to).number;
          for (let n = first; n <= last; n++) {
            let cls = "cm-md-quote";
            if (n == first) cls += " cm-md-quote-first";
            if (n == last) cls += " cm-md-quote-last";
            out.push(line(cls).range(doc.line(n).from));
          }
          return;
        }
        case "QuoteMark": {
          const l = doc.lineAt(ref.from);
          const end = l.text[ref.to - l.from] == " " ? ref.to + 1 : ref.to;
          hideMark(ref.from, end);
          return;
        }
        case "ListItem": {
          decorateListItem(node);
          return;
        }
        case "FencedCode":
        case "CodeBlock":
        case "Frontmatter": {
          const first = doc.lineAt(ref.from).number;
          const last = doc.lineAt(ref.to).number;
          const open = anyActive(first, last);
          const fenced = ref.name != "CodeBlock";
          const marks = node.getChildren("CodeMark");
          const closed = fenced && marks.length > 1;
          const info = node.getChild("CodeInfo");
          const label = ref.name == "Frontmatter" ? "yaml" : info ? doc.sliceString(info.from, info.to) : "";
          const from = first < last ? doc.line(first + 1).from : ref.to;
          const to = closed ? doc.line(last).from - 1 : ref.to;
          const code = fenced && from <= to ? doc.sliceString(from, to) : "";
          const chart = fenced && /^mermaid\b/i.test(label) ? new MermaidWidget(code) : null;
          for (let n = first; n <= last; n++) codeLines.add(n);
          if (chart && !open) {
            replaceLines(first, last, chart);
            return false;
          }
          for (let n = first; n <= last; n++) {
            const l = doc.line(n);
            let cls = "cm-md-code";
            if (n == first) cls += " cm-md-code-first";
            if (n == last) cls += " cm-md-code-last";
            const fence = fenced && ((n == first) || (n == last && closed));
            if (fence && !open) {
              if (n == first) {
                cls += " cm-md-fence-first";
                out.push(Decoration.replace({ widget: new FenceHeadWidget(label, code) }).range(l.from, l.to));
              } else {
                cls += " cm-md-fence";
                if (l.length) out.push(hide.range(l.from, l.to));
              }
            }
            out.push(line(cls).range(l.from));
          }
          if (chart) below(last, chart);
          return false;
        }
        case "BlockMath": {
          const first = doc.lineAt(ref.from).number;
          const last = doc.lineAt(ref.to).number;
          const marks = node.getChildren("MathMark");
          const end = marks.length > 1 ? marks[marks.length - 1]!.from : ref.to;
          const widget = new MathWidget(doc.sliceString(marks[0]!.to, end).trim(), true);
          for (let n = first; n <= last; n++) codeLines.add(n);
          if (!anyActive(first, last)) {
            replaceLines(first, last, widget);
            return false;
          }
          for (let n = first; n <= last; n++) out.push(line("cm-md-math-src").range(doc.line(n).from));
          below(last, widget);
          return false;
        }
        case "InlineMath": {
          if (isActive(ref.from) || isActive(ref.to)) {
            out.push(Decoration.mark({ class: "cm-md-math-src" }).range(ref.from, ref.to));
            return false;
          }
          const [open, close] = node.getChildren("MathMark");
          const widget = new MathWidget(doc.sliceString(open!.to, close!.from), false);
          out.push(Decoration.replace({ widget }).range(ref.from, ref.to));
          return false;
        }
        case "HorizontalRule": {
          if (!isActive(ref.from)) out.push(rule.range(ref.from, ref.to));
          return false;
        }
        case "Table": {
          const first = doc.lineAt(ref.from).number;
          const last = doc.lineAt(ref.to).number;
          if (!anyActive(first, last)) {
            replaceLines(first, last, new TableWidget(doc.sliceString(ref.from, ref.to), tableModel(state, node)));
            return false;
          }
          for (let n = first; n <= last; n++) out.push(line("cm-md-table").range(doc.line(n).from));
          return;
        }
        case "TableDelimiter":
          out.push(Decoration.mark({ class: "cm-md-deep" }).range(ref.from, ref.to));
          return;
        case "EmphasisMark":
        case "StrikethroughMark":
        case "HighlightMark":
          hideMark(ref.from, ref.to);
          return;
        case "InlineCode": {
          out.push(Decoration.mark({ class: "cm-md-icode" }).range(ref.from, ref.to));
          for (const m of node.getChildren("CodeMark")) hideMark(m.from, m.to);
          return false;
        }
        case "Link": {
          const url = node.getChild("URL");
          // Without a URL it is a reference link, which needs a definition
          // elsewhere; most often it is just text in brackets, so it stays text.
          if (!url) {
            out.push(Decoration.mark({ class: "cm-md-plain" }).range(ref.from, ref.to));
            return false;
          }
          out.push(Decoration.mark({ class: "cm-md-link", attributes: { title: linkTitle } }).range(ref.from, ref.to));
          const [open, close] = node.getChildren("LinkMark");
          if (open && close) {
            hideMark(open.from, open.to);
            hideMark(close.from, ref.to);
          }
          return false;
        }
        case "Autolink": {
          out.push(Decoration.mark({ class: "cm-md-autolink", attributes: { title: linkTitle } }).range(ref.from, ref.to));
          for (const m of node.getChildren("LinkMark")) hideMark(m.from, m.to);
          return false;
        }
        case "LinkReference":
          return false;
        case "URL":
          // A bare URL; those inside links and images are handled above.
          out.push(Decoration.mark({ class: "cm-md-autolink", attributes: { title: linkTitle } }).range(ref.from, ref.to));
          return;
        case "Escape":
          hideMark(ref.from, ref.from + 1);
          return;
        case "Image": {
          const url = node.getChild("URL");
          if (!url) return false;
          const l = doc.lineAt(ref.from);
          const src = doc.sliceString(url.from, url.to);
          const [open, close] = node.getChildren("LinkMark");
          const widget = new ImageWidget(src, open && close ? doc.sliceString(open.to, close.from) : "");
          const alone = l.text.trim() == doc.sliceString(ref.from, ref.to);
          if (isActive(ref.from)) {
            // The source stays editable; the picture shows below the line.
            out.push(Decoration.widget({ widget, block: true, side: 1 }).range(l.to));
          } else if (alone) {
            // A line holding only the image becomes the picture. It keeps
            // its line, so the arrow keys still reach it.
            out.push(Decoration.replace({ widget }).range(ref.from, ref.to));
          } else {
            out.push(hide.range(ref.from, ref.to));
            out.push(Decoration.widget({ widget, block: true, side: 1 }).range(l.to));
          }
          return false;
        }
      }
    },
  });

  function decorateListItem(item: SyntaxNode) {
    const depth = listDepth(item);
    const mark = item.getChild("ListMark");
    const task = item.getChild("Task");
    const taskMark = task?.getChild("TaskMarker");
    const ordered = item.parent?.name == "OrderedList";
    const first = doc.lineAt(item.from).number;
    // Nested items decorate their own lines, so stop where the first child list starts.
    const sub = item.getChild("BulletList") ?? item.getChild("OrderedList");
    const last = sub ? doc.lineAt(sub.from).number - 1 : doc.lineAt(item.to).number;
    const kind = taskMark ? "cm-md-task" : "cm-md-li";
    for (let n = first; n <= last; n++) {
      if (listLines.has(n)) continue;
      listLines.add(n);
      const l = doc.line(n);
      if (n != first && !l.text.trim()) continue;
      out.push(line(`cm-md-list ${kind}`, `--depth:${depth}`).range(l.from));
      // Indentation is drawn by the line padding, so the source spaces are hidden.
      const indent = /^\s*/.exec(l.text)![0].length;
      if (indent) out.push(hide.range(l.from, l.from + indent));
    }
    if (!mark) return;
    const l = doc.lineAt(mark.from);
    const space = l.text[mark.to - l.from] == " " ? 1 : 0;
    if (taskMark) {
      const checked = doc.sliceString(taskMark.from + 1, taskMark.from + 2) != " ";
      const end = l.text[taskMark.to - l.from] == " " ? taskMark.to + 1 : taskMark.to;
      out.push(Decoration.replace({ widget: new CheckboxWidget(checked) }).range(mark.from, end));
      if (checked) out.push(line("cm-md-done").range(l.from));
    } else if (!ordered && !active.has(l.number)) {
      out.push(Decoration.replace({ widget: new BulletWidget(depth) }).range(mark.from, mark.to + space));
    } else {
      const dim = active.has(l.number) ? "" : " cm-md-lm-dim";
      out.push(Decoration.mark({ class: `cm-md-lm${dim}` }).range(mark.from, mark.to + space));
    }
  }

  // Blank lines between blocks shrink to paragraph spacing, except the
  // cursor's line, which needs full height to type into. A selection
  // dragged across them leaves them alone, so the text does not jump.
  const heads = new Set(state.selection.ranges.map((r) => doc.lineAt(r.head).number));
  for (let n = 1; n <= doc.lines; n++) {
    if (heads.has(n) || codeLines.has(n)) continue;
    const l = doc.line(n);
    // A quote line holding only > counts as blank.
    if (/^[\s>]*$/.test(l.text)) out.push(line("cm-md-blank").range(l.from));
  }
  out.push(line("cm-md-first").range(0));

  return Decoration.set(out, true);
}


const previewField = StateField.define<DecorationSet>({
  create: build,
  update(deco, tr) {
    if (tr.docChanged || tr.selection || syntaxTree(tr.state) != syntaxTree(tr.startState)) return build(tr.state);
    return deco;
  },
  provide: (f) => EditorView.decorations.from(f),
});

/** URL of the link at pos, if any. */
function linkAt(state: EditorState, pos: number): string | null {
  for (let node: SyntaxNode | null = syntaxTree(state).resolveInner(pos, 1); node; node = node.parent) {
    if (node.name == "URL") return state.doc.sliceString(node.from, node.to);
    if (node.name == "Link" || node.name == "Autolink") {
      const url = node.getChild("URL");
      return url ? state.doc.sliceString(url.from, url.to) : null;
    }
  }
  return null;
}

const openLinks = EditorView.domEventHandlers({
  mousedown(e, view) {
    if (e.button != 0 || !(e.metaKey || e.ctrlKey)) return false;
    const pos = view.posAtCoords({ x: e.clientX, y: e.clientY });
    if (pos == null) return false;
    let url = linkAt(view.state, pos);
    if (!url) return false;
    if (/^www\./i.test(url)) url = `https://${url}`;
    else if (!/^(https?:|mailto:|\/)/i.test(url)) return false;
    e.preventDefault();
    window.open(url, "_blank", "noopener");
    return true;
  },
});

export const livePreview = [previewField, openLinks];
