import { type Extension, StateEffect, StateField } from "@codemirror/state";
import { Decoration, type DecorationSet, EditorView, WidgetType } from "@codemirror/view";

import { markDictated } from "@/editor/tidy";

/*
 * Dictation in the editor. While the user speaks, what has been heard so
 * far shows faintly at the insertion point without entering the text. On
 * release the final text replaces the selection the dictation started with,
 * as one undoable change, and gets an underline in the document's deep tint
 * that fades after a few seconds. The text is marked for tidying
 * (editor/tidy.ts).
 */

type Pending = { from: number; to: number; text: string };

const begin = StateEffect.define<null>();
const interim = StateEffect.define<string>();
const end = StateEffect.define<null>();

const pending = StateField.define<Pending | null>({
  create: () => null,
  update(p, tr) {
    for (const e of tr.effects) {
      if (e.is(begin)) {
        const { from, to } = tr.state.selection.main;
        return { from, to, text: "" };
      }
      if (e.is(end)) return null;
    }
    if (!p) return p;
    let next = p;
    if (tr.docChanged) {
      const from = tr.changes.mapPos(p.from, 1);
      next = { ...p, from, to: Math.max(from, tr.changes.mapPos(p.to, -1)) };
    }
    for (const e of tr.effects) if (e.is(interim)) next = { ...next, text: e.value };
    return next;
  },
  provide: (f) =>
    EditorView.decorations.from(f, (p) =>
      p?.text ? Decoration.set(Decoration.widget({ widget: new InterimWidget(p.text), side: -1 }).range(p.to)) : Decoration.none,
    ),
});

class InterimWidget extends WidgetType {
  constructor(readonly text: string) {
    super();
  }
  eq(other: InterimWidget) {
    return other.text === this.text;
  }
  toDOM() {
    const span = document.createElement("span");
    span.className = "cm-dictation-interim";
    span.textContent = this.text;
    return span;
  }
  ignoreEvent() {
    return false;
  }
}

/* The underline under freshly dictated text: shown, then fading, then gone. */

const FRESH_MS = 2500;
const FADE_MS = 1200;

const addFresh = StateEffect.define<{ id: number; from: number; to: number }>();
const fadeFresh = StateEffect.define<number>();
const dropFresh = StateEffect.define<number>();

const freshMark = (id: number, fading: boolean) =>
  Decoration.mark({ class: fading ? "cm-dictation-fresh cm-dictation-fading" : "cm-dictation-fresh", fresh: id });

const fresh = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(set, tr) {
    set = set.map(tr.changes);
    for (const e of tr.effects) {
      if (e.is(addFresh)) {
        set = set.update({ add: [freshMark(e.value.id, false).range(e.value.from, e.value.to)] });
      } else if (e.is(fadeFresh) || e.is(dropFresh)) {
        const id = e.value;
        const kept: { from: number; to: number }[] = [];
        set = set.update({
          filter: (from, to, d) => {
            if ((d.spec as { fresh?: number }).fresh !== id) return true;
            if (e.is(fadeFresh)) kept.push({ from, to });
            return false;
          },
        });
        if (kept.length) set = set.update({ add: kept.map((r) => freshMark(id, true).range(r.from, r.to)) });
      }
    }
    return set;
  },
  provide: (f) => EditorView.decorations.from(f),
});

let freshSeq = 0;

/**
 * Effects that underline the spans (positions after the change they go
 * with); the underline fades on its own.
 */
export function markFresh(view: EditorView, spans: readonly (readonly [number, number])[]): StateEffect<unknown>[] {
  const id = ++freshSeq;
  // The editor may be gone by then (another document opened).
  const later = (effect: StateEffect<number>) => () => {
    if (view.dom.isConnected) view.dispatch({ effects: effect });
  };
  setTimeout(later(fadeFresh.of(id)), FRESH_MS);
  setTimeout(later(dropFresh.of(id)), FRESH_MS + FADE_MS);
  return spans.filter(([from, to]) => from < to).map(([from, to]) => addFresh.of({ id, from, to }));
}

/** Marks the selection as the insertion point and starts showing interim text. */
export function beginDictation(view: EditorView) {
  view.dispatch({ effects: begin.of(null) });
}

/** Shows what has been heard so far at the insertion point. */
export function showInterim(view: EditorView, text: string) {
  if (view.state.field(pending, false)) view.dispatch({ effects: interim.of(text) });
}

/** Writes the final text in place of the insertion point, or only clears it when text is empty. */
export function commitDictation(view: EditorView, text: string) {
  const p = view.state.field(pending, false);
  if (!p) return;
  if (!text) {
    view.dispatch({ effects: end.of(null) });
    return;
  }
  const to = p.from + text.length;
  view.dispatch({
    changes: { from: p.from, to: p.to, insert: text },
    selection: { anchor: to },
    effects: [end.of(null), markDictated(p.from, to), ...markFresh(view, [[p.from, to]])],
    userEvent: "input.dictation",
    scrollIntoView: true,
  });
}

/** Drops the dictation without touching the text. */
export function cancelDictation(view: EditorView) {
  if (view.state.field(pending, false)) view.dispatch({ effects: end.of(null) });
}

export const dictation: Extension = [pending, fresh];
