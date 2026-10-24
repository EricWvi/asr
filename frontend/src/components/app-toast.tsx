import { useToast } from "@/lib/toast";
import { cn } from "@/lib/utils";

/** Sits at the top centre of the main area, over the sheet's sticky bar. */
export function AppToast() {
  const toast = useToast((s) => s.toast);
  if (!toast) return null;
  return (
    <div
      key={toast.key}
      role="status"
      className={cn(
        "absolute top-2 left-1/2 z-10 flex h-11 -translate-x-1/2 animate-in items-center gap-3.5 rounded-[12px] bg-surface text-[13px] whitespace-nowrap shadow-[inset_0_0_0_1px_var(--line),var(--shadow-lg)] fade-in-0 slide-in-from-top-1",
        toast.action ? "pr-1.5 pl-4" : "px-4",
      )}
    >
      <span className="max-w-[320px] truncate">{toast.text}</span>
      {toast.action && (
        <button
          className="h-8 rounded-[8px] px-2.5 font-semibold text-blue transition-colors hover:bg-blue/10"
          onMouseDown={(e) => e.preventDefault()}
          onClick={toast.action.run}
        >
          {toast.action.label}
        </button>
      )}
    </div>
  );
}
