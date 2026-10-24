import type { EditorView } from "@codemirror/view";
import { create } from "zustand";

import { beginDictation, cancelDictation, commitDictation, showInterim } from "@/editor/dictation";
import { currentEditor } from "@/lib/editor-focus";
import { notify } from "@/lib/toast";
import { useWorkspace } from "@/lib/workspace";
import { type AsrOutcome, AsrSocket } from "@/voice/asr-socket";
import { Recorder } from "@/voice/recorder";

/*
 * Push to talk. Pressing opens the microphone at once and buffers the
 * audio; a press shorter than HOLD_MS is a tap (with the space bar it types
 * a space) and nothing is recognized. Past HOLD_MS the buffered audio and
 * what follows stream to the server while the editor shows interim text.
 * Letting go waits for the final results and writes them in one change.
 */

const HOLD_MS = 300;
export const MAX_MS = 5 * 60_000;
const COUNTDOWN_MS = 30_000;

export type Phase = "idle" | "pressing" | "recording" | "finishing";
type By = "key" | "pointer";

export const useVoice = create<{ phase: Phase; /** Seconds left, shown near the limit. */ left: number | null }>(() => ({
  phase: "idle",
  left: null,
}));

type Run = {
  by: By;
  view: EditorView;
  pressedAt: number;
  recorder: Recorder;
  socket: AsrSocket | null;
  /** Audio captured before recognition started. */
  buffer: ArrayBuffer[];
  timers: ReturnType<typeof setTimeout>[];
};

let run: Run | null = null;

const setPhase = (phase: Phase) => useVoice.setState({ phase, left: null });

export function press(by: By) {
  if (run) return;
  const view = currentEditor();
  if (!view) return;
  const r: Run = { by, view, pressedAt: Date.now(), recorder: null!, socket: null, buffer: [], timers: [] };
  r.recorder = new Recorder((pcm) => (r.socket ? r.socket.send(pcm) : r.buffer.push(pcm)));
  r.recorder.ready.catch((err: unknown) => {
    if (run !== r) return;
    abort();
    notify(err instanceof Error ? err.message : "打不开麦克风");
  });
  r.timers.push(setTimeout(() => startRecognition(r), HOLD_MS));
  run = r;
  setPhase("pressing");
}

function startRecognition(r: Run) {
  if (run !== r) return;
  beginDictation(r.view);
  const socket = new AsrSocket((text) => showInterim(r.view, text));
  r.socket = socket;
  for (const pcm of r.buffer) socket.send(pcm);
  r.buffer = [];
  // A failure while the user is still speaking ends the recording.
  void socket.outcome.then((o) => {
    if (run === r && useVoice.getState().phase === "recording") settle(r, o);
  });
  r.timers.push(
    setTimeout(() => {
      if (run !== r) return;
      notify("已到 5 分钟上限，自动结束");
      void release();
    }, MAX_MS - (Date.now() - r.pressedAt)),
    setInterval(() => {
      const left = MAX_MS - (Date.now() - r.pressedAt);
      useVoice.setState({ left: left <= COUNTDOWN_MS ? Math.max(0, Math.ceil(left / 1000)) : null });
    }, 250),
  );
  setPhase("recording");
}

/** Lets go. by limits it to the input that pressed, so a stray keyup does not end a pointer press. */
export async function release(by?: By) {
  const r = run;
  if (!r || (by && r.by !== by)) return;
  const { phase } = useVoice.getState();
  if (phase === "pressing") {
    clear(r);
    run = null;
    r.recorder.cancel();
    setPhase("idle");
    if (r.by === "key" && r.view.hasFocus) {
      r.view.dispatch(r.view.state.replaceSelection(" "), { userEvent: "input.type", scrollIntoView: true });
    }
    return;
  }
  if (phase !== "recording" || !r.socket) return;
  clear(r);
  setPhase("finishing");
  await r.recorder.stop();
  settle(r, await r.socket.finish());
}

function settle(r: Run, o: AsrOutcome) {
  if (run !== r) return;
  clear(r);
  run = null;
  r.recorder.cancel();
  r.socket?.abort();
  setPhase("idle");
  if (!r.view.dom.isConnected) return;
  commitDictation(r.view, o.text);
  if (!o.ok) notify(o.message);
}

/** Drops the recording and whatever was heard. */
export function abort() {
  const r = run;
  if (!r) return;
  clear(r);
  run = null;
  r.recorder.cancel();
  r.socket?.abort();
  if (r.view.dom.isConnected) cancelDictation(r.view);
  setPhase("idle");
}

function clear(r: Run) {
  for (const t of r.timers) clearTimeout(t);
  r.timers = [];
}

// Leaving voice mode or the document ends the recording without a trace.
useWorkspace.subscribe((s, prev) => {
  if (s.mode !== prev.mode || s.currentId !== prev.currentId) abort();
});
