// GPU-side world: the metatile pool, tileset tables, tile maps and the chunk meshes
// built from them by terrain.js.
import * as THREE from 'three';
import { TilesetModel, buildChunkHeights, meshChunk } from './terrain.js';
import { computeElevation } from './elevation.js';

export const EMPTY_TS = 0xffff;

function b64ToU8(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function loadImageData(url) {
  const img = new Image();
  img.src = url;
  await img.decode();
  const c = document.createElement('canvas');
  c.width = img.width; c.height = img.height;
  const g = c.getContext('2d', { willReadFrequently: true });
  g.drawImage(img, 0, 0);
  return g.getImageData(0, 0, img.width, img.height);
}

// Everything shared by all maps: pool texture, frame tables, per-tileset 3D models.
export class WorldData {
  // Prebuilt world files (from tools/capture-world.mjs), or null if there are none.
  static async fetchPrebuilt(base) {
    try {
      const r = await fetch(`${base}/world.json`);
      if (!r.ok || (r.headers.get('content-type') || '').includes('html')) return null;
      const json = await r.json();
      if (json.version !== 2) return null;
      const pool = await loadImageData(`${base}/pool.png`);
      return { json, pool: { width: pool.width, height: pool.height, data: pool.data } };
    } catch {
      return null;
    }
  }

  constructor(json, pool) {
    this.json = json;
    this.symbols = json.symbols;
    this.seasonTable = json.seasonTable;
    this.poolPixels = pool.data;
    this.poolW = pool.width;
    this.poolCols = json.poolCols;

    this.poolTex = new THREE.DataTexture(new Uint8Array(pool.data.buffer, pool.data.byteOffset, pool.data.length), pool.width, pool.height, THREE.RGBAFormat);
    nearest(this.poolTex);

    // Frame table: one row per animation frame, 256 pool indices (lo, hi bytes).
    const tilesets = json.tilesets;
    this.liveTs = tilesets.length; // reserved tileset index: the room the game is showing right now
    let rows = 0;
    const info = new Uint8Array(512 * 4);
    this.frameIdx = [];
    tilesets.forEach((ts, t) => {
      info[t * 4] = rows & 255; info[t * 4 + 1] = rows >> 8; info[t * 4 + 2] = ts.frames.length;
      rows += ts.frames.length;
      this.frameIdx.push(ts.frames.map((f) => new Uint16Array(b64ToU8(f).buffer)));
    });
    const table = new Uint8Array(256 * rows * 4);
    let r = 0;
    for (const frames of this.frameIdx) for (const idx of frames) {
      for (let id = 0; id < 256; id++) { table[(r * 256 + id) * 4] = idx[id] & 255; table[(r * 256 + id) * 4 + 1] = idx[id] >> 8; }
      r++;
    }
    this.framesTex = new THREE.DataTexture(table, 256, rows, THREE.RGBAFormat);
    nearest(this.framesTex);
    this.tsInfoTex = new THREE.DataTexture(info, 256, 2, THREE.RGBAFormat);
    nearest(this.tsInfoTex);

    // Live metatiles: 256 metatiles side by side, rewritten from VRAM while playing.
    this.livePixels = new Uint8Array(4096 * 16 * 4);
    this.liveTex = new THREE.DataTexture(this.livePixels, 4096, 16, THREE.RGBAFormat);
    nearest(this.liveTex);

    // 3D models (pixel classes) per tileset, built lazily.
    this.models = new Map();
    this.collisions = tilesets.map((ts) => b64ToU8(ts.collisions));
    this.collisionModes = tilesets.map((ts) => ts.collisionMode | 0);
    this.liveCollisionMode = 0;
  }

  // RGBA of one metatile of a tileset (frame 0).
  tilePixels(ts, id) {
    const p = this.frameIdx[ts][0][id];
    const ox = (p % this.poolCols) * 16, oy = Math.floor(p / this.poolCols) * 16;
    const out = new Uint8Array(1024);
    for (let y = 0; y < 16; y++) {
      const s = ((oy + y) * this.poolW + ox) * 4;
      out.set(this.poolPixels.subarray(s, s + 64), y * 64);
    }
    return out;
  }

  collisionMode(ts) {
    return ts === this.liveTs ? this.liveCollisionMode : (this.collisionModes[ts] ?? 255);
  }

  model(ts) {
    if (ts === EMPTY_TS) return null;
    let m = this.models.get(ts);
    if (!m) {
      m = new TilesetModel((id) => this.tilePixels(ts, id), this.collisions[ts]);
      this.models.set(ts, m);
    }
    return m;
  }

  // Replace the live tileset with what the game has loaded now.
  setLive(rgbaAtlas, collisions) {
    // rgbaAtlas: 256x256 atlas (16x16 grid). Re-lay it out as one 4096x16 strip.
    for (let id = 0; id < 256; id++) {
      const ox = (id & 15) * 16, oy = (id >> 4) * 16;
      for (let y = 0; y < 16; y++) {
        const s = ((oy + y) * 256 + ox) * 4;
        this.livePixels.set(rgbaAtlas.subarray(s, s + 64), (y * 4096 + id * 16) * 4);
      }
    }
    this.liveTex.needsUpdate = true;
    if (collisions) {
      const tile = (id) => {
        const out = new Uint8Array(1024);
        for (let y = 0; y < 16; y++) out.set(this.livePixels.subarray((y * 4096 + id * 16) * 4, (y * 4096 + id * 16 + 16) * 4), y * 64);
        return out;
      };
      this.models.set(this.liveTs, new TilesetModel(tile, collisions));
    }
  }
}

function nearest(t) {
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestFilter;
  t.generateMipmaps = false;
  t.flipY = false;
  t.needsUpdate = true;
}

// A tile map made of rooms (chunks), e.g. all of Holodrum, Subrosia or one interior room.
export class TileMap {
  constructor(data, roomsW, roomsH, roomW, roomH, originX = 0, originZ = 0) {
    this.data = data;
    this.roomsW = roomsW; this.roomsH = roomsH;
    this.roomW = roomW; this.roomH = roomH;
    this.mtW = roomsW * roomW; this.mtH = roomsH * roomH;
    this.originX = originX; this.originZ = originZ;
    this.ids = new Uint8Array(this.mtW * this.mtH);
    this.tilesets = new Uint16Array(this.mtW * this.mtH).fill(EMPTY_TS);
    this.texData = new Uint8Array(this.mtW * this.mtH * 4);
    this.tex = new THREE.DataTexture(this.texData, this.mtW, this.mtH, THREE.RGBAFormat);
    nearest(this.tex);
    this.group = new THREE.Group();
    this.chunks = new Map(); // room index -> { mesh, heights, dirty }
    this.material = makeTerrainMaterial(data, this);
    // terrain.js reads models through this proxy
    this.models = new Proxy({}, { get: (_, k) => data.model(Number(k)) });
  }

  // Write one room's metatiles. layout: Uint8Array(roomW*roomH); ts: tileset index.
  setRoom(rx, ry, layout, ts) {
    let changed = false;
    for (let y = 0; y < this.roomH; y++) for (let x = 0; x < this.roomW; x++) {
      const mi = (ry * this.roomH + y) * this.mtW + rx * this.roomW + x;
      const id = layout ? layout[y * this.roomW + x] : 0;
      const t = layout ? ts : EMPTY_TS;
      if (this.ids[mi] !== id || this.tilesets[mi] !== t) {
        this.ids[mi] = id; this.tilesets[mi] = t;
        this.texData[mi * 4] = id; this.texData[mi * 4 + 1] = t & 255; this.texData[mi * 4 + 2] = t >> 8;
        this.texData[mi * 4 + 3] = 255;
        changed = true;
      }
    }
    if (changed) {
      this.tex.needsUpdate = true;
      this.elevDirty = true;
      // This room and the ones above/below (solid runs reach across) need new meshes.
      for (const [dx, dy] of [[0, 0], [0, -1], [0, 1], [1, 0], [-1, 0]]) this.markDirty(rx + dx, ry + dy);
    }
    return changed;
  }

  markDirty(rx, ry) {
    const c = this.chunks.get(ry * this.roomsW + rx);
    if (c) c.dirty = true;
  }

  roomHasTiles(rx, ry) {
    if (rx < 0 || ry < 0 || rx >= this.roomsW || ry >= this.roomsH) return false;
    return this.tilesets[(ry * this.roomH) * this.mtW + rx * this.roomW] !== EMPTY_TS;
  }

  // Terrain height at a map pixel (x, z relative to this map's origin).
  heightAt(px, pz) {
    const rx = Math.floor(px / (this.roomW * 16)), ry = Math.floor(pz / (this.roomH * 16));
    const c = this.chunks.get(ry * this.roomsW + rx);
    if (!c || !c.heights) return 0;
    const lx = Math.floor(px) - rx * this.roomW * 16, lz = Math.floor(pz) - ry * this.roomH * 16;
    if (lx < 0 || lz < 0 || lx >= c.heights.W || lz >= c.heights.H) return 0;
    const i = lz * c.heights.W + lx;
    return Math.max(c.heights.h[i], c.heights.deck[i]);
  }

  // Build or refresh room meshes within `radius` rooms of (rx, ry); drop far ones.
  // At most `budget` meshes are built per call so a frame never stalls for long.
  update(rx, ry, radius, budget = 3) {
    if (this.elevDirty) this.refreshElevation();
    let built = 0;
    const order = [];
    for (let dy = -radius; dy <= radius; dy++) for (let dx = -radius; dx <= radius; dx++) order.push([dx, dy]);
    order.sort((a, b) => Math.hypot(a[0], a[1]) - Math.hypot(b[0], b[1]));
    for (const [dx, dy] of order) {
      const x = rx + dx, y = ry + dy;
      if (x < 0 || y < 0 || x >= this.roomsW || y >= this.roomsH) continue;
      const key = y * this.roomsW + x;
      const c = this.chunks.get(key);
      if (c && !c.dirty) continue;
      if (built >= budget) break;
      this.buildChunk(x, y);
      built++;
    }
    for (const [key, c] of this.chunks) {
      const x = key % this.roomsW, y = Math.floor(key / this.roomsW);
      if (Math.abs(x - rx) > radius + 1 || Math.abs(y - ry) > radius + 1) {
        if (c.mesh) { this.group.remove(c.mesh); c.mesh.geometry.dispose(); }
        this.chunks.delete(key);
      }
    }
    return built;
  }

  // Recompute ground levels for the whole map; rooms whose levels moved get rebuilt.
  refreshElevation() {
    this.elevDirty = false;
    const old = this.elev;
    this.elev = computeElevation(this, (ts) => this.data.collisionMode(ts));
    this._tmpHeights = null;
    if (!old) { for (const c of this.chunks.values()) c.dirty = true; return; }
    const { cw } = this.elev;
    const rw = this.roomW * 2, rh = this.roomH * 2;
    for (const [key, c] of this.chunks) {
      const x0 = (key % this.roomsW) * rw, y0 = Math.floor(key / this.roomsW) * rh;
      // include a margin of rows: runs reach across room edges
      outer: for (let y = Math.max(0, y0 - 12); y < Math.min(this.elev.ch, y0 + rh + 12); y++) {
        for (let x = x0; x < x0 + rw; x++) {
          const i = y * cw + x;
          if (old.level[i] !== this.elev.level[i] || old.kind[i] !== this.elev.kind[i]) { c.dirty = true; break outer; }
        }
      }
    }
  }

  buildChunk(rx, ry) {
    const key = ry * this.roomsW + rx;
    const old = this.chunks.get(key);
    if (old && old.mesh) { this.group.remove(old.mesh); old.mesh.geometry.dispose(); }
    const chunk = { dirty: false, mesh: null, heights: null };
    this.chunks.set(key, chunk);
    if (!this.roomHasTiles(rx, ry)) return;
    if (this.elevDirty || !this.elev) this.refreshElevation();
    const heights = buildChunkHeights(this, this.models, rx * this.roomW, ry * this.roomH, this.roomW, this.roomH, this.elev);
    chunk.heights = heights;
    const geo = meshChunk(heights, (x, z) => this.cellAtMapPx(x, z));
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(geo.position, 3));
    g.setAttribute('normal', new THREE.BufferAttribute(geo.normal, 3));
    g.setAttribute('src', new THREE.BufferAttribute(geo.src, 2));
    g.computeBoundingSphere();
    const mesh = new THREE.Mesh(g, this.material);
    mesh.position.set(this.originX, 0, this.originZ);
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.matrixAutoUpdate = false;
    mesh.updateMatrix();
    chunk.mesh = mesh;
    this.group.add(mesh);
  }

  // The cell at a pixel in map coordinates ({ c: chunk heights, i }), from whatever chunk covers
  // it (for faces at chunk edges). Rooms that are not built yet are computed on the fly.
  cellAtMapPx(x, z) {
    const rx = Math.floor(x / (this.roomW * 16)), ry = Math.floor(z / (this.roomH * 16));
    if (rx < 0 || ry < 0 || rx >= this.roomsW || ry >= this.roomsH || !this.roomHasTiles(rx, ry)) return null;
    const c = this.chunks.get(ry * this.roomsW + rx);
    let hs = c && c.heights && !c.dirty ? c.heights : null;
    if (!hs) {
      const key = `${rx},${ry}`;
      if (!this._tmpHeights || this._tmpHeights.key !== key) {
        this._tmpHeights = buildChunkHeights(this, this.models, rx * this.roomW, ry * this.roomH, this.roomW, this.roomH, this.elev);
        this._tmpHeights.key = key;
      }
      hs = this._tmpHeights;
    }
    return { c: hs, i: (z - ry * this.roomH * 16) * hs.W + (x - rx * this.roomW * 16) };
  }

  dispose() {
    for (const c of this.chunks.values()) if (c.mesh) c.mesh.geometry.dispose();
    this.chunks.clear();
    this.tex.dispose();
    this.material.dispose();
  }
}

// Lambert material whose colour comes from the tile map: each fragment knows which map
// pixel it shows (the `src` attribute) and fetches it from the metatile pool. Also
// gives every pixel a small bevel so surfaces read as stacked voxels.
function makeTerrainMaterial(data, map) {
  const mat = new THREE.MeshLambertMaterial({ color: 0xffffff });
  const uniforms = {
    uPool: { value: data.poolTex },
    uFrames: { value: data.framesTex },
    uTsInfo: { value: data.tsInfoTex },
    uLive: { value: data.liveTex },
    uMap: { value: map.tex },
    uMapSize: { value: new THREE.Vector2(map.mtW, map.mtH) },
    uLiveTs: { value: data.liveTs },
    uTime: { value: 0 },
    uPoolCols: { value: data.poolCols },
  };
  map.uniforms = uniforms;
  mat.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms);
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nattribute vec2 src;\nvarying vec2 vSrc;\nvarying vec3 vObjNormal;\nvarying vec3 vObjPos;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvSrc = src;\nvObjNormal = normal;\nvObjPos = position;');
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', `#include <common>
varying vec2 vSrc;
varying vec3 vObjNormal;
varying vec3 vObjPos;
uniform sampler2D uPool, uFrames, uTsInfo, uLive, uMap;
uniform vec2 uMapSize;
uniform int uLiveTs, uPoolCols;
uniform float uTime;
int b2(vec4 v) { return int(v.r * 255.0 + 0.5) + int(v.g * 255.0 + 0.5) * 256; }
vec3 mapColor(vec2 s) {
  ivec2 p = ivec2(floor(s));
  ivec2 mt = p / 16;
  if (p.x < 0 || p.y < 0 || mt.x >= int(uMapSize.x) || mt.y >= int(uMapSize.y)) return vec3(0.06, 0.25, 0.45);
  vec4 m = texelFetch(uMap, mt, 0);
  int id = int(m.r * 255.0 + 0.5);
  int ts = int(m.g * 255.0 + 0.5) + int(m.b * 255.0 + 0.5) * 256;
  ivec2 l = p - mt * 16;
  if (ts == 65535) return vec3(0.06, 0.25, 0.45);
  if (ts == uLiveTs) return texelFetch(uLive, ivec2(id * 16 + l.x, l.y), 0).rgb;
  vec4 info = texelFetch(uTsInfo, ivec2(ts - (ts / 256) * 256, ts / 256), 0);
  int row = int(info.r * 255.0 + 0.5) + int(info.g * 255.0 + 0.5) * 256;
  int cnt = max(1, int(info.b * 255.0 + 0.5));
  int f = int(uTime * 7.5) - (int(uTime * 7.5) / cnt) * cnt;
  int pi = b2(texelFetch(uFrames, ivec2(id, row + f), 0));
  int py = pi / uPoolCols;
  ivec2 pp = ivec2((pi - py * uPoolCols) * 16 + l.x, py * 16 + l.y);
  return texelFetch(uPool, pp, 0).rgb;
}`)
      .replace('#include <map_fragment>', `
vec3 tc = mapColor(vSrc);
tc = pow(tc, vec3(2.2));
// Voxel bevel: light the top-left edge of each pixel, shade the bottom-right one.
vec3 an = abs(vObjNormal);
vec2 f = an.y > 0.5 ? fract(vObjPos.xz) : (an.x > 0.5 ? vec2(fract(vObjPos.z), 1.0 - fract(vObjPos.y)) : vec2(fract(vObjPos.x), 1.0 - fract(vObjPos.y)));
float bevel = 1.0 + 0.10 * (1.0 - smoothstep(0.0, 0.16, min(f.x, f.y))) - 0.14 * (1.0 - smoothstep(0.0, 0.16, min(1.0 - f.x, 1.0 - f.y)));
// Sides of things are a little darker than tops, like ambient occlusion.
float side = an.y > 0.5 ? 1.0 : 0.82;
diffuseColor.rgb *= tc * bevel * side;`);
  };
  mat.customProgramCacheKey = () => 'terrain';
  return mat;
}
