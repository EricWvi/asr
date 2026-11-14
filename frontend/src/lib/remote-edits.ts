import { create } from "zustand";

import { api, type Doc } from "@/lib/api";
import { queryClient } from "@/lib/query";
import { docKey, isDirty } from "@/lib/save-queue";
import { useWorkspace } from "@/lib/workspace";
import { useVoice } from "@/voice/controller";
import { useTidy } from "@/voice/tidy";

/*
 * The open document is loaded once, and the editor owns its text after
 * that. When the page is looked at again it checks whether another device
 * changed the document meanwhile, and if so shows that version, unless
 * this page has something of its own in flight.
 */

/** Bumped each time the open document is replaced by a version from the server. */
export const useReloads = create<{ revision: number }>(() => ({ revision: 0 }));

const quiet = (id: number) =>
  useWorkspace.getState().currentId === id &&
  !isDirty(id) &&
  useVoice.getState().phase === "idle" &&
  !useTidy.getState().busy;

async function reloadCurrent() {
  const id = useWorkspace.getState().currentId;
  if (id === null) return;
  const cached = queryClient.getQueryData<Doc>(docKey(id));
  if (!cached || !quiet(id)) return;
  let doc: Doc;
  try {
    doc = await api.document(id);
  } catch {
    // Deleted elsewhere or offline; the list refresh deals with the former.
    return;
  }
  // Any local edit meanwhile replaced the cached copy.
  if (doc.content === cached.content || !quiet(id) || queryClient.getQueryData(docKey(id)) !== cached) return;
  queryClient.setQueryData<Doc>(docKey(id), doc);
  useReloads.setState(({ revision }) => ({ revision: revision + 1 }));
}

export function watchRemoteEdits() {
  addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") void reloadCurrent();
  });
}
