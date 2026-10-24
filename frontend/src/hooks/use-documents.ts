import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { docKey, listKey } from "@/lib/save-queue";

export function useDocuments() {
  return useQuery({ queryKey: listKey, queryFn: api.documents });
}

/** One document with its content. It is loaded fresh each time it is opened. */
export function useDocument(id: number | null) {
  return useQuery({
    queryKey: docKey(id ?? 0),
    queryFn: () => api.document(id!),
    enabled: id !== null,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}
