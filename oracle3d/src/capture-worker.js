// Web Worker: builds the 3D world from the player's ROM (see capture.js).
import { loadCoreModule } from './core.js';
import { captureWorld } from './capture.js';

self.onmessage = async ({ data: { rom, symbols } }) => {
  try {
    const wasmModule = await loadCoreModule();
    let last = 0;
    const result = captureWorld({
      wasmModule, rom, symbols,
      onProgress(done, total, message) {
        const now = performance.now();
        if (now - last > 200 || done === total) { last = now; self.postMessage({ type: 'progress', done, total, message }); }
      },
    });
    self.postMessage({ type: 'done', json: result.json, pool: result.pool }, [result.pool.data.buffer]);
  } catch (err) {
    self.postMessage({ type: 'error', message: String(err && err.message || err) });
  }
};
