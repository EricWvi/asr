import { markdown, markdownLanguage } from "@codemirror/lang-markdown";
import { Tag, tags } from "@lezer/highlight";
import type { MarkdownConfig } from "@lezer/markdown";

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

/** GFM plus ==highlight==, with the Markdown keymap (list continuation). */
export const markdownSupport = () =>
  markdown({ base: markdownLanguage, extensions: [Highlight], completeHTMLTags: false });
