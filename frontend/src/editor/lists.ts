import { type ChangeSpec, type EditorState, type Line, type StateCommand } from "@codemirror/state";

/*
 * Tab and Shift+Tab on list items nest and un-nest them. A nested item has
 * to start where its parent's text starts, which is two columns after "- "
 * but three after "1. ", so the step depends on the item above.
 */

const item = /^(\s*)(?:[-*+]|\d+[.)])\s+/;

/** Leading spaces and the column where the text starts, for a list line. */
function listLine(l: Line) {
  const m = item.exec(l.text);
  return m ? { indent: m[1]!.length, text: m[0].length } : null;
}

function selectedLines(state: EditorState): Line[] {
  const lines: Line[] = [];
  let last = -1;
  for (const r of state.selection.ranges) {
    for (let n = state.doc.lineAt(r.from).number; n <= state.doc.lineAt(r.to).number; n++) {
      if (n > last) lines.push(state.doc.line(n));
      last = n;
    }
  }
  return lines;
}

/** The nearest list item above at the given indent or shallower. */
function itemAbove(state: EditorState, from: Line, maxIndent: number) {
  for (let n = from.number - 1; n >= 1; n--) {
    const l = state.doc.line(n);
    if (!l.text.trim()) continue;
    const info = listLine(l);
    if (!info) {
      // A continuation line inside an item keeps searching; anything else ends the list.
      if (/^\s/.test(l.text)) continue;
      return null;
    }
    if (info.indent <= maxIndent) return { ...info, line: n };
  }
  return null;
}

/** The number for an ordered item placed at indent below the lines above l. */
function numberAt(state: EditorState, l: Line, indent: number) {
  for (let n = l.number - 1; n >= 1; n--) {
    const above = state.doc.line(n);
    if (!above.text.trim()) continue;
    const info = listLine(above);
    if (!info) {
      if (/^\s/.test(above.text)) continue;
      break;
    }
    if (info.indent < indent) break;
    const num = /^\s*(\d+)/.exec(above.text);
    if (info.indent == indent) return num ? Number(num[1]) + 1 : 1;
  }
  return 1;
}

function reindent(state: EditorState, dispatch: Parameters<StateCommand>[0]["dispatch"], deeper: boolean) {
  const lines = selectedLines(state);
  if (!lines.some((l) => listLine(l))) return false;
  const changes: ChangeSpec[] = [];
  for (const l of lines) {
    const info = listLine(l);
    if (!info) continue;
    let to: number;
    if (deeper) {
      // Nest under the sibling above: start at its text column.
      const sibling = itemAbove(state, l, info.indent);
      if (!sibling || sibling.indent != info.indent) continue;
      to = sibling.text;
    } else if (info.indent > 0) {
      // Move out to the parent's column.
      to = itemAbove(state, l, info.indent - 1)?.indent ?? 0;
    } else continue;
    changes.push({ from: l.from, to: l.from + info.indent, insert: " ".repeat(to) });
    // An ordered item takes the next number at its new level.
    const num = /^\s*(\d+)/.exec(l.text);
    if (num) changes.push({ from: l.from + info.indent, to: l.from + num[0].length, insert: String(numberAt(state, l, to)) });
  }
  if (changes.length) {
    const set = state.changes(changes);
    dispatch(
      state.update({
        changes: set,
        selection: state.selection.map(set, 1),
        userEvent: deeper ? "input.indent" : "delete.dedent",
        scrollIntoView: true,
      }),
    );
  }
  return true;
}

export const indentListItem: StateCommand = ({ state, dispatch }) => reindent(state, dispatch, true);
export const dedentListItem: StateCommand = ({ state, dispatch }) => reindent(state, dispatch, false);

const emptyItem = /^(\s*)(?:[-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?$/;

/**
 * Enter on an empty list item leaves the list in one step: a nested item
 * moves out to its parent's level, a top-level item turns into a blank
 * line with the cursor on the next one. (A line right below an item would
 * still belong to it.) Anything else falls through to Markdown's own
 * list continuation.
 */
export const exitEmptyListItem: StateCommand = ({ state, dispatch }) => {
  const sel = state.selection.main;
  if (state.selection.ranges.length > 1 || !sel.empty) return false;
  const l = state.doc.lineAt(sel.head);
  const m = emptyItem.exec(l.text);
  if (!m || sel.head != l.to) return false;
  const indent = m[1]!.length;
  let insert = "\n";
  if (indent > 0) {
    const parent = itemAbove(state, l, indent - 1);
    if (parent) {
      const p = item.exec(state.doc.line(parent.line).text)!;
      const marker = /\d+/.test(p[0]) ? p[0].replace(/\d+/, (n) => String(Number(n) + 1)) : p[0];
      insert = marker + (m[2] ? "[ ] " : "");
    }
  }
  dispatch(
    state.update({
      changes: { from: l.from, to: l.to, insert },
      selection: { anchor: l.from + insert.length },
      userEvent: "input",
      scrollIntoView: true,
    }),
  );
  return true;
};
