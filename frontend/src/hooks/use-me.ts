import { useQuery } from "@tanstack/react-query";

import { api, ApiError } from "@/lib/api";
import { goToLogin, useSession } from "@/lib/session";

/**
 * The signed-in user. A missing session sends the browser to the login page,
 * unless the user signed out on this device; then the signed-out screen shows.
 */
export function useMe() {
  const signedOut = useSession((s) => s.signedOut);
  return useQuery({
    queryKey: ["me"],
    enabled: !signedOut,
    queryFn: async () => {
      try {
        return await api.me();
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) goToLogin();
        throw err;
      }
    },
    retry: (count, err) => !(err instanceof ApiError && err.status === 401) && count < 2,
    staleTime: Infinity,
  });
}
