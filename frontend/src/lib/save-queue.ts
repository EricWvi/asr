import { create } from "zustand";

import { api, ApiError, writing, type Doc, type DocPatch, type DocSummary } from "@/lib/api";
import { queryClient } from "@/lib/query";

/*
 * Edits go to the query cache at once and reach the server debounced, one
 * request per document at a time. The server keeps the last write.
 */

export const listKey = ["documents"] as const;
export const docKey = (id: number) => ["document", id] as const;

const DEBOUNCE_MS = 800;
const RETRY_MS = 5000;

/** Absent means everything is saved. */
type SaveStatus = "saving" | "error";

export const useSaveStatus = create<{ status: Record<number, SaveStatus> }>(() => ({ status: {} }));

const setStatus = (id: number, s: SaveStatus | null) =>
  useSaveStatus.setState(({ status }) => {
    const next = { ...status };
    if (s) next[id] = s;
    else delete next[id];
    return { status: next };
  });

const pending = new Map<number, DocPatch>();
const timers = new Map<number, ReturnType<typeof setTimeout>>();
const inflight = new Map<number, Promise<void>>();

/** Updates the sidebar entry of a document in place. */
export function patchSummary(id: number, fields: Partial<DocSummary>) {
  queryClient.setQueryData<DocSummary[]>(listKey, (list) => list?.map((d) => (d.id === id ? { ...d, ...fields } : d)));
}

function sortList() {
  queryClient.setQueryData<DocSummary[]>(listKey, (list) =>
    list?.toSorted((a, b) => b.updated_at.localeCompare(a.updated_at) || b.id - a.id),
  );
}

/** Applies an edit locally and schedules saving it. Colours save at once. */
export function editDocument(id: number, patch: DocPatch) {
  queryClient.setQueryData<Doc>(docKey(id), (d) => d && { ...d, ...patch });
  const { content: _, ...summary } = patch;
  if (Object.keys(summary).length) patchSummary(id, summary);

  pending.set(id, { ...pending.get(id), ...patch });
  setStatus(id, "saving");
  clearTimeout(timers.get(id));
  if (patch.content === undefined && patch.title === undefined) void flush(id);
  else timers.set(id, setTimeout(() => void flush(id), DEBOUNCE_MS));
}

/** Whether the document has edits the server has not acknowledged. */
export const isDirty = (id: number) => pending.has(id) || inflight.has(id);

/** Sends the document's pending edits now and waits until they are saved. */
export async function flush(id: number, keepalive = false): Promise<void> {
  clearTimeout(timers.get(id));
  timers.delete(id);
  while (inflight.has(id)) await inflight.get(id);
  const patch = pending.get(id);
  if (!patch) return;
  pending.delete(id);

  const run = (async () => {
    try {
      // Browsers cap keepalive bodies at 64 KiB.
      const small = new TextEncoder().encode(JSON.stringify(patch)).length < 60_000;
      const saved = await api.updateDocument(id, patch, keepalive && small);
      // Only take what the server derived; newer local edits may be pending.
      patchSummary(id, { updated_at: saved.updated_at, snippet: saved.snippet });
      queryClient.setQueryData<Doc>(docKey(id), (d) => d && { ...d, updated_at: saved.updated_at });
      sortList();
      if (!pending.has(id)) setStatus(id, null);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        // Deleted elsewhere; nothing left to save into.
        pending.delete(id);
        setStatus(id, null);
        return;
      }
      pending.set(id, { ...patch, ...pending.get(id) });
      setStatus(id, "error");
      clearTimeout(timers.get(id));
      timers.set(id, setTimeout(() => void flush(id), RETRY_MS));
    }
  })();
  inflight.set(id, run);
  await run;
  inflight.delete(id);
  if (pending.has(id) && !timers.has(id)) await flush(id);
}

/** Drops unsaved edits, for a document that is being thrown away. */
export function discard(id: number) {
  clearTimeout(timers.get(id));
  timers.delete(id);
  pending.delete(id);
  setStatus(id, null);
}

// Push out whatever is pending when the page is hidden or closed.
addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") for (const id of pending.keys()) void flush(id, true);
});
addEventListener("pagehide", () => {
  for (const id of pending.keys()) void flush(id, true);
});

// Ask before leaving while edits or other writes have not reached the server.
addEventListener("beforeunload", (e) => {
  if (pending.size || inflight.size || writing()) e.preventDefault();
});
