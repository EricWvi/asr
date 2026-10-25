import { type Extension, StateEffect, StateField } from "@codemirror/state";
import { Decoration, type DecorationSet, EditorView } from "@codemirror/view";

/*
 * Search hits in a document opened from the search results: every
 * occurrence of the query's words is tinted until the text is edited or
 * the search is cleared.
 */

const MAX_HITS = 1000;

const setHits = StateEffect.define<DecorationSet>();
const hitMark = Decoration.mark({ class: "cm-search-hit" });

const hits = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(set, tr) {
    for (const e of tr.effects) if (e.is(setHits)) return e.value;
    return tr.docChanged ? Decoration.none : set;
  },
  provide: (f) => EditorView.decorations.from(f),
});

export const searchHits: Extension = hits;

/** Matches any of the words, case-insensitively, longest first so a word containing another wins. */
export const wordsPattern = (words: string[]) =>
  new RegExp(
    `(${words
      .toSorted((a, b) => b.length - a.length)
      .map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
      .join("|")})`,
    "gi",
  );

/**
 * Tints the words' occurrences and puts the cursor on the first, scrolled
 * to the middle. Returns false, changing nothing, if there is none.
 */
export function showSearchHits(view: EditorView, words: string[]): boolean {
  if (!words.length) return false;
  const re = wordsPattern(words);
  const text = view.state.doc.toString();
  const ranges = [];
  for (const m of text.matchAll(re)) {
    ranges.push(hitMark.range(m.index, m.index + m[0].length));
    if (ranges.length >= MAX_HITS) break;
  }
  const first = ranges[0];
  if (!first) return false;
  view.dispatch({
    selection: { anchor: first.from },
    effects: [setHits.of(Decoration.set(ranges)), EditorView.scrollIntoView(first.from, { y: "center" })],
  });
  return true;
}

export function clearHits(view: EditorView) {
  if (view.state.field(hits).size) view.dispatch({ effects: setHits.of(Decoration.none) });
}
