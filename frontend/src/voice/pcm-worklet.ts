/*
 * Audio thread: turns the microphone's mono float samples, at whatever rate
 * the AudioContext runs, into 16 kHz PCM16 and posts 100 ms chunks (an
 * ArrayBuffer each) to the main thread. Each output sample is the average
 * of the input samples it covers, which also keeps aliasing down.
 *
 * Posting "flush" sends what is left as a short chunk, then "flushed".
 */

export {};

declare const sampleRate: number;
declare class AudioWorkletProcessor {
  readonly port: MessagePort;
}
declare function registerProcessor(name: string, ctor: new () => AudioWorkletProcessor): void;

const RATE = 16000;
const CHUNK = RATE / 10;

class PcmProcessor extends AudioWorkletProcessor {
  private ratio = sampleRate / RATE;
  private out = new Int16Array(CHUNK);
  private n = 0;
  // The input samples summed so far for the next output sample.
  private sum = 0;
  private count = 0;
  // How many input samples the next output sample still needs.
  private need = this.ratio;

  constructor() {
    super();
    this.port.onmessage = (e) => {
      if (e.data !== "flush") return;
      this.post();
      this.port.postMessage("flushed");
    };
  }

  process(inputs: Float32Array[][]) {
    const input = inputs[0]?.[0];
    if (!input) return true;
    for (const x of input) {
      // A sample straddling two outputs is shared between them by weight.
      let w = 1;
      while (w >= this.need) {
        this.sum += x * this.need;
        this.count += this.need;
        w -= this.need;
        this.emit();
      }
      this.sum += x * w;
      this.count += w;
      this.need -= w;
    }
    return true;
  }

  private emit() {
    const v = Math.max(-1, Math.min(1, this.sum / this.count));
    this.out[this.n++] = v < 0 ? v * 0x8000 : v * 0x7fff;
    this.sum = 0;
    this.count = 0;
    this.need = this.ratio;
    if (this.n === CHUNK) this.post();
  }

  private post() {
    if (this.n === 0) return;
    const chunk = this.out.slice(0, this.n).buffer;
    this.port.postMessage(chunk, [chunk]);
    this.n = 0;
  }
}

registerProcessor("pcm16k", PcmProcessor);
