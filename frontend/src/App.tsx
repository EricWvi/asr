import { Sidebar } from "@/components/sidebar";
import { Button } from "@/components/ui/button";
import { useMe } from "@/hooks/use-me";
import { ApiError } from "@/lib/api";
import { goToLogin, useSession } from "@/lib/session";

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
      <main className="flex min-h-0 min-w-0 flex-col">
        <div className="mx-auto flex min-h-0 w-full max-w-[880px] flex-1 items-center justify-center rounded-sheet border border-line bg-paper shadow-md">
          <p className="text-[13px] text-ink-3">还没有文稿</p>
        </div>
      </main>
    </div>
  );
}
