import { QueryClient } from "@tanstack/react-query";

/** Shared so that modules outside React (the save queue) can update the cache. */
export const queryClient = new QueryClient();
