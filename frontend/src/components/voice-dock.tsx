import { insertNewlineContinueMarkup } from "@codemirror/lang-markdown";
import { insertNewlineAndIndent } from "@codemirror/commands";
import { CornerDownLeftIcon, MicIcon, SparklesIcon } from "lucide-react";
import { useEffect } from "react";

import { exitEmptyListItem } from "@/editor/lists";
import { currentEditor } from "@/lib/editor-focus";
import { cn } from "@/lib/utils";
import { abort, press, release, useVoice } from "@/voice/controller";

const side =
  "inline-flex h-[46px] items-center gap-2 rounded-full px-4 text-[13px] text-ink-2 transition-colors hover:bg-hover hover:text-ink disabled:pointer-events-none disabled:opacity-45";

/** The floating bar of voice mode: tidy | hold to talk | new line. */
export function VoiceDock() {
  const phase = useVoice((s) => s.phase);
  const left = useVoice((s) => s.left);
  const recording = phase === "recording";
  usePushToTalkKey();

  return (
    <div className="absolute bottom-9 left-1/2 z-5 flex -translate-x-1/2 items-center gap-1.5 rounded-full bg-surface/88 p-1.5 shadow-[inset_0_0_0_1px_var(--line),var(--shadow-lg)] backdrop-blur-[16px]">
      {/* Disabled until there is something to tidy (stage 5); the wrapper shows why. */}
      <span className="cursor-help" title="没有待整理的文字">
        <button className={side} disabled tabIndex={-1}>
          <SparklesIcon className="size-4" strokeWidth={1.75} />
          整理
        </button>
      </span>
      <button
        className={cn(
          "inline-flex h-[50px] min-w-[220px] items-center justify-center gap-2.5 rounded-full bg-blue px-6 text-[14.5px] font-semibold text-white shadow-[0_6px_16px_-6px_color-mix(in_oklch,var(--blue)_70%,transparent)] transition-[transform,background-color,box-shadow] duration-200 select-none active:scale-[.98] active:bg-rec",
          recording && "scale-[.98] bg-rec shadow-[0_0_0_6px_color-mix(in_oklch,var(--rec)_18%,transparent)]",
        )}
        aria-pressed={recording}
        disabled={phase === "finishing"}
        // Keep the editor focused, with its cursor where the text will go.
        onMouseDown={(e) => e.preventDefault()}
        onContextMenu={(e) => e.preventDefault()}
        onPointerDown={(e) => {
          if (e.button !== 0) return;
          e.currentTarget.setPointerCapture(e.pointerId);
          press("pointer");
        }}
        onPointerUp={() => void release("pointer")}
        onPointerCancel={() => void release("pointer")}
        onLostPointerCapture={() => void release("pointer")}
      >
        {recording ? <Wave /> : <MicIcon className="size-[18px]" strokeWidth={2} />}
        {recording ? "松开结束" : "按住 空格 说话"}
        {recording && left !== null && (
          <span className="font-medium tabular-nums opacity-80">
            {Math.floor(left / 60)}:{String(left % 60).padStart(2, "0")}
          </span>
        )}
      </button>
      <button
        className={side}
        disabled={phase !== "idle"}
        onMouseDown={(e) => e.preventDefault()}
        onClick={newLine}
      >
        <CornerDownLeftIcon className="size-4" strokeWidth={1.75} />
        换行
      </button>
    </div>
  );
}

function Wave() {
  return (
    <span className="voice-wave inline-flex h-[18px] items-center gap-[3px]" aria-hidden>
      <i />
      <i />
      <i />
      <i />
      <i />
    </span>
  );
}

/** Starts a new line at the cursor, as Enter would. */
function newLine() {
  const view = currentEditor();
  if (!view) return;
  if (!exitEmptyListItem(view) && !insertNewlineContinueMarkup(view)) insertNewlineAndIndent(view);
  view.focus();
}

/** Typing somewhere other than the document: the title, a rename box, a menu. */
function typingElsewhere(target: EventTarget | null) {
  if (!(target instanceof Element)) return false;
  if (target.closest(".cm-content")) return false;
  return !!target.closest("input, textarea, select, [contenteditable], [role=menu], [role=dialog]");
}

/** Holding the space bar talks; a tap types a space (see voice/controller.ts). */
function usePushToTalkKey() {
  useEffect(() => {
    const isSpace = (e: KeyboardEvent) => e.key === " " && !e.ctrlKey && !e.metaKey && !e.altKey && !e.shiftKey;
    const down = (e: KeyboardEvent) => {
      if (!isSpace(e) || e.isComposing || typingElsewhere(e.target)) return;
      e.preventDefault();
      if (!e.repeat) press("key");
    };
    const up = (e: KeyboardEvent) => {
      if (e.key !== " ") return;
      void release("key");
    };
    const leave = () => void release();
    const hidden = () => document.hidden && leave();
    window.addEventListener("keydown", down, true);
    window.addEventListener("keyup", up, true);
    window.addEventListener("blur", leave);
    document.addEventListener("visibilitychange", hidden);
    return () => {
      window.removeEventListener("keydown", down, true);
      window.removeEventListener("keyup", up, true);
      window.removeEventListener("blur", leave);
      document.removeEventListener("visibilitychange", hidden);
      abort();
    };
  }, []);
}
