import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { markdown, markdownLanguage } from "@codemirror/lang-markdown";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { EditorState } from "@codemirror/state";
import { drawSelection, EditorView, keymap } from "@codemirror/view";
import { tags } from "@lezer/highlight";
import { useEffect, useRef } from "react";

import { registerEditor } from "@/lib/editor-focus";

/*
 * Plain Markdown source with syntax styling. Live preview (hiding marks
 * off the cursor line) comes later as decorations on top of this.
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
  // #, -, >, **, ` and other syntax marks take the document's deep tint.
  { tag: tags.processingInstruction, color: "var(--tint-deep)", fontWeight: "500" },
  { tag: tags.strong, fontWeight: "600" },
  { tag: tags.emphasis, fontStyle: "italic" },
  { tag: tags.strikethrough, textDecoration: "line-through" },
  { tag: tags.link, color: "var(--blue)" },
  { tag: tags.url, color: "var(--ink-3)" },
  { tag: tags.monospace, fontFamily: '"SF Mono", ui-monospace, Menlo, monospace', fontSize: "0.85em" },
  { tag: tags.quote, color: "var(--ink-2)" },
  { tag: tags.contentSeparator, color: "var(--ink-3)" },
]);

export function Editor({
  docId,
  initial,
  onChange,
}: {
  docId: number;
  initial: string;
  onChange: (content: string) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const changed = useRef(onChange);
  changed.current = onChange;

  // The editor owns its text after mount; remount it (key) for another document.
  useEffect(() => {
    const view = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: initial,
        extensions: [
          history(),
          drawSelection(),
          EditorView.lineWrapping,
          markdown({ base: markdownLanguage }),
          syntaxHighlighting(markdownStyle),
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
          theme,
          EditorView.updateListener.of((u) => {
            if (u.docChanged) changed.current(u.state.doc.toString());
          }),
        ],
      }),
    });
    registerEditor(view, docId);
    return () => {
      registerEditor(null);
      view.destroy();
    };
  }, []);

  return <div ref={host} />;
}
