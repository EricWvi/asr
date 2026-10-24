import workletUrl from "./pcm-worklet.ts?worker&url";

/*
 * Microphone capture as 16 kHz mono PCM16 in ~100 ms chunks. The microphone
 * is only open between start and stop, so the browser's recording
 * indicator shows only while the user is speaking. The AudioContext and its
 * worklet are kept for the next recording.
 */

let context: Promise<AudioContext> | null = null;

function audioContext() {
  context ??= (async () => {
    const ctx = new AudioContext();
    await ctx.audioWorklet.addModule(workletUrl);
    return ctx;
  })().catch((err: unknown) => {
    context = null;
    throw err;
  });
  return context;
}

/** A recording failure the user can act on, worded for them. */
export class MicError extends Error {}

function micError(err: unknown) {
  const name = err instanceof DOMException ? err.name : "";
  if (name === "NotAllowedError" || name === "SecurityError") return new MicError("没有麦克风权限，请在浏览器的网站设置里允许");
  if (name === "NotFoundError" || name === "OverconstrainedError") return new MicError("没有找到麦克风");
  if (name === "NotReadableError") return new MicError("麦克风被其他程序占用");
  return new MicError("打不开麦克风");
}

export class Recorder {
  private stream: MediaStream | null = null;
  private node: AudioWorkletNode | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private started: Promise<void>;
  private stopped = false;

  /** Opens the microphone; chunks go to onChunk until stop. Rejects with MicError. */
  constructor(onChunk: (pcm: ArrayBuffer) => void) {
    this.started = this.open(onChunk);
  }

  /** Resolves once audio is flowing, or rejects with MicError. */
  get ready() {
    return this.started;
  }

  private async open(onChunk: (pcm: ArrayBuffer) => void) {
    if (!navigator.mediaDevices?.getUserMedia) throw new MicError("当前页面不能使用麦克风（需要 HTTPS）");
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
    } catch (err) {
      throw micError(err);
    }
    // Let go before the permission prompt was answered.
    if (this.stopped) {
      stream.getTracks().forEach((t) => t.stop());
      return;
    }
    this.stream = stream;
    let ctx: AudioContext;
    try {
      ctx = await audioContext();
      await ctx.resume();
    } catch {
      this.release();
      throw new MicError("浏览器不支持录音");
    }
    if (this.stopped) {
      this.release();
      return;
    }
    this.node = new AudioWorkletNode(ctx, "pcm16k", { numberOfInputs: 1, numberOfOutputs: 0, channelCount: 1 });
    this.node.port.onmessage = (e: MessageEvent<ArrayBuffer | string>) => {
      if (e.data instanceof ArrayBuffer) onChunk(e.data);
    };
    this.source = ctx.createMediaStreamSource(stream);
    this.source.connect(this.node);
  }

  /** Stops recording after passing on the audio captured so far. */
  async stop() {
    this.stopped = true;
    try {
      await this.started;
    } catch {
      return;
    }
    const node = this.node;
    if (node) {
      this.source?.disconnect();
      await new Promise<void>((resolve) => {
        const prev = node.port.onmessage;
        node.port.onmessage = (e: MessageEvent) => {
          prev?.call(node.port, e);
          if (e.data === "flushed") resolve();
        };
        node.port.postMessage("flush");
        // The audio thread may be gone if the page is being torn down.
        setTimeout(resolve, 500);
      });
    }
    this.release();
  }

  /** Stops at once and drops what has not been passed on. */
  cancel() {
    this.stopped = true;
    this.started.then(() => this.release()).catch(() => {});
    this.release();
  }

  private release() {
    this.source?.disconnect();
    this.node?.port.close();
    this.stream?.getTracks().forEach((t) => t.stop());
    this.source = null;
    this.node = null;
    this.stream = null;
  }
}
