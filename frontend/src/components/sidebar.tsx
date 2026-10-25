import { FilePlusIcon, MicIcon } from "lucide-react";

import { DocList } from "@/components/doc-list";
import { UserMenu } from "@/components/user-menu";
import { COLORS, type User } from "@/lib/api";
import { cn } from "@/lib/utils";
import { newDocument, useWorkspace } from "@/lib/workspace";

export function Sidebar({ user }: { user: User }) {
  const filter = useWorkspace((s) => s.filter);

  return (
    <aside className="flex min-h-0 flex-col pt-2 pr-3 pl-4 max-md:hidden max-md:px-3 max-md:pt-[max(12px,env(safe-area-inset-top))] max-md:pb-[env(safe-area-inset-bottom)] max-md:list-open:flex">
      <div className="flex items-center gap-2.5 px-1.5 pt-0.5 pb-[18px]">
        <div className="grid size-[30px] place-items-center rounded-[9px] bg-surface text-blue shadow-sm">
          <MicIcon className="size-4" strokeWidth={1.75} />
        </div>
        <div className="font-serif text-base leading-none font-semibold tracking-[.06em]">听写</div>
      </div>

      <button
        className="flex h-9 w-full items-center gap-2.5 rounded-item px-2.5 text-left transition-colors hover:bg-hover"
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => void newDocument()}
      >
        <FilePlusIcon className="size-4 text-ink-2" strokeWidth={1.75} />
        新建文稿
      </button>

      <div className="flex items-center justify-between pt-3.5 pr-1 pb-1 pl-2.5 text-[13px] font-semibold">
        <span>文稿</span>
        {/* Filter by colour: one click shows only that colour, a second click clears it. */}
        <div className="flex">
          {COLORS.map((c) => (
            <button
              key={c}
              title="只看这种颜色"
              aria-pressed={filter === c}
              className={cn(`tint-${c}`, "group grid size-[22px] place-items-center")}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => useWorkspace.setState({ filter: filter === c ? null : c })}
            >
              <span
                className={cn(
                  "size-2.5 rounded-full bg-tint-fill shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)] transition-[transform,opacity,box-shadow]",
                  "group-hover:scale-120",
                  filter !== null &&
                    (filter === c
                      ? "shadow-[0_0_0_2px_var(--bg),0_0_0_3.5px_var(--tint-deep)]"
                      : "opacity-35 group-hover:opacity-70"),
                )}
              />
            </button>
          ))}
        </div>
      </div>

      <DocList />
      <UserMenu user={user} />
    </aside>
  );
}
