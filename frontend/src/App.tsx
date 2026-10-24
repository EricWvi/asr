import { useEffect } from "react";

import { AppToast } from "@/components/app-toast";
import { Sheet } from "@/components/sheet";
import { Sidebar } from "@/components/sidebar";
import { Button } from "@/components/ui/button";
import { useDocuments } from "@/hooks/use-documents";
import { useMe } from "@/hooks/use-me";
import { ApiError } from "@/lib/api";
import { goToLogin, useSession } from "@/lib/session";
import { ensureOpen, useWorkspace } from "@/lib/workspace";

export function App() {
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

  return (
    <div className="mx-auto grid h-full max-w-[1440px] grid-cols-[280px_1fr] py-4 pr-4">
      <Sidebar user={me.data} />
      <main className="relative flex min-h-0 min-w-0 flex-col">
        <Workspace />
        <AppToast />
      </main>
    </div>
  );
}

function Workspace() {
  const docs = useDocuments();
  const currentId = useWorkspace((s) => s.currentId);
  const current = docs.data?.find((d) => d.id === currentId);

  useEffect(() => {
    if (docs.data) ensureOpen(docs.data);
  }, [docs.data]);

  if (current) return <Sheet doc={current} />;
  return (
    <div className="mx-auto flex min-h-0 w-full max-w-[880px] flex-1 items-center justify-center rounded-sheet border border-line bg-paper shadow-md">
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
