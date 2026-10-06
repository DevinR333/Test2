#!/usr/bin/env node
// Builds the 3D world data ahead of time (the app can also do this itself on first
// launch). Writes dev-data/world/world.json and dev-data/world/pool.png, which the dev
// server picks up.
//
// Usage: node tools/capture-world.mjs <seasons.gbc> [seasons.sym] [outDir]
//   The .sym from the oracles-disasm build is only needed if you changed RAM layout.

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { PNG } from 'pngjs';
import { parseSym } from '../src/gb.js';
import { captureWorld, romTitle } from '../src/capture.js';
import { DEFAULT_SYMBOLS } from '../src/symbols.js';

const require = createRequire(import.meta.url);
const args = process.argv.slice(2);
const romPath = args.find((a) => /\.gbc?$/i.test(a));
const symPath = args.find((a) => /\.sym$/i.test(a));
const outDir = args.find((a) => a !== romPath && a !== symPath) || 'dev-data/world';
if (!romPath) {
  console.error('usage: node tools/capture-world.mjs <seasons.gbc> [seasons.sym] [outDir]');
  process.exit(1);
}

const rom = new Uint8Array(fs.readFileSync(romPath));
if (romTitle(rom) !== 'ZELDA DIN') {
  console.error('This does not look like an Oracle of Seasons (US) ROM.');
  process.exit(1);
}
const symbols = symPath ? parseSym(fs.readFileSync(symPath, 'utf8')) : DEFAULT_SYMBOLS;

const coreSrc = fs.readFileSync(require.resolve('wasmboy/dist/core/getWasmBoyWasmCore.cjs.js'), 'utf8');
const wasmModule = new WebAssembly.Module(Buffer.from(coreSrc.match(/base64,([A-Za-z0-9+/=]+)/)[1], 'base64'));

const t0 = Date.now();
let lastMsg = '';
const { json, pool } = captureWorld({
  wasmModule, rom, symbols,
  onProgress(done, total, msg) {
    if (msg !== lastMsg) { console.log(`[${((Date.now() - t0) / 1000).toFixed(0)}s] ${msg}`); lastMsg = msg; }
  },
});

fs.mkdirSync(outDir, { recursive: true });
const png = new PNG({ width: pool.width, height: pool.height });
png.data = Buffer.from(pool.data.buffer, pool.data.byteOffset, pool.data.length);
fs.writeFileSync(path.join(outDir, 'pool.png'), PNG.sync.write(png));
fs.writeFileSync(path.join(outDir, 'world.json'), JSON.stringify(json));
console.log(`wrote ${outDir}: ${json.tilesets.length} tilesets, ${json.poolSize} metatiles, ${((Date.now() - t0) / 1000).toFixed(0)}s`);
