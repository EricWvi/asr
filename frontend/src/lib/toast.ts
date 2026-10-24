import { create } from "zustand";

/*
 * The one toast of the app: a short message at the top of the main area,
 * optionally with an action such as undo. A new toast replaces the old.
 */

const TOAST_MS = 6000;

type Action = { label: string; run: () => void };
type Toast = { key: number; text: string; action?: Action };

export const useToast = create<{ toast: Toast | null }>(() => ({ toast: null }));

let timer: ReturnType<typeof setTimeout> | undefined;
let seq = 0;

export function notify(text: string, action?: Action) {
  clearTimeout(timer);
  useToast.setState({ toast: { key: ++seq, text, action } });
  timer = setTimeout(dismiss, TOAST_MS);
}

export function notifyError(err: unknown, fallback: string) {
  notify(err instanceof Error && err.message ? err.message : fallback);
}

export function dismiss() {
  clearTimeout(timer);
  useToast.setState({ toast: null });
}
