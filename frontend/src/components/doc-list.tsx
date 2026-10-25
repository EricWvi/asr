import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { CopyIcon, DownloadIcon, PencilIcon, Trash2Icon } from "lucide-react";
import { Fragment, type ReactNode, useEffect, useLayoutEffect, useRef, useState } from "react";

import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from "@/components/ui/context-menu";
import { wordsPattern } from "@/editor/search-hits";
import { useDocuments } from "@/hooks/use-documents";
import { api, type DocSummary } from "@/lib/api";
import { groupLabel } from "@/lib/dates";
import { focusEditor } from "@/lib/editor-focus";
import { notify, notifyError } from "@/lib/toast";
import { cn } from "@/lib/utils";
import { deleteDocument, documentMarkdown, edit, openDocument, useWorkspace } from "@/lib/workspace";

const groupHead = "sticky top-0 z-1 bg-bg px-2.5 pt-3 pb-1 text-[11.5px] text-ink-3";
const emptyNote = "px-2.5 py-6 text-center text-[12.5px] text-ink-3";

/** The sidebar list: documents by last update, or search results while the search box holds words. */
export function DocList() {
  const query = useWorkspace((s) => s.query.trim());
  const nav = useRef<HTMLElement>(null);
  const [more, setMore] = useState(false);
  const [count, setCount] = useState(0);

  // Fade the bottom edge while there is more to scroll to.
  const syncMore = () => {
    const el = nav.current;
    if (el) setMore(el.scrollTop + el.clientHeight < el.scrollHeight - 2);
  };
  useLayoutEffect(syncMore, [count, query]);
  useEffect(() => {
    const ro = new ResizeObserver(syncMore);
    if (nav.current) ro.observe(nav.current);
    return () => ro.disconnect();
  }, []);

  return (
    <nav
      ref={nav}
      onScroll={syncMore}
      className={cn(
        "-mx-1 flex-1 overflow-auto px-1 pb-3 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden",
        more && "mask-[linear-gradient(#000_calc(100%-32px),transparent)]",
      )}
    >
      {query ? <SearchResults query={query} onCount={setCount} /> : <Groups onCount={setCount} />}
    </nav>
  );
}

function Groups({ onCount }: { onCount: (n: number) => void }) {
  const { data: docs = [] } = useDocuments();
  const filter = useWorkspace((s) => s.filter);
  const shown = filter ? docs.filter((d) => d.color === filter) : docs;
  useLayoutEffect(() => onCount(shown.length), [shown.length]);

  const groups: { label: string; docs: DocSummary[] }[] = [];
  const now = new Date();
  for (const d of shown) {
    const label = groupLabel(new Date(d.updated_at), now);
    const last = groups.at(-1);
    if (last?.label === label) last.docs.push(d);
    else groups.push({ label, docs: [d] });
  }
  return (
    <>
      {groups.map((g) => (
        <section key={g.label}>
          <div className={groupHead}>{g.label}</div>
          {g.docs.map((d) => (
            <DocItem key={d.id} doc={d} />
          ))}
        </section>
      ))}
      {filter && shown.length === 0 && <div className={emptyNote}>没有这种颜色的文稿</div>}
    </>
  );
}

const SEARCH_DELAY_MS = 150;

function SearchResults({ query, onCount }: { query: string; onCount: (n: number) => void }) {
  const q = useDebounced(query, SEARCH_DELAY_MS);
  const filter = useWorkspace((s) => s.filter);
  const { data, error, isPlaceholderData } = useQuery({
    queryKey: ["search", q],
    queryFn: () => api.search(q),
    placeholderData: keepPreviousData,
  });
  const shown = (data ?? []).filter((d) => !filter || d.color === filter);
  useLayoutEffect(() => onCount(shown.length), [shown.length]);
  const words = q.split(/\s+/).filter(Boolean);

  if (error) return <div className={emptyNote}>{error.message}</div>;
  if (!data) return null;
  if (!shown.length) return isPlaceholderData ? null : <div className={emptyNote}>没有找到「{q}」</div>;
  return (
    <section>
      <div className={groupHead}>找到 {shown.length} 篇</div>
      {shown.map((d) => (
        <DocItem key={d.id} doc={d} hits={words} />
      ))}
    </section>
  );
}

function useDebounced<T>(value: T, ms: number) {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return settled;
}

/** Text with the search words tinted in the document's mark colour; hit styles the tinted words. */
function Hits({ text, words, hit = "text-ink" }: { text: string; words?: string[]; hit?: string }): ReactNode {
  if (!words?.length) return text;
  // Splitting on a capturing group puts the hits at odd indexes.
  return text.split(wordsPattern(words)).map((part, i) =>
    i % 2 ? (
      <span key={i} className={cn("rounded-[3px] bg-tint-mark py-px", hit)}>
        {part}
      </span>
    ) : (
      <Fragment key={i}>{part}</Fragment>
    ),
  );
}

/** hits are the search words, for a search result: they show tinted and opening goes to the first one. */
function DocItem({ doc, hits }: { doc: DocSummary; hits?: string[] }) {
  const current = useWorkspace((s) => s.currentId === doc.id);
  const renaming = useWorkspace((s) => s.renamingId === doc.id);
  // An input may not sit inside a button.
  const Row = renaming ? "div" : "button";

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(await documentMarkdown(doc.id));
      notify("已复制 Markdown");
    } catch (err) {
      notifyError(err, "复制失败");
    }
  };

  const exportFile = async () => {
    try {
      const blob = new Blob([await documentMarkdown(doc.id)], { type: "text/markdown;charset=utf-8" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `${doc.title.replace(/[\\/:*?"<>|]/g, "-")}.md`;
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (err) {
      notifyError(err, "导出失败");
    }
  };

  return (
    <ContextMenu modal={false}>
      <ContextMenuTrigger asChild>
        <Row
          aria-current={current}
          className={cn(
            `tint-${doc.color}`,
            "mb-px flex w-full gap-2.5 rounded-item px-2.5 py-2 text-left transition-colors hover:bg-hover data-[state=open]:bg-hover",
            (current || renaming) && "bg-selected hover:bg-selected",
          )}
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => !renaming && openDocument(doc.id, hits)}
        >
          <span className="mt-px h-[22px] w-[18px] flex-none rounded-[4px] bg-tint-fill shadow-[inset_0_0_0_1px_rgb(0_0_0/0.06)] transition-colors duration-200" />
          <span className="min-w-0 flex-1">
            {renaming ? (
              <RenameInput doc={doc} />
            ) : (
              <span className="block truncate text-[13.5px] font-medium">
                <Hits text={doc.title} words={hits} />
              </span>
            )}
            <span className="mt-px block truncate text-xs text-ink-3">
              {doc.snippet ? <Hits text={doc.snippet} words={hits} hit="text-ink-2" /> : "无内容"}
            </span>
          </span>
        </Row>
      </ContextMenuTrigger>
      <ContextMenuContent onCloseAutoFocus={(e) => e.preventDefault()}>
        <ContextMenuItem onSelect={() => useWorkspace.setState({ renamingId: doc.id })}>
          <PencilIcon />
          重命名
        </ContextMenuItem>
        <ContextMenuItem onSelect={() => void copy()}>
          <CopyIcon />
          复制 Markdown
        </ContextMenuItem>
        <ContextMenuItem onSelect={() => void exportFile()}>
          <DownloadIcon />
          导出 .md 文件
        </ContextMenuItem>
        <ContextMenuSeparator />
        <ContextMenuItem variant="danger" onSelect={() => void deleteDocument(doc.id)}>
          <Trash2Icon />
          删除
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  );
}

/** Enter or leaving the field saves; Escape cancels. */
function RenameInput({ doc }: { doc: DocSummary }) {
  const input = useRef<HTMLInputElement>(null);
  const done = useRef(false);

  useEffect(() => {
    // After the context menu has closed and released focus.
    const t = setTimeout(() => {
      input.current?.focus();
      input.current?.select();
    });
    return () => clearTimeout(t);
  }, []);

  const finish = (save: boolean) => {
    if (done.current) return;
    done.current = true;
    const title = input.current?.value.trim() ?? "";
    if (save && title && title !== doc.title) edit(doc.id, { title });
    useWorkspace.setState({ renamingId: null });
    focusEditor("keep");
  };

  return (
    <input
      ref={input}
      defaultValue={doc.title}
      maxLength={200}
      className="-mx-1.5 -mt-0.5 -mb-px block h-6 w-[calc(100%+12px)] rounded-[6px] bg-surface px-[5px] text-[13.5px] font-medium text-ink shadow-[inset_0_0_0_1px_var(--line),0_0_0_3px_color-mix(in_oklch,var(--blue)_22%,transparent)] outline-none selection:bg-blue/25"
      onClick={(e) => e.stopPropagation()}
      onMouseDown={(e) => e.stopPropagation()}
      onKeyDown={(e) => {
        e.stopPropagation();
        // Keep Enter from typing into the editor that takes focus next.
        if (e.key === "Enter" && !e.nativeEvent.isComposing) {
          e.preventDefault();
          finish(true);
        }
        if (e.key === "Escape") finish(false);
      }}
      onBlur={() => finish(true)}
    />
  );
}
