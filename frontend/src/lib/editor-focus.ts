import type { EditorView } from "@codemirror/view";

/*
 * The open document's editor, so that actions outside it (opening or
 * creating a document, clicking the blank paper) can put the cursor in it.
 * A request for a document whose editor has not mounted yet waits for it.
 */

/** keep leaves the selection where it was. */
type Where = "start" | "end" | "keep";

let view: EditorView | null = null;
let viewDoc: number | null = null;
let wanted: { where: Where; doc: number | null } | null = null;

function apply(v: EditorView, where: Where) {
  if (where !== "keep") {
    const pos = where === "end" ? v.state.doc.length : 0;
    // Opening a document shows its top; typing scrolls to the cursor.
    v.dispatch({ selection: { anchor: pos } });
  }
  v.contentDOM.focus({ preventScroll: true });
}

export function registerEditor(v: EditorView | null, doc: number | null = null) {
  view = v;
  viewDoc = doc;
  // Browser tests drive the editor through this.
  if (import.meta.env.DEV) (window as { __editor?: EditorView | null }).__editor = v;
  if (!v || !wanted || (wanted.doc !== null && wanted.doc !== doc)) return;
  // Wait a frame: a remount (StrictMode) may replace this view at once.
  requestAnimationFrame(() => {
    if (view && wanted && (wanted.doc === null || wanted.doc === viewDoc)) {
      apply(view, wanted.where);
      wanted = null;
    }
  });
}

/** Focuses the editor, or the editor of document doc once it is open. */
export function focusEditor(where: Where = "end", doc: number | null = null) {
  if (view && (doc === null || doc === viewDoc)) {
    wanted = null;
    apply(view, where);
  } else wanted = { where, doc };
}
