import type { EditorView } from "@codemirror/view";
import { create } from "zustand";

import { markFresh } from "@/editor/dictation";
import { locate, replaceSegments, type Segment, segmentsOf, showUntidied } from "@/editor/tidy";
import { ApiError, tidyText } from "@/lib/api";
import { currentEditor } from "@/lib/editor-focus";
import { notify } from "@/lib/toast";
import { useWorkspace } from "@/lib/workspace";

/*
 * Tidying: every segment of untidied dictation (editor/tidy.ts) goes to the
 * model at once, and once all have answered the results replace them in
 * one undoable change. The text stays editable meanwhile; a segment edited
 * by hand in the meantime keeps its text and loses its result. Talking is
 * off while a tidy runs.
 */

export const useTidy = create<{ /** Whether the open document has untidied text. */ pending: boolean; busy: boolean }>(
  () => ({ pending: false, busy: false }),
);

/** The open editor reports whether it has untidied text. */
export const setPending = (pending: boolean) => {
  if (useTidy.getState().pending !== pending) useTidy.setState({ pending });
};

let run: { view: EditorView; abort: AbortController } | null = null;

type Outcome = { seg: Segment; text: string } | { seg: Segment; err: unknown };

export async function tidy() {
  if (run) return;
  const view = currentEditor();
  if (!view) return;
  const segments = segmentsOf(view.state);
  if (!segments.length) return;
  const r = { view, abort: new AbortController() };
  run = r;
  useTidy.setState({ busy: true });
  showUntidied(view, "busy");

  const outcomes = await Promise.all(
    segments.map(
      (seg): Promise<Outcome> =>
        tidyText({ text: seg.text, headings: seg.headings, prefix: seg.prefix, suffix: seg.suffix }, r.abort.signal).then(
          (text) => ({ seg, text: clean(text, !!(seg.prefix || seg.suffix)) }),
          (err: unknown) => ({ seg, err }),
        ),
    ),
  );
  if (run !== r) return; // Cancelled.
  run = null;
  useTidy.setState({ busy: false });
  if (!view.dom.isConnected) return;

  const results: { from: number; to: number; text: string }[] = [];
  let failure: unknown = null;
  let edited = 0;
  for (const o of outcomes) {
    if ("err" in o) {
      failure ??= o.err;
      continue;
    }
    const at = locate(view.state, o.seg);
    if (!at || !o.text) edited++;
    else results.push({ ...at, text: o.text });
  }
  if (results.length) replaceSegments(view, results, (placed) => markFresh(view, placed));
  else showUntidied(view, "none");

  if (failure) notify(failure instanceof ApiError ? failure.message : "整理失败，请稍后再试");
  else if (edited) notify("整理期间文字被改动，改动的部分没有替换");
}

/** Stops a running tidy; nothing is replaced. */
export function cancelTidy() {
  const r = run;
  if (!r) return;
  run = null;
  r.abort.abort();
  useTidy.setState({ busy: false });
  if (r.view.dom.isConnected) showUntidied(r.view, "none");
}

/**
 * The model's answer as it goes into the text: trimmed, without a code
 * fence around it, and on one line where it sits inside a line.
 */
function clean(text: string, inline: boolean) {
  let t = text.trim();
  const fenced = /^```(?:markdown|md)?\n([\s\S]*)\n```$/.exec(t);
  if (fenced) t = fenced[1]!.trim();
  return inline ? t.replace(/\s*\n\s*/g, "") : t;
}

// Leaving voice mode or the document drops the tidy.
useWorkspace.subscribe((s, prev) => {
  if (s.mode !== prev.mode || s.currentId !== prev.currentId) cancelTidy();
});
