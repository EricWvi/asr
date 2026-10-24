/*
 * One recognition over /api/asr (see internal/server/asr.go): audio goes out
 * as it is captured, results come back per sentence. Interim results repeat
 * the whole sentence and replace the previous one with the same ID.
 */

type ServerMessage =
  | { type: "partial" | "final"; sentence?: number; text?: string }
  | { type: "done" }
  | { type: "error"; message?: string };

export type AsrOutcome =
  /** All of it, sentences in order. */
  | { ok: true; text: string }
  /** The sentences finished before it failed, and what to tell the user. */
  | { ok: false; text: string; message: string };

// The server gives up 10 s after finish; this only covers a silent socket.
const FINISH_TIMEOUT = 15_000;

export class AsrSocket {
  private ws: WebSocket;
  private sentences = new Map<number, { text: string; final: boolean }>();
  /** Audio captured before the socket opened. */
  private pending: ArrayBuffer[] = [];
  private opened = false;
  private finishing = false;
  private settled = false;
  private resolve!: (o: AsrOutcome) => void;
  private finishTimer: ReturnType<typeof setTimeout> | undefined;
  readonly outcome: Promise<AsrOutcome>;

  /** onText gets everything heard so far, interim sentences included. */
  constructor(private onText: (text: string) => void) {
    this.outcome = new Promise((r) => (this.resolve = r));
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    this.ws = new WebSocket(`${proto}//${location.host}/api/asr`);
    this.ws.binaryType = "arraybuffer";
    this.ws.onopen = () => {
      this.opened = true;
      for (const chunk of this.pending) this.ws.send(chunk);
      this.pending = [];
      if (this.finishing) this.ws.send(JSON.stringify({ type: "finish" }));
    };
    this.ws.onmessage = (e) => {
      if (typeof e.data === "string") this.receive(JSON.parse(e.data) as ServerMessage);
    };
    this.ws.onclose = () => this.fail(this.opened ? "识别中断" : "连不上语音识别服务");
  }

  send(pcm: ArrayBuffer) {
    if (this.settled || this.finishing) return;
    if (this.ws.readyState === WebSocket.OPEN) this.ws.send(pcm);
    else if (this.ws.readyState === WebSocket.CONNECTING) this.pending.push(pcm);
  }

  /** No more audio: resolves outcome with the final results. */
  finish() {
    if (this.settled || this.finishing) return this.outcome;
    this.finishing = true;
    if (this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify({ type: "finish" }));
    this.finishTimer = setTimeout(() => this.fail("语音识别超时"), FINISH_TIMEOUT);
    return this.outcome;
  }

  /** Drops the recognition; outcome never resolves. */
  abort() {
    this.settled = true;
    clearTimeout(this.finishTimer);
    this.ws.close();
  }

  private receive(m: ServerMessage) {
    if (this.settled) return;
    switch (m.type) {
      case "partial":
      case "final":
        this.sentences.set(m.sentence ?? 0, { text: m.text ?? "", final: m.type === "final" });
        this.onText(this.text(false));
        break;
      case "done":
        this.settle({ ok: true, text: this.text(true) });
        break;
      case "error":
        this.fail(m.message || "语音识别失败");
        break;
    }
  }

  /** The sentences in order; finalOnly leaves out those still being heard. */
  private text(finalOnly: boolean) {
    return [...this.sentences.entries()]
      .sort(([a], [b]) => a - b)
      .filter(([, s]) => !finalOnly || s.final)
      .map(([, s]) => s.text)
      .join("");
  }

  private fail(message: string) {
    if (this.settled) return;
    const unfinished = [...this.sentences.values()].some((s) => !s.final && s.text);
    this.settle({ ok: false, text: this.text(true), message: unfinished ? "识别中断，最后一句没有识别完" : message });
  }

  private settle(o: AsrOutcome) {
    this.settled = true;
    clearTimeout(this.finishTimer);
    this.ws.onclose = null;
    this.ws.close();
    this.resolve(o);
  }
}
