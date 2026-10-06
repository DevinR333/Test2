// Loads the WasmBoy core (embedded as base64 in the wasmboy package) as a compiled
// WebAssembly.Module, so the app and the capture worker can make as many emulator
// instances as they need.
import coreSource from 'wasmboy/dist/core/getWasmBoyWasmCore.esm.js?raw';

let modulePromise = null;

export function loadCoreModule() {
  if (!modulePromise) {
    const b64 = coreSource.match(/base64,([A-Za-z0-9+/=]+)/)[1];
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    modulePromise = WebAssembly.compile(bytes);
  }
  return modulePromise;
}

export const CORE_IMPORTS = {
  index: { consoleLog() {}, consoleLogTimeout() {} },
  env: { abort() { throw new Error('wasm abort'); } },
};
