import { invertedEffects, isolateHistory } from "@codemirror/commands";
import {
  type ChangeDesc,
  type EditorState,
  type Extension,
  StateEffect,
  StateField,
  type Transaction,
} from "@codemirror/state";
import { Decoration, EditorView } from "@codemirror/view";

/*
 * Dictated text awaiting tidying. Each dictation marks the text it wrote;
 * the marks follow edits, and one whose inside the user edits by hand is
 * dropped. Tidying replaces whole marked stretches, which drops their
 * marks too. Undo and redo put the marks back as they were. The marks are
 * invisible except while the tidy button is hovered (peek) or a tidy is
 * running (busy).
 */

/** [from, to) in the document, in order, never overlapping. */
export type Span = [number, number];

type Mark = { id: number; from: number; to: number };
type Show = "none" | "peek" | "busy";
type Untidied = { marks: readonly Mark[]; show: Show };

let markSeq = 0;

const mapSpans = (spans: readonly Span[], changes: ChangeDesc) =>
  spans.flatMap(([from, to]): Span[] => {
    const a = changes.mapPos(from, 1);
    const b = changes.mapPos(to, -1);
    return a < b ? [[a, b]] : [];
  });

/** Marks text. Positions are in the document after the transaction's changes. */
const addSpans = StateEffect.define<readonly Span[]>({ map: mapSpans });
/** Unmarks whatever overlaps the spans. */
const dropSpans = StateEffect.define<readonly Span[]>({ map: mapSpans });
const setShow = StateEffect.define<Show>();

/** Whether a change of [fromA, toA) in the old document reaches inside the mark; touching its edges does not. */
const reachesInside = (m: Mark, fromA: number, toA: number) =>
  fromA === toA ? fromA > m.from && fromA < m.to : fromA < m.to && toA > m.from;

/**
 * Whether the transaction unmarks the text it changes: edits by hand and
 * tidying do; dictation does not, and undo and redo carry their own marks.
 * (A replaced stretch would otherwise keep its mark on the new text.)
 */
const unmarksChanged = (tr: Transaction) =>
  !tr.isUserEvent("input.dictation") && !tr.isUserEvent("undo") && !tr.isUserEvent("redo");

function insert(marks: Mark[], [from, to]: Span) {
  // Text dictated into a marked stretch is already covered.
  if (marks.some((m) => m.from < to && m.to > from)) return;
  marks.push({ id: ++markSeq, from, to });
  marks.sort((a, b) => a.from - b.from);
}

const untidied = StateField.define<Untidied>({
  create: () => ({ marks: [], show: "none" }),
  update(value, tr) {
    let { marks, show } = value;
    if (tr.docChanged) {
      const manual = unmarksChanged(tr);
      const next: Mark[] = [];
      for (const m of marks) {
        let touched = false;
        if (manual) tr.changes.iterChangedRanges((fromA, toA) => (touched ||= reachesInside(m, fromA, toA)));
        const from = tr.changes.mapPos(m.from, 1);
        const to = tr.changes.mapPos(m.to, -1);
        if (!touched && from < to) next.push(from === m.from && to === m.to ? m : { ...m, from, to });
      }
      marks = next;
    }
    for (const e of tr.effects) {
      if (e.is(addSpans)) {
        const next = [...marks];
        for (const span of e.value) insert(next, span);
        marks = next;
      } else if (e.is(dropSpans)) {
        const spans = e.value;
        marks = marks.filter((m) => !spans.some(([from, to]) => m.from < to && m.to > from));
      } else if (e.is(setShow)) show = e.value;
    }
    return marks === value.marks && show === value.show ? value : { marks, show };
  },
  provide: (f) =>
    EditorView.decorations.from(f, ({ marks, show }) => {
      if (show === "none" || !marks.length) return Decoration.none;
      const mark = Decoration.mark({ class: show === "peek" ? "cm-tidy-peek" : "cm-tidy-busy" });
      return Decoration.set(marks.map((m) => mark.range(m.from, m.to)));
    }),
});

/*
 * Undoing a change brings back the marks it dropped and removes those it
 * added, positioned in the document as it was before the change.
 */
const invertMarks = invertedEffects.of((tr) => {
  const before = tr.startState.field(untidied).marks;
  const after = tr.state.field(untidied).marks;
  if (before === after) return [];
  const dropped = before.filter((m) => !after.some((n) => n.id === m.id)).map((m): Span => [m.from, m.to]);
  const back = tr.changes.invertedDesc;
  const added = after
    .filter((m) => !before.some((n) => n.id === m.id))
    .map((m): Span => [back.mapPos(m.from, 1), back.mapPos(m.to, -1)])
    .filter(([from, to]) => from < to);
  const out: StateEffect<unknown>[] = [];
  if (dropped.length) out.push(addSpans.of(dropped));
  if (added.length) out.push(dropSpans.of(added));
  return out;
});

export const tidyRanges: Extension = [untidied, invertMarks];

/** Sets up the marks of a freshly opened document, ignoring any that do not fit its text. */
export function initialTidyRanges(spans: readonly Span[] | undefined, length: number): Extension {
  const marks: Mark[] = [];
  for (const [from, to] of spans ?? []) {
    if (Number.isInteger(from) && Number.isInteger(to) && from >= 0 && from < to && to <= length) insert(marks, [from, to]);
  }
  return untidied.init(() => ({ marks, show: "none" }));
}

/** The marked stretches, for saving with the text. */
export function spansOf(state: EditorState): Span[] {
  return state.field(untidied, false)?.marks.map((m): Span => [m.from, m.to]) ?? [];
}

/** Effect that marks freshly dictated text. */
export const markDictated = (from: number, to: number) => addSpans.of([[from, to]]);

/** Whether the marks or what they show changed in this transaction. */
export const tidyRangesChanged = (tr: Transaction) => tr.startState.field(untidied, false) !== tr.state.field(untidied, false);

/** Shows the marks: hinted while the tidy button is hovered, animated while tidying. */
export function showUntidied(view: EditorView, show: Show) {
  const f = view.state.field(untidied, false);
  if (f && f.show !== show) view.dispatch({ effects: setShow.of(show) });
}

/*
 * A segment is what goes to the model as one piece: marks next to each
 * other, or apart only by blank space, form one.
 */

export type Segment = {
  ids: number[];
  from: number;
  to: number;
  text: string;
  /** Heading lines above the segment, as context for new headings. */
  headings: string[];
  /** Text before and after the segment on its first and last lines. */
  prefix: string;
  suffix: string;
};

export function segmentsOf(state: EditorState): Segment[] {
  const { marks } = state.field(untidied);
  const doc = state.doc;
  const groups: Mark[][] = [];
  for (const m of marks) {
    const last = groups.at(-1);
    const prev = last?.at(-1);
    if (last && prev && /^\s*$/.test(doc.sliceString(prev.to, m.from))) last.push(m);
    else groups.push([m]);
  }
  return groups.map((g) => {
    const from = g[0]!.from;
    const to = g.at(-1)!.to;
    const first = doc.lineAt(from);
    const last = doc.lineAt(to);
    const prefix = doc.sliceString(first.from, from);
    const suffix = doc.sliceString(to, last.to);
    return {
      ids: g.map((m) => m.id),
      from,
      to,
      text: doc.sliceString(from, to),
      headings: headingsBefore(state, first.number),
      prefix: prefix.trim() ? prefix : "",
      suffix: suffix.trim() ? suffix : "",
    };
  });
}

/** ATX headings on lines before the given one, skipping fenced code. */
function headingsBefore(state: EditorState, line: number): string[] {
  const out: string[] = [];
  let fence: string | null = null;
  for (let n = 1; n < line; n++) {
    const text = state.doc.line(n).text;
    const open = /^\s{0,3}(`{3,}|~{3,})/.exec(text)?.[1];
    if (open) {
      if (fence === null) fence = open[0]!;
      else if (open[0] === fence) fence = null;
      continue;
    }
    if (fence === null && /^\s{0,3}#{1,6}\s/.test(text)) out.push(text.trim());
  }
  return out;
}

/**
 * Where a segment is now, if it is still untouched: all its marks still
 * there and the text from the first to the last as it was sent.
 */
export function locate(state: EditorState, seg: Segment): { from: number; to: number } | null {
  const { marks } = state.field(untidied);
  const found = seg.ids.map((id) => marks.find((m) => m.id === id));
  const first = found[0];
  const last = found.at(-1);
  if (!first || !last || found.some((m) => !m)) return null;
  return state.doc.sliceString(first.from, last.to) === seg.text ? { from: first.from, to: last.to } : null;
}

/**
 * Writes tidied text over segments as one undoable change. decorate gets
 * where the new text lands and returns effects to go with the change.
 */
export function replaceSegments(
  view: EditorView,
  results: { from: number; to: number; text: string }[],
  decorate: (placed: Span[]) => StateEffect<unknown>[] = () => [],
) {
  const sorted = results.toSorted((a, b) => a.from - b.from);
  let shift = 0;
  const placed = sorted.map(({ from, to, text }): Span => {
    const at = from + shift;
    shift += text.length - (to - from);
    return [at, at + text.length];
  });
  view.dispatch({
    changes: sorted.map(({ from, to, text }) => ({ from, to, insert: text })),
    effects: [setShow.of("none"), ...decorate(placed)],
    annotations: isolateHistory.of("full"),
    userEvent: "tidy",
  });
}
