import { SearchIcon, XIcon } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { clearSearchHits, focusEditor } from "@/lib/editor-focus";
import { cn } from "@/lib/utils";
import { useWorkspace } from "@/lib/workspace";

const mac = /Mac|iPhone|iPad/.test(navigator.platform);
const raised = "bg-surface shadow-[inset_0_0_0_1px_var(--line),var(--shadow-sm)] hover:bg-surface";

/**
 * The sidebar search. It reads as a row like 「新建文稿」 until focused or
 * filled. Enter searches and the list below shows the results; emptying
 * the box goes back to the list and untints the open document. ⌘K /
 * Ctrl+K focuses it, Escape clears it.
 */
export function SearchBox() {
  const [text, setText] = useState(() => useWorkspace.getState().query);
  const input = useRef<HTMLInputElement>(null);
  const search = (query: string) => {
    useWorkspace.setState({ query: query.trim() });
    if (!query.trim()) clearSearchHits();
  };
  const clear = () => {
    setText("");
    search("");
  };

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
        text && raised,
      )}
    >
      <SearchIcon className="size-4 flex-none text-ink-2" strokeWidth={1.75} />
      <input
        ref={input}
        value={text}
        placeholder="搜索"
        autoComplete="off"
        spellCheck={false}
        enterKeyHint="search"
        className="h-full min-w-0 flex-1 bg-transparent font-medium outline-none placeholder:text-ink group-focus-within/search:placeholder:text-ink-3"
        onChange={(e) => {
          setText(e.target.value);
          if (!e.target.value.trim()) search("");
        }}
        onKeyDown={(e) => {
          // Enter while an input method is composing only commits the composition
          // (Safari reports that keydown as not composing but with keyCode 229).
          const composing = e.nativeEvent.isComposing || e.nativeEvent.keyCode === 229;
          if (e.key === "Enter" && !composing) {
            e.preventDefault();
            search(text);
          } else if (e.key === "Escape") {
            e.preventDefault();
            clear();
            focusEditor("keep");
          }
        }}
      />
      {text ? (
        <button
          title="清除"
          className="grid size-6 flex-none place-items-center rounded-[6px] text-ink-3 transition-colors hover:bg-hover hover:text-ink"
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => {
            clear();
            input.current?.focus();
          }}
        >
          <XIcon className="size-3.5" strokeWidth={1.75} />
        </button>
      ) : (
        <kbd className="mr-1 inline-grid h-5 min-w-5 place-items-center rounded-[6px] bg-chip px-[5px] text-[11px] leading-none font-medium text-ink-2 group-focus-within/search:hidden">
          {mac ? "⌘ K" : "Ctrl K"}
        </kbd>
      )}
    </label>
  );
}
