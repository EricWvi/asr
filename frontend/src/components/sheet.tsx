import { KeyboardIcon, MicIcon } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";

import { Editor } from "@/components/editor";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useDocument } from "@/hooks/use-documents";
import { COLORS, type DocSummary } from "@/lib/api";
import { shortTime } from "@/lib/dates";
import { focusEditor } from "@/lib/editor-focus";
import { useSaveStatus } from "@/lib/save-queue";
import { cn } from "@/lib/utils";
import { edit, type InputMode, useWorkspace } from "@/lib/workspace";

const chip = "inline-flex h-6 items-center rounded-[6px] bg-black/[.045] px-2 text-xs text-ink/62 dark:bg-white/[.07]";

/** The open document on its paper sheet. */
export function Sheet({ doc }: { doc: DocSummary }) {
  const [scrolled, setScrolled] = useState(false);

  return (
    <div
      className={cn(
        `tint-${doc.color}`,
        "group/sheet mx-auto flex min-h-0 w-full max-w-[880px] flex-1 flex-col overflow-auto rounded-sheet border border-line bg-paper shadow-md [scrollbar-width:none] [&::-webkit-scrollbar]:hidden",
      )}
      data-scrolled={scrolled || undefined}
      onScroll={(e) => setScrolled(e.currentTarget.scrollTop > 60)}
    >
      {/* Sticky bar: solid paper; once scrolled it shows the title, a hairline and a faint shadow. */}
      <header className="sticky top-0 z-4 flex h-[60px] flex-none items-center gap-0.5 bg-paper pr-4 pl-7 transition-shadow duration-200 group-data-scrolled/sheet:shadow-[0_1px_0_var(--line),0_8px_16px_-10px_rgb(0_0_0/0.12)] dark:group-data-scrolled/sheet:shadow-[0_1px_0_var(--line),0_8px_16px_-10px_rgb(0_0_0/0.6)]">
        <div className="min-w-0 flex-1 translate-y-1 truncate text-[15px] font-semibold opacity-0 transition duration-200 group-data-scrolled/sheet:translate-y-0 group-data-scrolled/sheet:opacity-100">
          {doc.title}
        </div>
        <ModeSwitch />
      </header>

      <div className="mx-auto w-full max-w-[760px] flex-none px-14 pt-5">
        <TitleField key={doc.id} doc={doc} />
        <div className="mt-3.5 flex flex-wrap items-center gap-1.5">
          <ColorPicker doc={doc} />
          <span className={chip}>{shortTime(new Date(doc.updated_at))}</span>
          <WordCount id={doc.id} />
          <SaveState id={doc.id} />
        </div>
        <div className="mt-6 h-px bg-paper-line" />
      </div>

      <article
        className="mx-auto w-full max-w-[760px] flex-1 px-14 pt-7 pb-[200px]"
        onMouseDown={(e) => {
          // Clicking the blank paper below the text puts the cursor at the end.
          if (e.target !== e.currentTarget) return;
          e.preventDefault();
          focusEditor("end");
        }}
      >
        <Body id={doc.id} />
      </article>
    </div>
  );
}

function Body({ id }: { id: number }) {
  const { data, error, refetch } = useDocument(id);
  if (error)
    return (
      <p className="text-ink-3">
        {error.message}，
        <button className="text-blue" onClick={() => void refetch()}>
          重试
        </button>
      </p>
    );
  if (!data) return null;
  return <Editor key={id} docId={id} initial={data.content} onChange={(content) => edit(id, { content })} />;
}

const modes: { mode: InputMode; label: string; Icon: typeof MicIcon }[] = [
  { mode: "keyboard", label: "键盘", Icon: KeyboardIcon },
  { mode: "voice", label: "语音", Icon: MicIcon },
];

function ModeSwitch() {
  const current = useWorkspace((s) => s.mode);
  return (
    <div className="inline-flex gap-0.5" role="group">
      {modes.map(({ mode, label, Icon }) => (
        <button
          key={mode}
          aria-pressed={current === mode}
          className="inline-flex h-8 items-center gap-1.5 rounded-[8px] px-2.5 text-[13px] text-ink-2 transition-colors hover:bg-hover hover:text-ink aria-pressed:bg-selected aria-pressed:text-ink"
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => useWorkspace.setState({ mode })}
        >
          <Icon className="size-[15px]" strokeWidth={1.75} />
          {label}
        </button>
      ))}
    </div>
  );
}

/** The big title. It saves as you type; leaving it blank restores the old one. */
function TitleField({ doc }: { doc: DocSummary }) {
  const field = useRef<HTMLTextAreaElement>(null);
  const [value, setValue] = useState(doc.title);
  const saved = useRef(doc.title);

  // Follow renames made elsewhere (the sidebar) while not editing here.
  useEffect(() => {
    if (document.activeElement !== field.current) setValue(doc.title);
    saved.current = doc.title;
  }, [doc.title]);

  // Grow with the text instead of scrolling.
  useLayoutEffect(() => {
    const el = field.current!;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, [value]);

  return (
    <textarea
      ref={field}
      rows={1}
      value={value}
      maxLength={200}
      spellCheck={false}
      aria-label="标题"
      className="block w-full resize-none overflow-hidden bg-transparent text-[30px] leading-[1.5] font-bold tracking-[-.01em] text-ink outline-none"
      onChange={(e) => {
        const next = e.target.value.replace(/[\r\n]+/g, " ");
        setValue(next);
        if (next.trim() && next.trim() !== saved.current) edit(doc.id, { title: next.trim() });
      }}
      onKeyDown={(e) => {
        if (e.key === "Enter" && !e.nativeEvent.isComposing) {
          e.preventDefault();
          focusEditor("start");
        }
      }}
      onBlur={() => {
        if (!value.trim()) setValue(saved.current);
      }}
    />
  );
}

function ColorPicker({ doc }: { doc: DocSummary }) {
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          title="文稿颜色"
          className="grid size-6 place-items-center rounded-[6px] bg-black/[.045] dark:bg-white/[.07]"
          onMouseDown={(e) => e.preventDefault()}
        >
          <span className="size-3 rounded-full bg-tint shadow-[inset_0_0_0_1px_rgb(0_0_0/0.12)]" />
        </button>
      </PopoverTrigger>
      <PopoverContent
        className="flex gap-1.5 p-2"
        onOpenAutoFocus={(e) => e.preventDefault()}
        onCloseAutoFocus={(e) => {
          e.preventDefault();
          focusEditor("keep");
        }}
      >
        {COLORS.map((c) => (
          <button
            key={c}
            aria-label={c}
            aria-pressed={doc.color === c}
            className={cn(
              `tint-${c}`,
              "size-[26px] rounded-full bg-tint shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)] transition-transform hover:scale-108",
              "aria-pressed:shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08),0_0_0_2px_var(--surface),0_0_0_4px_var(--blue)]",
            )}
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => {
              if (c !== doc.color) edit(doc.id, { color: c });
              setOpen(false);
            }}
          />
        ))}
      </PopoverContent>
    </Popover>
  );
}

/** Characters other than whitespace, counted in code points. */
function WordCount({ id }: { id: number }) {
  const { data } = useDocument(id);
  if (!data) return null;
  let n = 0;
  for (const ch of data.content) if (!/\s/.test(ch)) n++;
  return <span className={chip}>{n} 字</span>;
}

function SaveState({ id }: { id: number }) {
  const status = useSaveStatus((s) => s.status[id]);
  if (status === "error") return <span className={cn(chip, "text-rec")}>保存失败</span>;
  return <span className={chip}>{status === "saving" ? "保存中…" : "已保存"}</span>;
}
