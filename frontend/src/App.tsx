import { useEffect } from "react";

import { AppToast } from "@/components/app-toast";
import { Sheet } from "@/components/sheet";
import { Sidebar } from "@/components/sidebar";
import { VoiceDock } from "@/components/voice-dock";
import { Button } from "@/components/ui/button";
import { useDocuments } from "@/hooks/use-documents";
import { useMe } from "@/hooks/use-me";
import { ApiError } from "@/lib/api";
import { goToLogin, useSession } from "@/lib/session";
import { ensureOpen, useWorkspace } from "@/lib/workspace";

/** Marks the root while a phone shows the list, for the max-md: styles. */
function useListOpenFlag() {
  const listOpen = useWorkspace((s) => s.listOpen);
  useEffect(() => {
    document.documentElement.toggleAttribute("data-list-open", listOpen);
  }, [listOpen]);
}

export function App() {
  useListOpenFlag();
  const signedOut = useSession((s) => s.signedOut);
  const setSignedOut = useSession((s) => s.setSignedOut);
  const me = useMe();

  if (signedOut) {
    return (
      <main className="flex h-full flex-col items-center justify-center gap-4 text-ink-2">
        <p>已退出登录</p>
        <Button
          variant="outline"
          onClick={() => {
            setSignedOut(false);
            goToLogin();
          }}
        >
          登录
        </Button>
      </main>
    );
  }
  // A 401 is already on its way to the login page.
  if (me.isError && !me.isFetching && !(me.error instanceof ApiError && me.error.status === 401)) {
    return (
      <main className="flex h-full flex-col items-center justify-center gap-4 text-ink-2">
        <p>{me.error.message}</p>
        <Button variant="outline" onClick={() => void me.refetch()}>
          重试
        </Button>
      </main>
    );
  }
  if (!me.data) return null; // waiting for the server or the login redirect

  // Phones (narrower than md) get one column: the list or the open document.
  return (
    <div className="mx-auto grid h-full max-w-[1440px] grid-cols-[280px_1fr] py-4 pr-4 max-md:grid-cols-1 max-md:p-0">
      <Sidebar user={me.data} />
      <main className="relative flex min-h-0 min-w-0 flex-col max-md:list-open:hidden">
        <Workspace />
        <AppToast />
      </main>
    </div>
  );
}

function Workspace() {
  const docs = useDocuments();
  const currentId = useWorkspace((s) => s.currentId);
  const mode = useWorkspace((s) => s.mode);
  const current = docs.data?.find((d) => d.id === currentId);

  useEffect(() => {
    if (docs.data) ensureOpen(docs.data);
  }, [docs.data]);

  if (current)
    return (
      <>
        <Sheet doc={current} />
        {/* Voice mode is not offered on phones. */}
        {mode === "voice" && (
          <div className="max-md:hidden">
            <VoiceDock />
          </div>
        )}
      </>
    );
  return (
    <div className="mx-auto flex min-h-0 w-full max-w-[880px] flex-1 items-center justify-center rounded-sheet border border-line bg-paper shadow-md max-md:rounded-none max-md:border-0 max-md:shadow-none">
      {docs.error && (
        <p className="text-[13px] text-ink-3">
          {docs.error.message}，
          <button className="text-blue" onClick={() => void docs.refetch()}>
            重试
          </button>
        </p>
      )}
    </div>
  );
}
