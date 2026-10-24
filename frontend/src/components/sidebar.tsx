import { MicIcon } from "lucide-react";

import { UserMenu } from "@/components/user-menu";
import type { User } from "@/lib/api";

export function Sidebar({ user }: { user: User }) {
  return (
    <aside className="flex min-h-0 flex-col pt-2 pr-3 pl-4">
      <div className="flex items-center gap-2.5 px-1.5 pt-0.5 pb-[18px]">
        <div className="grid size-[30px] place-items-center rounded-[9px] bg-surface text-blue shadow-sm">
          <MicIcon className="size-4" strokeWidth={1.75} />
        </div>
        <div className="font-serif text-base leading-none font-semibold tracking-[.06em]">听写</div>
      </div>

      <div className="flex items-center justify-between pt-3.5 pr-1 pb-1 pl-2.5 text-[13px] font-semibold">文稿</div>
      <div className="flex-1" />

      <UserMenu user={user} />
    </aside>
  );
}
