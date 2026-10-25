import type { EditorState } from "@codemirror/state";
import { type EditorView, WidgetType } from "@codemirror/view";
import type { SyntaxNode } from "@lezer/common";

/*
 * Rendered stand-ins for Markdown that does not read as text: TeX math,
 * Mermaid charts and tables. KaTeX and Mermaid are large, so each loads on
 * first use; until then a widget shows its source dimmed.
 */

type Katex = typeof import("katex").default;
let katex: Katex | null = null;
let katexLoad: Promise<Katex> | null = null;

function loadKatex(): Promise<Katex> {
  katexLoad ??= Promise.all([import("katex"), import("katex/dist/katex.min.css")]).then(([m]) => (katex = m.default));
  return katexLoad;
}

function renderMath(el: HTMLElement, tex: string, display: boolean, view?: EditorView) {
  const draw = (k: Katex) => {
    k.render(tex, el, { displayMode: display, throwOnError: false, output: "htmlAndMathml" });
    view?.requestMeasure();
  };
  if (katex) return draw(katex);
  el.textContent = tex;
  el.classList.add("cm-md-loading");
  loadKatex().then((k) => {
    el.classList.remove("cm-md-loading");
    draw(k);
  });
}

export class MathWidget extends WidgetType {
  constructor(
    readonly tex: string,
    readonly display: boolean,
  ) {
    super();
  }
  eq(other: MathWidget) {
    return other.tex == this.tex && other.display == this.display;
  }
  toDOM(view: EditorView) {
    const el = document.createElement(this.display ? "div" : "span");
    el.className = this.display ? "cm-md-math-block" : "cm-md-math";
    renderMath(el, this.tex, this.display, view);
    return el;
  }
  // Let clicks through, so clicking the formula opens its source.
  ignoreEvent() {
    return false;
  }
}

const dark = () => matchMedia("(prefers-color-scheme: dark)").matches;

/** Rendered charts by theme and source, so a re-created widget does not flash. */
const charts = new Map<string, string | Error>();
let chartSeq = 0;
let mermaidTheme = "";

async function renderChart(code: string, isDark: boolean): Promise<string> {
  const key = `${isDark ? "d" : "l"}\0${code}`;
  const hit = charts.get(key);
  if (hit instanceof Error) throw hit;
  if (hit) return hit;
  const { default: mermaid } = await import("mermaid");
  const theme = isDark ? "dark" : "neutral";
  if (theme != mermaidTheme) {
    // The widget shows its own error; mermaid's would land at the end of the page.
    mermaid.initialize({ startOnLoad: false, theme, fontFamily: "inherit", securityLevel: "strict", suppressErrorRendering: true });
    mermaidTheme = theme;
  }
  const id = `mermaid-${++chartSeq}`;
  try {
    const { svg } = await mermaid.render(id, code);
    charts.set(key, svg);
    return svg;
  } catch (err) {
    // A failed render leaves its scratch container in the body.
    document.getElementById(`d${id}`)?.remove();
    const e = err instanceof Error ? err : new Error(String(err));
    charts.set(key, e);
    throw e;
  }
}

export class MermaidWidget extends WidgetType {
  constructor(readonly code: string) {
    super();
  }
  eq(other: MermaidWidget) {
    return other.code == this.code;
  }
  toDOM(view: EditorView) {
    const el = document.createElement("div");
    el.className = "cm-md-chart";
    const isDark = dark();
    const cached = charts.get(`${isDark ? "d" : "l"}\0${this.code}`);
    if (typeof cached == "string") {
      el.innerHTML = cached;
      return el;
    }
    el.classList.add("cm-md-loading");
    el.textContent = this.code;
    renderChart(this.code, isDark).then(
      (svg) => {
        el.classList.remove("cm-md-loading");
        el.innerHTML = svg;
        view.requestMeasure();
      },
      (err: Error) => {
        el.classList.remove("cm-md-loading");
        el.classList.add("cm-md-chart-error");
        el.textContent = `Mermaid 图表无法渲染：${err.message.split("\n")[0]}`;
        view.requestMeasure();
      },
    );
    return el;
  }
  ignoreEvent() {
    return false;
  }
}

/** Inline Markdown inside a table cell, reduced to what a cell can hold. */
type Inline = string | { tag: string; text?: string; href?: string; children?: Inline[] };
type Align = "left" | "center" | "right" | "";
type TableModel = { align: Align[]; head: Inline[][]; rows: Inline[][][] };

const MARKS = new Set(["EmphasisMark", "StrikethroughMark", "HighlightMark", "CodeMark", "LinkMark", "MathMark", "QuoteMark"]);
const TAGS: Record<string, string> = {
  Emphasis: "em",
  StrongEmphasis: "strong",
  Strikethrough: "del",
  Highlight: "mark",
};

function inlines(state: EditorState, node: SyntaxNode, from: number, to: number): Inline[] {
  const out: Inline[] = [];
  let pos = from;
  const text = (end: number) => {
    if (end > pos) out.push(state.doc.sliceString(pos, end));
  };
  for (let c = node.firstChild; c; c = c.nextSibling) {
    if (c.to <= from || c.from >= to) continue;
    text(c.from);
    pos = c.to;
    const marks = c.getChildren("LinkMark");
    if (MARKS.has(c.name)) continue;
    if (TAGS[c.name]) out.push({ tag: TAGS[c.name]!, children: inlines(state, c, c.from, c.to) });
    else if (c.name == "InlineCode") {
      const m = c.getChildren("CodeMark");
      out.push({ tag: "code", text: state.doc.sliceString(m[0]?.to ?? c.from, m[1]?.from ?? c.to) });
    } else if (c.name == "InlineMath") {
      const m = c.getChildren("MathMark");
      out.push({ tag: "math", text: state.doc.sliceString(m[0]!.to, m[1]!.from) });
    } else if (c.name == "Link" && marks.length >= 2) {
      const url = c.getChild("URL");
      out.push({
        tag: "a",
        href: url ? state.doc.sliceString(url.from, url.to) : undefined,
        children: inlines(state, c, marks[0]!.to, marks[1]!.from),
      });
    } else if (c.name == "Autolink" || c.name == "URL") {
      const url = c.name == "URL" ? c : c.getChild("URL");
      const href = url ? state.doc.sliceString(url.from, url.to) : "";
      out.push({ tag: "a", href, children: [href] });
    } else if (c.name == "Image") {
      const url = c.getChild("URL");
      out.push({
        tag: "img",
        href: url ? state.doc.sliceString(url.from, url.to) : "",
        text: marks.length >= 2 ? state.doc.sliceString(marks[0]!.to, marks[1]!.from) : "",
      });
    } else if (c.name == "Escape") out.push(state.doc.sliceString(c.from + 1, c.to));
    else if (c.name == "HardBreak") out.push({ tag: "br" });
    else out.push(...inlines(state, c, c.from, c.to));
  }
  text(to);
  return out;
}

/** A row's cells; GFM leaves no node for an empty cell. */
function rowCells(state: EditorState, row: SyntaxNode): Inline[][] {
  const cells: Inline[][] = [];
  let first = true;
  let filled = false;
  for (let c = row.firstChild; c; c = c.nextSibling) {
    if (c.name == "TableCell") {
      cells.push(inlines(state, c, c.from, c.to));
      filled = true;
    } else if (c.name == "TableDelimiter") {
      if (!filled && !first) cells.push([]);
      filled = false;
      first = false;
    }
  }
  return cells;
}

export function tableModel(state: EditorState, table: SyntaxNode): TableModel {
  const model: TableModel = { align: [], head: [], rows: [] };
  for (let c = table.firstChild; c; c = c.nextSibling) {
    if (c.name == "TableHeader") model.head = rowCells(state, c);
    else if (c.name == "TableRow") model.rows.push(rowCells(state, c));
    else if (c.name == "TableDelimiter") {
      model.align = state.doc
        .sliceString(c.from, c.to)
        .replace(/^\s*\|/, "")
        .replace(/\|\s*$/, "")
        .split("|")
        .map((s) => {
          s = s.trim();
          const l = s.startsWith(":");
          const r = s.endsWith(":");
          return l && r ? "center" : r ? "right" : l ? "left" : "";
        });
    }
  }
  return model;
}

const safeHref = (href: string) => (/^www\./i.test(href) ? `https://${href}` : /^(https?:|mailto:|\/)/i.test(href) ? href : "");

function renderInlines(parent: HTMLElement, list: Inline[]) {
  for (const item of list) {
    if (typeof item == "string") {
      parent.append(item);
      continue;
    }
    if (item.tag == "math") {
      const el = parent.appendChild(document.createElement("span"));
      el.className = "cm-md-math";
      renderMath(el, item.text ?? "", false);
    } else if (item.tag == "img") {
      const img = parent.appendChild(document.createElement("img"));
      img.src = item.href ?? "";
      img.alt = item.text ?? "";
    } else {
      const el = parent.appendChild(document.createElement(item.tag));
      if (item.tag == "code") el.className = "cm-md-icode";
      if (item.tag == "mark") el.className = "cm-md-mark";
      if (item.tag == "a") {
        const href = safeHref(item.href ?? "");
        if (href) {
          el.setAttribute("href", href);
          el.setAttribute("target", "_blank");
          el.setAttribute("rel", "noopener");
        }
      }
      if (item.text != null) el.textContent = item.text;
      if (item.children) renderInlines(el, item.children);
    }
  }
}

export class TableWidget extends WidgetType {
  constructor(
    readonly source: string,
    readonly model: TableModel,
  ) {
    super();
  }
  eq(other: TableWidget) {
    return other.source == this.source;
  }
  toDOM(view: EditorView) {
    const wrap = document.createElement("div");
    wrap.className = "cm-md-table-wrap";
    const table = wrap.appendChild(document.createElement("table"));
    const cols = this.model.head.length;
    const row = (parent: HTMLElement, cells: Inline[][], tag: "th" | "td") => {
      const tr = parent.appendChild(document.createElement("tr"));
      for (let i = 0; i < cols; i++) {
        const cell = tr.appendChild(document.createElement(tag));
        const align = this.model.align[i];
        if (align) cell.style.textAlign = align;
        renderInlines(cell, cells[i] ?? []);
      }
    };
    row(table.appendChild(document.createElement("thead")), this.model.head, "th");
    const body = table.appendChild(document.createElement("tbody"));
    for (const cells of this.model.rows) row(body, cells, "td");
    wrap.querySelectorAll("img").forEach((img) => (img.onload = () => view.requestMeasure()));
    return wrap;
  }
  // Links open; any other click goes to the editor and opens the source.
  ignoreEvent(e: Event) {
    return e.type == "mousedown" && !!(e.target as HTMLElement).closest?.("a[href]");
  }
}
