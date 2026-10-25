import { create } from "zustand";

import { api, type Doc, type DocColor, type DocPatch, type DocSummary } from "@/lib/api";
import { defaultTitle } from "@/lib/dates";
import { focusEditor } from "@/lib/editor-focus";
import { queryClient } from "@/lib/query";
import { discard, docKey, editDocument, flush, isDirty, listKey } from "@/lib/save-queue";
import { dismiss, notify, notifyError } from "@/lib/toast";

const CURRENT_KEY = "asr:current";

export type InputMode = "keyboard" | "voice";

type WorkspaceState = {
  /** The open document. */
  currentId: number | null;
  /** A document created in this page that has not been written in yet. */
  draftId: number | null;
  /** Show only documents of this colour. */
  filter: DocColor | null;
  mode: InputMode;
  /** The sidebar entry being renamed inline. */
  renamingId: number | null;
  /** On a phone, the list shows instead of the open document. */
  listOpen: boolean;
  /** The sidebar search; the list shows results while it is not blank. */
  query: string;
};

function readCurrent() {
  try {
    return Number(localStorage.getItem(CURRENT_KEY)) || null;
  } catch {
    return null;
  }
}

export const useWorkspace = create<WorkspaceState>(() => ({
  currentId: readCurrent(),
  draftId: null,
  filter: null,
  mode: "keyboard",
  renamingId: null,
  listOpen: false,
  query: "",
}));

useWorkspace.subscribe((s, prev) => {
  if (s.currentId === prev.currentId) return;
  try {
    if (s.currentId) localStorage.setItem(CURRENT_KEY, String(s.currentId));
  } catch {
    // Storage disabled: the app opens the latest document next time.
  }
});

const list = () => queryClient.getQueryData<DocSummary[]>(listKey) ?? [];

const dropFromList = (id: number) =>
  queryClient.setQueryData<DocSummary[]>(listKey, (l) => l?.filter((d) => d.id !== id));

/** Edits a document; writing anything keeps a new draft for good. */
export function edit(id: number, patch: DocPatch) {
  const { draftId } = useWorkspace.getState();
  if (id === draftId && (patch.title !== undefined || patch.content)) useWorkspace.setState({ draftId: null });
  editDocument(id, patch);
}

/** Throws away the open document if it is a draft nobody wrote in. */
function leaveCurrent() {
  const { currentId, draftId } = useWorkspace.getState();
  if (currentId === null || currentId !== draftId) return;
  useWorkspace.setState({ draftId: null });
  discard(currentId);
  dropFromList(currentId);
  queryClient.removeQueries({ queryKey: docKey(currentId) });
  api.deleteDocument(currentId).catch(() => {
    // Left in the trash list on the server; it is empty either way.
    void queryClient.invalidateQueries({ queryKey: listKey });
  });
}

/** Opens a document with the cursor at the end of its text, or on the first hit of the search words. */
export function openDocument(id: number, find?: string[]) {
  if (id !== useWorkspace.getState().currentId) {
    leaveCurrent();
    // Load it fresh unless this page still holds unsaved edits to it.
    if (!isDirty(id)) queryClient.removeQueries({ queryKey: docKey(id) });
    useWorkspace.setState({ currentId: id });
  }
  useWorkspace.setState({ listOpen: false });
  focusEditor(find?.length ? { find } : "end", id);
}

let creating: Promise<void> | null = null;

/** Starts a new document, or returns to the blank one already open. */
export function newDocument() {
  const { currentId, draftId } = useWorkspace.getState();
  if (currentId !== null && currentId === draftId) {
    useWorkspace.setState({ listOpen: false });
    focusEditor("end");
    return Promise.resolve();
  }
  creating ??= (async () => {
    try {
      const doc = await api.createDocument(defaultTitle());
      const { content: _, ...summary } = doc;
      queryClient.setQueryData<Doc>(docKey(doc.id), { ...doc, content: "" });
      queryClient.setQueryData<DocSummary[]>(listKey, (l) => [summary, ...(l ?? [])]);
      leaveCurrent();
      useWorkspace.setState({ currentId: doc.id, draftId: doc.id, listOpen: false });
      focusEditor("end", doc.id);
    } catch (err) {
      notifyError(err, "新建文稿失败");
    } finally {
      creating = null;
    }
  })();
  return creating;
}

/** Makes sure some document is open once the list has loaded. */
export function ensureOpen(docs: DocSummary[]) {
  const { currentId } = useWorkspace.getState();
  if (currentId !== null && docs.some((d) => d.id === currentId)) return;
  if (creating) return;
  const first = docs[0];
  if (first) {
    useWorkspace.setState({ currentId: first.id });
    focusEditor("end", first.id);
  } else void newDocument();
}

/** Moves a document to the trash and offers to undo it. */
export async function deleteDocument(id: number) {
  const docs = list();
  const i = docs.findIndex((d) => d.id === id);
  const doc = docs[i];
  if (!doc) return;
  const { currentId, draftId } = useWorkspace.getState();
  if (draftId === id) useWorkspace.setState({ draftId: null });

  await flush(id);
  dropFromList(id);
  if (currentId === id) {
    const next = docs[i + 1] ?? docs[i - 1];
    if (next) openDocument(next.id);
    else {
      useWorkspace.setState({ currentId: null });
      void newDocument();
    }
  }
  try {
    await api.deleteDocument(id);
  } catch (err) {
    void queryClient.invalidateQueries({ queryKey: listKey });
    notifyError(err, "删除失败");
    return;
  }
  notify(`已删除「${doc.title}」`, {
    label: "撤销",
    run: async () => {
      dismiss();
      try {
        await api.restoreDocument(id);
      } catch (err) {
        notifyError(err, "撤销失败");
        return;
      }
      await queryClient.invalidateQueries({ queryKey: listKey });
      openDocument(id);
    },
  });
}

/**
 * A document as Markdown to copy or export, including edits not saved
 * yet. Unless the text already opens with a level-one heading, the title
 * goes on top as one.
 */
export async function documentMarkdown(id: number) {
  const doc = await queryClient.fetchQuery({ queryKey: docKey(id), queryFn: () => api.document(id), staleTime: Infinity });
  return startsWithH1(doc.content) ? doc.content : `# ${doc.title}\n\n${doc.content}`;
}

/** Whether the first non-blank line is an ATX (# ...) or setext (=== underline) level-one heading. */
function startsWithH1(content: string) {
  const lines = content.split("\n");
  const i = lines.findIndex((l) => l.trim() !== "");
  if (i < 0) return false;
  return /^ {0,3}#(\s|$)/.test(lines[i]!) || /^ {0,3}=+\s*$/.test(lines[i + 1] ?? "");
}
