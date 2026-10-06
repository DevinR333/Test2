// Runs the game in the browser: WasmBoy core, fixed 59.73 Hz timing, audio, save states.
import { loadCoreModule, CORE_IMPORTS } from './core.js';
import { GB } from './gb.js';

const FPS = 4194304 / 70224;

export async function createEmulator(rom, symbols) {
  const instance = new WebAssembly.Instance(await loadCoreModule(), CORE_IMPORTS);
  const gb = new GB(instance.exports, symbols);
  gb.loadRom(rom);
  const saveKey = 'oracle3d-sram';
  try {
    const saved = localStorage.getItem(saveKey);
    if (saved) {
      const bytes = Uint8Array.from(atob(saved), (c) => c.charCodeAt(0));
      gb.m.set(bytes, instance.exports.CARTRIDGE_RAM_LOCATION.valueOf());
    }
  } catch { /* no storage: play without a battery save */ }
  return new Emulator(gb, saveKey);
}

class Emulator {
  constructor(gb, saveKey) {
    this.gb = gb;
    this.saveKey = saveKey;
    this.buttons = 0;
    this.acc = 0;
    this.audio = null;
    this.lastSramSave = 0;
  }

  // Advance emulation by `dt` seconds of real time. `speed` > 1 fast-forwards (used to
  // squash the game's screen-scroll so walking between rooms feels continuous).
  // Calls onFrame after each emulated frame. Returns the number of frames run.
  update(dt, speedFn, onFrame) {
    this.acc += Math.min(dt, 0.1) * FPS;
    let ran = 0;
    while (this.acc >= 1) {
      const speed = speedFn();
      for (let i = 0; i < speed; i++) {
        this.gb.frame(this.buttons);
        this.pumpAudio(speed > 1);
        onFrame(this.gb);
        ran++;
      }
      this.acc -= 1;
    }
    const now = performance.now();
    if (now - this.lastSramSave > 5000) { this.lastSramSave = now; this.saveSram(); }
    return ran;
  }

  saveSram() {
    const e = this.gb.e;
    const base = e.CARTRIDGE_RAM_LOCATION.valueOf();
    const ram = this.gb.m.subarray(base, base + 0x2000);
    let s = '';
    for (let i = 0; i < ram.length; i++) s += String.fromCharCode(ram[i]);
    try { localStorage.setItem(this.saveKey, btoa(s)); } catch { /* storage full or blocked */ }
  }

  saveState() {
    this.gb.e.saveState();
    return new Uint8Array(this.gb.m.subarray(0, 0x145680)); // everything except the ROM
  }

  loadState(state) {
    this.gb.m.set(state, 0);
    this.gb.e.loadState();
  }

  // ---- audio: WasmBoy accumulates stereo samples as bytes (0..254, 127 = silence) ----
  startAudio() {
    if (this.audio) return;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    const ctx = new AC({ sampleRate: 44100 });
    this.audio = { ctx, next: 0 };
  }

  pumpAudio(fastForward) {
    const e = this.gb.e;
    const n = e.getNumberOfSamplesInAudioBuffer();
    if (!this.audio || fastForward || n < 1) {
      if (n > 0) e.clearAudioBuffer();
      return;
    }
    if (n < 1024) return; // batch into ~23 ms buffers
    const { ctx } = this.audio;
    const base = e.AUDIO_BUFFER_LOCATION.valueOf();
    const buf = ctx.createBuffer(2, n, 44100);
    const l = buf.getChannelData(0), r = buf.getChannelData(1);
    const m = this.gb.m;
    for (let i = 0; i < n; i++) {
      l[i] = (m[base + i * 2] - 127) / 128;
      r[i] = (m[base + i * 2 + 1] - 127) / 128;
    }
    e.clearAudioBuffer();
    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(ctx.destination);
    const now = ctx.currentTime;
    if (this.audio.next < now + 0.02 || this.audio.next > now + 0.25) this.audio.next = now + 0.05;
    src.start(this.audio.next);
    this.audio.next += n / 44100;
  }
}
