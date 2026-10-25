import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { EditorState, Prec } from "@codemirror/state";
import { drawSelection, EditorView, keymap } from "@codemirror/view";
import { tags } from "@lezer/highlight";
import { useEffect, useRef } from "react";

import { dictation } from "@/editor/dictation";
import { imageUpload } from "@/editor/images";
import { dedentListItem, exitEmptyListItem, indentListItem } from "@/editor/lists";
import { livePreview } from "@/editor/live-preview";
import { searchHits } from "@/editor/search-hits";
import { codeStyle, highlightTag, markdownSupport } from "@/editor/syntax";
import { initialTidyRanges, type Span, spansOf, tidyRanges } from "@/editor/tidy";
import { registerEditor } from "@/lib/editor-focus";
import { setPending } from "@/voice/tidy";

/*
 * Markdown source with live preview: the cursor's lines show their marks,
 * the rest render (see editor/live-preview.ts). Line-level styles live in
 * index.css under .cm-md-*.
 */

const theme = EditorView.theme({
  "&": { fontSize: "16px", backgroundColor: "transparent" },
  "&.cm-focused": { outline: "none" },
  // The sheet scrolls, not the editor.
  ".cm-scroller": { fontFamily: "inherit", lineHeight: "1.85", overflow: "visible" },
  ".cm-content": { padding: "0", caretColor: "var(--blue)" },
  ".cm-line": { padding: "0" },
  ".cm-cursor, .cm-dropCursor": { borderLeft: "2px solid var(--blue)" },
  ".cm-selectionBackground, &.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground": {
    background: "var(--tint-sel)",
  },
});

const markdownStyle = HighlightStyle.define([
  { tag: tags.heading1, fontSize: "24px", fontWeight: "700" },
  { tag: tags.heading2, fontSize: "20px", fontWeight: "650" },
  { tag: tags.heading3, fontSize: "17px", fontWeight: "600" },
  { tag: [tags.heading4, tags.heading5, tags.heading6], fontWeight: "600" },
  { tag: tags.strong, fontWeight: "600" },
  { tag: tags.emphasis, fontStyle: "italic" },
  { tag: tags.strikethrough, textDecoration: "line-through" },
  { tag: highlightTag, class: "cm-md-mark" },
  { tag: tags.link, color: "var(--blue)" },
  { tag: tags.url, class: "cm-md-url" },
  { tag: tags.monospace, fontFamily: '"SF Mono", ui-monospace, Menlo, monospace' },
  { tag: tags.quote, color: "var(--ink-2)" },
  // #, -, >, **, ` and other syntax marks take the document's deep tint (.cm-md-pi).
  { tag: tags.processingInstruction, class: "cm-md-pi" },
]);

export function Editor({
  docId,
  initial,
  initialSpans,
  onChange,
}: {
  docId: number;
  initial: string;
  /** Dictated text awaiting tidying (editor/tidy.ts). */
  initialSpans?: Span[];
  onChange: (content: string, spans: Span[]) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const changed = useRef(onChange);
  changed.current = onChange;

  // The editor owns its text after mount; remount it (key) for another document.
  useEffect(() => {
    let saved = "";
    const view = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: initial,
        extensions: [
          history(),
          drawSelection(),
          EditorView.lineWrapping,
          markdownSupport(),
          syntaxHighlighting(markdownStyle),
          syntaxHighlighting(codeStyle),
          livePreview,
          imageUpload,
          dictation,
          tidyRanges,
          searchHits,
          initialTidyRanges(initialSpans, initial.length),
          // Ahead of the Markdown keymap, which has high precedence.
          Prec.highest(
            keymap.of([
              { key: "Enter", run: exitEmptyListItem },
              { key: "Tab", run: indentListItem, shift: dedentListItem },
            ]),
          ),
          keymap.of([
            ...defaultKeymap,
            ...historyKeymap,
            indentWithTab,
          ]),
          theme,
          EditorView.updateListener.of((u) => {
            // The marks save with the text; showing them changes nothing.
            const spans = spansOf(u.state);
            const key = JSON.stringify(spans);
            if (u.docChanged || key !== saved) {
              saved = key;
              changed.current(u.state.doc.toString(), spans);
            }
            setPending(spans.length > 0);
          }),
        ],
      }),
    });
    saved = JSON.stringify(spansOf(view.state));
    setPending(saved !== "[]");
    registerEditor(view, docId);
    return () => {
      setPending(false);
      registerEditor(null);
      view.destroy();
    };
  }, []);

  return <div ref={host} />;
}
