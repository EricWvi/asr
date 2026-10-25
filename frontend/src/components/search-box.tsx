import { SearchIcon, XIcon } from "lucide-react";
import { useEffect, useRef } from "react";

import { focusEditor } from "@/lib/editor-focus";
import { cn } from "@/lib/utils";
import { useWorkspace } from "@/lib/workspace";

const mac = /Mac|iPhone|iPad/.test(navigator.platform);
const raised = "bg-surface shadow-[inset_0_0_0_1px_var(--line),var(--shadow-sm)] hover:bg-surface";

/**
 * The sidebar search. It reads as a row like 「新建文稿」 until focused or
 * filled; while it holds words the list below shows results. ⌘K / Ctrl+K
 * focuses it, Escape clears it.
 */
export function SearchBox() {
  const query = useWorkspace((s) => s.query);
  const input = useRef<HTMLInputElement>(null);
  const set = (query: string) => useWorkspace.setState({ query });

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() !== "k" || !(mac ? e.metaKey : e.ctrlKey) || e.shiftKey || e.altKey) return;
      e.preventDefault();
      input.current?.focus();
      input.current?.select();
    };
    window.addEventListener("keydown", key, true);
    return () => window.removeEventListener("keydown", key, true);
  }, []);

  return (
    <label
      className={cn(
        "group/search flex h-9 w-full flex-none cursor-text items-center gap-2.5 rounded-item pr-1.5 pl-2.5 transition-[background-color,box-shadow] hover:bg-hover",
        "focus-within:bg-surface focus-within:shadow-[inset_0_0_0_1px_var(--line),var(--shadow-sm)] focus-within:hover:bg-surface",
        query && raised,
      )}
    >
      <SearchIcon className="size-4 flex-none text-ink-2" strokeWidth={1.75} />
      <input
        ref={input}
        value={query}
        placeholder="搜索"
        autoComplete="off"
        spellCheck={false}
        className="h-full min-w-0 flex-1 bg-transparent outline-none placeholder:text-ink group-focus-within/search:placeholder:text-ink-3"
        onChange={(e) => set(e.target.value)}
        onKeyDown={(e) => {
          if (e.key !== "Escape") return;
          e.preventDefault();
          set("");
          focusEditor("keep");
        }}
      />
      {query ? (
        <button
          title="清除"
          className="grid size-6 flex-none place-items-center rounded-[6px] text-ink-3 transition-colors hover:bg-hover hover:text-ink"
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => {
            set("");
            input.current?.focus();
          }}
        >
          <XIcon className="size-3.5" strokeWidth={1.75} />
        </button>
      ) : (
        <kbd className="mr-1 inline-grid h-5 min-w-5 place-items-center rounded-[6px] bg-chip px-[5px] text-[11px] leading-none font-medium text-ink-2 group-focus-within/search:hidden">
          {mac ? "⌘K" : "Ctrl K"}
        </kbd>
      )}
    </label>
  );
}
