import * as THREE from 'three';
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js';
import { ShaderPass } from 'three/examples/jsm/postprocessing/ShaderPass.js';
import { OutputPass } from 'three/examples/jsm/postprocessing/OutputPass.js';
import { WorldData, TileMap } from './world.js';
import { SpriteLayer } from './sprites.js';
import { createEmulator } from './emu.js';
import { Input } from './input.js';
import { TiltShiftShader } from './tiltshift.js';
import { romId, romTitle, WORLD_VERSION } from './capture.js';
import { DEFAULT_SYMBOLS } from './symbols.js';
import * as store from './store.js';

const WORLD_URL = 'world';
const ROOM_W = 160, ROOM_H = 128; // overworld room size in pixels

const $ = (s) => document.querySelector(s);
const statusEl = $('#status');

let data, emu, input;
const app = {};

async function boot(romBytes) {
  const world = await loadWorld(romBytes);
  data = new WorldData(world.json, world.pool);
  emu = await createEmulator(romBytes, data.symbols);
  store.set('rom', romBytes);
  $('#menu').classList.add('hidden');
  start();
}

// The 3D world comes from (in order): prebuilt files next to the app, the copy cached
// from an earlier launch, or a fresh capture from this ROM in a worker (about a minute).
async function loadWorld(rom) {
  const id = romId(rom);
  statusEl.textContent = 'Loading the world…';
  const pre = await WorldData.fetchPrebuilt(WORLD_URL);
  if (pre && (!pre.json.romId || pre.json.romId === id)) return pre;
  const cached = await store.get(`world:${id}`);
  if (cached && cached.json && cached.json.version === WORLD_VERSION) return cached;

  const bar = $('#progress');
  bar.hidden = false;
  const world = await new Promise((resolve, reject) => {
    const w = new Worker(new URL('./capture-worker.js', import.meta.url), { type: 'module' });
    w.onmessage = ({ data: msg }) => {
      if (msg.type === 'progress') {
        statusEl.textContent = `Building the 3D world from your ROM (first launch only): ${msg.message}…`;
        bar.value = msg.done / msg.total;
      } else if (msg.type === 'done') {
        w.terminate();
        resolve({ json: msg.json, pool: msg.pool });
      } else if (msg.type === 'error') {
        w.terminate();
        reject(new Error(msg.message));
      }
    };
    w.onerror = (e) => reject(new Error(e.message || 'capture worker failed'));
    w.postMessage({ rom, symbols: DEFAULT_SYMBOLS });
  });
  bar.hidden = true;
  await store.set(`world:${id}`, world);
  return world;
}

// ---------------------------------------------------------------- rendering setup
const canvas = $('#view');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
// Phones get a lighter setup: lower resolution scale and a smaller shadow map.
const MOBILE = matchMedia('(pointer: coarse)').matches || /Android|iPhone|iPad/i.test(navigator.userAgent);
renderer.setPixelRatio(Math.min(devicePixelRatio, MOBILE ? 1.5 : 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFShadowMap;
renderer.outputColorSpace = THREE.SRGBColorSpace;

const scene = new THREE.Scene();
const SKY = new THREE.Color(0x9fd3ff);
const INDOOR = new THREE.Color(0x07080a);
scene.background = SKY.clone();
scene.fog = new THREE.Fog(SKY.clone(), 700, 1500);

const camera = new THREE.PerspectiveCamera(34, 1, 4, 4000);
const hemi = new THREE.HemisphereLight(0xe8f4ff, 0x6a5a3a, 1.1);
scene.add(hemi);
const sun = new THREE.DirectionalLight(0xfff1d6, 2.9);
sun.castShadow = true;
sun.shadow.mapSize.set(MOBILE ? 2048 : 4096, MOBILE ? 2048 : 4096);
const SHADOW_R = 330;
Object.assign(sun.shadow.camera, { left: -SHADOW_R, right: SHADOW_R, top: SHADOW_R, bottom: -SHADOW_R, near: 1, far: 1600 });
sun.shadow.bias = -0.0006;
sun.shadow.normalBias = 0.6;
scene.add(sun, sun.target);
const SUN_DIR = new THREE.Vector3(-0.55, 1, 0.35).normalize();

const composer = new EffectComposer(renderer);
composer.addPass(new RenderPass(scene, camera));
const tiltH = new ShaderPass(TiltShiftShader);
const tiltV = new ShaderPass(TiltShiftShader);
tiltH.uniforms.dir.value.set(1, 0);
tiltV.uniforms.dir.value.set(0, 1);
composer.addPass(tiltH);
composer.addPass(tiltV);
composer.addPass(new OutputPass());

function resize() {
  const w = innerWidth, h = innerHeight;
  renderer.setSize(w, h, false);
  composer.setSize(w, h);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  for (const p of [tiltH, tiltV]) p.uniforms.resolution.value.set(w * renderer.getPixelRatio(), h * renderer.getPixelRatio());
}
addEventListener('resize', resize);
resize();

// ---------------------------------------------------------------- camera control
const cam = { yaw: 0, pitch: 0.92, dist: 300, target: new THREE.Vector3(), ready: false };
{
  const pointers = new Map();
  let pinch = 0;
  canvas.addEventListener('pointerdown', (e) => { pointers.set(e.pointerId, [e.clientX, e.clientY]); canvas.setPointerCapture(e.pointerId); });
  canvas.addEventListener('pointermove', (e) => {
    const p = pointers.get(e.pointerId);
    if (!p) return;
    if (pointers.size === 1) {
      cam.yaw -= (e.clientX - p[0]) * 0.006;
      cam.pitch = Math.min(1.45, Math.max(0.25, cam.pitch + (e.clientY - p[1]) * 0.004));
    }
    pointers.set(e.pointerId, [e.clientX, e.clientY]);
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      const d = Math.hypot(a[0] - b[0], a[1] - b[1]);
      if (pinch) cam.dist = Math.min(900, Math.max(90, cam.dist * pinch / d));
      pinch = d;
    }
  });
  const up = (e) => { pointers.delete(e.pointerId); pinch = 0; };
  canvas.addEventListener('pointerup', up);
  canvas.addEventListener('pointercancel', up);
  canvas.addEventListener('wheel', (e) => { cam.dist = Math.min(900, Math.max(90, cam.dist * Math.exp(e.deltaY * 0.001))); e.preventDefault(); }, { passive: false });
}

function placeCamera(dt) {
  const t = app.linkWorld;
  if (!cam.ready) { cam.target.copy(t); cam.ready = true; }
  cam.target.lerp(t, 1 - Math.exp(-dt * 8));
  const cp = Math.cos(cam.pitch), sp = Math.sin(cam.pitch);
  camera.position.set(
    cam.target.x + Math.sin(cam.yaw) * cp * cam.dist,
    cam.target.y + sp * cam.dist,
    cam.target.z + Math.cos(cam.yaw) * cp * cam.dist,
  );
  camera.lookAt(cam.target.x, cam.target.y + 8, cam.target.z);
  // Shadow camera follows the view, snapped to shadow-map texels to avoid shimmer.
  const texel = (SHADOW_R * 2) / sun.shadow.mapSize.x;
  const sx = Math.round(cam.target.x / texel) * texel, sz = Math.round(cam.target.z / texel) * texel;
  sun.target.position.set(sx, 0, sz);
  sun.position.set(sx + SUN_DIR.x * 600, SUN_DIR.y * 600, sz + SUN_DIR.z * 600);
  // Focus the tilt-shift band on Link.
  const v = t.clone().project(camera);
  const focus = Math.min(0.9, Math.max(0.1, v.y * 0.5 + 0.5));
  for (const p of [tiltH, tiltV]) p.uniforms.focus.value = focus;
}

// ---------------------------------------------------------------- game state -> world
const maps = {}; // 'g0', 'g1', 'interior'
let activeMap = null;
const sprites = new SpriteLayer();
scene.add(sprites.mesh);

function worldFor(group) {
  return data.json.worlds.find((w) => w.group === group);
}

function overworldMap(group) {
  const key = `g${group}`;
  if (!maps[key]) {
    const w = worldFor(group);
    maps[key] = new TileMap(data, w.width, w.height, 10, 8);
    maps[key].world = w;
    maps[key].roomSeason = new Int8Array(256).fill(-1);
  }
  return maps[key];
}

function interiorMap(w, h) {
  const m = maps.interior;
  if (m && m.roomW === w && m.roomH === h) return m;
  if (m) { scene.remove(m.group); m.dispose(); }
  maps.interior = new TileMap(data, 1, 1, w, h);
  return maps.interior;
}

function b64(s) { return Uint8Array.from(atob(s), (c) => c.charCodeAt(0)); }

// Which captured season each overworld room shows. Rooms in the same room pack as
// Link share his current season (the Rod of Seasons changes it); others use their
// pack's default season.
function seasonForRoom(map, room, livePack, liveSeason) {
  if (map.world.seasons.length === 1) return 0;
  const r = map.world.seasons[0][room];
  if (!r) return 0;
  if (r.pack === livePack) return liveSeason;
  const s = data.seasonTable[r.pack];
  return s === undefined ? 0 : s & 3;
}

function refreshSeasons(map, livePack, liveSeason, liveRoom) {
  for (let room = 0; room < 256; room++) {
    if (room === liveRoom) continue;
    const s = seasonForRoom(map, room, livePack, liveSeason);
    if (map.roomSeason[room] === s) continue;
    map.roomSeason[room] = s;
    const r = map.world.seasons[s][room];
    map.setRoom(room & 15, room >> 4, r ? b64(r.layout) : null, r ? r.tileset : 0);
  }
}

const DIR_DELTA = [-16, 1, 16, -1];
const live = { group: -1, room: -1, layout: null, collisions: new Uint8Array(256), atlasTimer: 0, pack: -1, season: -1 };

// Called after every emulated frame: keep the current room in sync with the game.
function syncFromGame(gb) {
  const group = gb.v('wActiveGroup');
  const room = gb.v('wActiveRoom');
  const isOverworld = !!worldFor(group);
  const layout = gb.roomLayout();
  const roomChanged = group !== live.group || room !== live.room;

  let map;
  if (isOverworld) {
    map = overworldMap(group);
    const pack = gb.v('wRoomPack'), season = gb.v('wRoomStateModifier');
    if (roomChanged || pack !== live.pack || season !== live.season) {
      // The room we just left goes back to its captured version.
      if (live.group === group && live.room >= 0 && live.room !== room) map.roomSeason[live.room] = -1;
      live.pack = pack; live.season = season;
      refreshSeasons(map, pack, group === 0 ? season : 0, room);
    }
  } else {
    map = interiorMap(layout.w, layout.h);
  }

  // Live tileset: refresh the pixels every few frames (animated tiles), the 3D model
  // only when the collision table changes.
  let collChanged = false;
  const coll = gb.s('w3TileCollisions');
  for (let i = 0; i < 256; i++) {
    const c = gb.rd(coll.addr + i, coll.bank);
    if (c !== live.collisions[i]) { live.collisions[i] = c; collChanged = true; }
  }
  if (roomChanged || collChanged || --live.atlasTimer <= 0) {
    const a = gb.metatileAtlas();
    data.liveCollisionMode = gb.v('wActiveCollisions');
    data.setLive(a.rgba, collChanged || roomChanged ? live.collisions : null);
    live.atlasTimer = 8;
    if (collChanged || roomChanged) {
      map.markDirty(isOverworld ? room & 15 : 0, isOverworld ? room >> 4 : 0);
      map.roomSeason && (map.roomSeason[room] = -2);
    }
  }

  const rx = isOverworld ? room & 15 : 0, ry = isOverworld ? room >> 4 : 0;
  map.setRoom(rx, ry, layout.tiles, data.liveTs);

  if (map !== activeMap) {
    if (activeMap) scene.remove(activeMap.group);
    activeMap = map;
    scene.add(map.group);
    cam.ready = false;
    // Outdoors: sky and distance fog. Indoors and dungeons: darkness around the room.
    const bg = isOverworld ? SKY : INDOOR;
    scene.background.copy(bg);
    scene.fog.color.copy(bg);
    scene.fog.near = isOverworld ? 700 : 900;
    scene.fog.far = isOverworld ? 1500 : 2500;
  }

  live.group = group; live.room = room;

  // Link and camera positions are relative to the "base" room: during a scrolling
  // transition the game has already switched rooms but still counts from the old one.
  let base = room;
  const transitioning = (gb.v('wScrollMode') & 8) !== 0;
  if (isOverworld && transitioning) base = room - DIR_DELTA[gb.v('wScreenTransitionDirection') & 3];
  const bx = isOverworld ? (base & 15) * ROOM_W : 0;
  const bz = isOverworld ? (base >> 4) * ROOM_H : 0;
  const link = gb.s('w1Link').addr;
  const lx = gb.rd(link + 0x0d, 1), ly = gb.rd(link + 0x0b, 1);
  app.base = { x: bx, z: bz };
  app.link = { x: bx + lx, z: bz + ly };
  app.transitioning = transitioning;
}

// ---------------------------------------------------------------- HUD / 2D screen
const hud = $('#hud').getContext('2d');
const hudImg = hud.createImageData(160, 16);
const pip = $('#pip');
const s2d = $('#screen2d').getContext('2d');
const s2dImg = s2d.createImageData(160, 144);
let showPip = false;

function drawScreens(gb) {
  const fb = gb.screen();
  const inGame = gb.v('w1Link') !== 0 && gb.v('wOpenedMenuType') === 0;
  const text = gb.v('wTextIsActive') !== 0;
  if (inGame) {
    for (let i = 0; i < 160 * 16; i++) {
      hudImg.data[i * 4] = fb[i * 3]; hudImg.data[i * 4 + 1] = fb[i * 3 + 1]; hudImg.data[i * 4 + 2] = fb[i * 3 + 2]; hudImg.data[i * 4 + 3] = 255;
    }
    hud.putImageData(hudImg, 0, 0);
  }
  $('#hud').style.display = inGame ? 'block' : 'none';
  const mode = !inGame ? 'big' : text ? 'text' : showPip ? 'small' : 'off';
  pip.style.display = mode === 'off' ? 'none' : 'block';
  pip.className = mode === 'big' ? 'big' : mode === 'text' ? 'text' : '';
  if (mode !== 'off') {
    for (let i = 0; i < 160 * 144; i++) {
      s2dImg.data[i * 4] = fb[i * 3]; s2dImg.data[i * 4 + 1] = fb[i * 3 + 1]; s2dImg.data[i * 4 + 2] = fb[i * 3 + 2]; s2dImg.data[i * 4 + 3] = 255;
    }
    s2d.putImageData(s2dImg, 0, 0);
  }
  return inGame && !text;
}

let toastTimer = 0;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 1600);
}

// ---------------------------------------------------------------- main loop
function start() {
  input = new Input($('#app'));
  let saved = null;
  input.onKey = (code) => {
    if (code === 'Tab') { showPip = !showPip; return true; }
    if (code === 'KeyC') { cam.yaw = 0; cam.pitch = 0.92; cam.dist = 300; return true; }
    if (code === 'F5') { saved = emu.saveState(); toast('State saved'); return true; }
    if (code === 'F9') { if (saved) { emu.loadState(saved); toast('State loaded'); } return true; }
    return false;
  };
  const unlockAudio = () => { emu.startAudio(); removeEventListener('pointerdown', unlockAudio); removeEventListener('keydown', unlockAudio); };
  addEventListener('pointerdown', unlockAudio);
  addEventListener('keydown', unlockAudio);

  app.linkWorld = new THREE.Vector3();
  app.link = { x: 0, z: 0 };
  app.base = { x: 0, z: 0 };
  let last = performance.now();
  let playing3d = false;
  window.__oracle3d = {
    emu, data, maps, cam, app, scene, camera, renderer, warp,
    // Test helper: run n frames as fast as possible; buttons(frameIndex) -> bitmask.
    run(n, buttons = () => 0) {
      for (let i = 0; i < n; i++) { emu.gb.frame(buttons(i)); syncFromGame(emu.gb); }
    },
  };

  const loop = (now) => {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    emu.buttons = input.buttons(cam.yaw, playing3d);
    // Screen-edge scrolling is fast-forwarded so walking between rooms stays fluid.
    emu.update(dt, () => (emu.gb.v('wScrollMode') & 8 ? 6 : 1), syncFromGame);
    playing3d = drawScreens(emu.gb);

    if (activeMap) {
      const ground = activeMap.heightAt(app.link.x, app.link.z);
      app.linkWorld.set(app.link.x, Math.max(0, ground), app.link.z);
      const rx = Math.floor(app.link.x / (activeMap.roomW * 16)), ry = Math.floor(app.link.z / (activeMap.roomH * 16));
      activeMap.update(rx, ry, activeMap.roomsW > 1 ? 2 : 0, 2);
      if (activeMap.uniforms) activeMap.uniforms.uTime.value = now / 1000;
      placeCamera(dt);
      sprites.update(emu.gb, {
        baseX: app.base.x, baseZ: app.base.z,
        camX: emu.gb.v('hCameraX') | (emu.gb.v('hCameraX', 1) << 8),
        camY: emu.gb.v('hCameraY') | (emu.gb.v('hCameraY', 1) << 8),
        heightAt: (x, z) => Math.max(0, activeMap.heightAt(x, z)),
        yaw: cam.yaw,
      });
    }
    composer.render();
    requestAnimationFrame(loop);
  };
  requestAnimationFrame(loop);
}

// Debug/test helper: warp Link to a room, as the gale seed menu does.
function warp(group, room, pos = 0x55) {
  const gb = emu.gb;
  gb.setv('wWarpDestGroup', 0x80 | group);
  gb.setv('wWarpDestRoom', room);
  gb.setv('wWarpTransition', 0);
  gb.setv('wWarpDestPos', pos);
  gb.setv('wWarpTransition2', 1);
}

// ---------------------------------------------------------------- ROM picking
async function tryBoot(bytes) {
  const title = romTitle(bytes);
  if (title !== 'ZELDA DIN') { statusEl.textContent = `That ROM is "${title}", not Oracle of Seasons (US).`; return; }
  try {
    await boot(bytes);
  } catch (err) {
    statusEl.textContent = String(err.message || err);
    console.error(err);
  }
}

$('#romfile').addEventListener('change', async (e) => {
  const f = e.target.files[0];
  if (f) await tryBoot(new Uint8Array(await f.arrayBuffer()));
});

// Start straight away with the ROM from last time, or (for development) one placed at
// dev-data/seasons.gbc (served by the dev server only).
(async () => {
  let bytes = await store.get('rom');
  if (!bytes) {
    try {
      const r = await fetch('seasons.gbc');
      if (r.ok && !(r.headers.get('content-type') || '').includes('html')) bytes = new Uint8Array(await r.arrayBuffer());
    } catch { /* pick it by hand */ }
  }
  if (bytes && bytes.length >= 0x100000) await tryBoot(new Uint8Array(bytes));
})();
