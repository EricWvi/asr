import { markdown, markdownLanguage } from "@codemirror/lang-markdown";
import { HighlightStyle } from "@codemirror/language";
import { languages } from "@codemirror/language-data";
import { Tag, tags } from "@lezer/highlight";
import type { Element, MarkdownConfig } from "@lezer/markdown";

/** Style tag for the text inside ==highlight==. */
export const highlightTag = Tag.define();

const HighlightDelim = { resolve: "Highlight", mark: "HighlightMark" };
const Punctuation = /[\p{P}\p{S}]/u;

/**
 * `==highlight==`, parsed like GFM strikethrough: two `=` on each side,
 * following the same flanking rules, so it nests with bold and the rest.
 */
export const Highlight: MarkdownConfig = {
  defineNodes: [
    { name: "Highlight", style: { "Highlight/...": highlightTag } },
    { name: "HighlightMark", style: tags.processingInstruction },
  ],
  parseInline: [
    {
      name: "Highlight",
      parse(cx, next, pos) {
        if (next != 61 /* = */ || cx.char(pos + 1) != 61 || cx.char(pos + 2) == 61 || cx.char(pos - 1) == 61) return -1;
        const before = cx.slice(pos - 1, pos);
        const after = cx.slice(pos + 2, pos + 3);
        const sBefore = /\s|^$/.test(before);
        const sAfter = /\s|^$/.test(after);
        const pBefore = Punctuation.test(before);
        const pAfter = Punctuation.test(after);
        return cx.addDelimiter(
          HighlightDelim,
          pos,
          pos + 2,
          !sAfter && (!pAfter || sBefore || pBefore),
          !sBefore && (!pBefore || sAfter || pAfter),
        );
      },
      after: "Emphasis",
    },
  ],
};

/**
 * TeX math as remark-math reads it: `$inline$` or `$$inline$$` inside a
 * paragraph, and a `$$` block running to the next line that ends in `$$`.
 * An inline `$` must not open before a space nor close after one or before
 * a digit, so prices stay text.
 */
export const MathSyntax: MarkdownConfig = {
  defineNodes: [
    { name: "InlineMath", style: tags.monospace },
    { name: "BlockMath", block: true, style: tags.monospace },
    { name: "MathMark", style: tags.processingInstruction },
  ],
  parseInline: [
    {
      name: "InlineMath",
      parse(cx, next, pos) {
        if (next != 36 /* $ */ || cx.char(pos - 1) == 36) return -1;
        const n = cx.char(pos + 1) == 36 ? 2 : 1;
        const first = cx.char(pos + n);
        if (first == 36 || first == 32 || first == 9 || first == 10 || first < 0) return -1;
        for (let i = pos + n; i < cx.end; i++) {
          const c = cx.char(i);
          if (c == 92 /* \ */) {
            i++;
            continue;
          }
          if (c != 36) continue;
          let run = 1;
          while (cx.char(i + run) == 36) run++;
          const prev = cx.char(i - 1);
          const after = cx.char(i + run);
          if (run == n) {
            // A `$` that cannot close ends the attempt, so `$5 and $10` stays text.
            if (prev == 32 || prev == 9 || prev == 10 || (after >= 48 && after <= 57)) return -1;
            const end = i + n;
            return cx.addElement(
              cx.elt("InlineMath", pos, end, [cx.elt("MathMark", pos, pos + n), cx.elt("MathMark", i, end)]),
            );
          }
          i += run - 1;
        }
        return -1;
      },
      before: "Emphasis",
    },
  ],
  parseBlock: [
    {
      name: "BlockMath",
      parse(cx, line) {
        if (line.next != 36 || line.text.charCodeAt(line.pos + 1) != 36 || line.indent - line.baseIndent >= 4) return false;
        const from = cx.lineStart + line.pos;
        const marks: Element[] = [cx.elt("MathMark", from, from + 2)];
        const rest = line.text.slice(line.pos + 2).trimEnd();
        let to: number;
        if (rest.length >= 2 && rest.endsWith("$$")) {
          // $$ ... $$ on a single line.
          to = cx.lineStart + line.pos + 2 + rest.length;
          marks.push(cx.elt("MathMark", to - 2, to));
          cx.nextLine();
        } else {
          // Unclosed, it runs to the end of the document, like a fence.
          to = cx.lineStart + line.text.length;
          for (;;) {
            if (!cx.nextLine()) break;
            const text = line.text.trimEnd();
            to = cx.lineStart + text.length;
            if (text.slice(line.pos).endsWith("$$")) {
              marks.push(cx.elt("MathMark", to - 2, to));
              cx.nextLine();
              break;
            }
          }
        }
        cx.addElement(cx.elt("BlockMath", from, to, marks));
        return true;
      },
      endLeaf: (_cx, line) => line.next == 36 && line.text.charCodeAt(line.pos + 1) == 36,
      before: "FencedCode",
    },
  ],
};

/**
 * YAML frontmatter: a `---` on the first line up to the next `---` or
 * `...`. Without this it would read as a rule and a setext heading. It
 * reuses the fenced-code marks, so it renders as a yaml code block.
 */
export const Frontmatter: MarkdownConfig = {
  defineNodes: [{ name: "Frontmatter", block: true }],
  parseBlock: [
    {
      name: "Frontmatter",
      parse(cx, line) {
        if (cx.lineStart != 0 || line.text.trimEnd() != "---") return false;
        const marks: Element[] = [cx.elt("CodeMark", 0, 3)];
        let to = line.text.length;
        while (cx.nextLine()) {
          const text = line.text.trimEnd();
          to = cx.lineStart + text.length;
          if (text == "---" || text == "...") {
            marks.push(cx.elt("CodeMark", cx.lineStart, to));
            cx.nextLine();
            break;
          }
        }
        cx.addElement(cx.elt("Frontmatter", 0, to, marks));
        return true;
      },
      before: "HorizontalRule",
    },
  ],
};

/**
 * Token colours inside fenced code, as classes styled by index.css under
 * .cm-md-code, so the Markdown outside never picks them up.
 */
export const codeStyle = HighlightStyle.define([
  { tag: [tags.keyword, tags.operatorKeyword, tags.modifier, tags.controlKeyword, tags.definitionKeyword], class: "hl-keyword" },
  { tag: [tags.string, tags.special(tags.string), tags.regexp, tags.character, tags.attributeValue], class: "hl-string" },
  { tag: [tags.comment, tags.meta], class: "hl-comment" },
  { tag: [tags.number, tags.bool, tags.atom, tags.null, tags.escape, tags.unit, tags.constant(tags.variableName)], class: "hl-constant" },
  {
    tag: [tags.function(tags.variableName), tags.function(tags.propertyName), tags.function(tags.definition(tags.variableName)), tags.macroName],
    class: "hl-function",
  },
  { tag: [tags.typeName, tags.className, tags.namespace, tags.tagName, tags.standard(tags.typeName)], class: "hl-type" },
  { tag: [tags.propertyName, tags.attributeName, tags.definition(tags.propertyName)], class: "hl-property" },
  { tag: [tags.inserted], class: "hl-inserted" },
  { tag: [tags.deleted, tags.invalid], class: "hl-deleted" },
  { tag: [tags.heading], class: "hl-heading" },
]);

/**
 * GFM plus ==highlight==, math and frontmatter, with the Markdown keymap
 * (list continuation). Fenced code parses as its language, loaded on demand.
 */
export const markdownSupport = () =>
  markdown({
    base: markdownLanguage,
    extensions: [Highlight, MathSyntax, Frontmatter],
    codeLanguages: languages,
    completeHTMLTags: false,
  });
