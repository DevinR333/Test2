// Game sprites (OAM) as upright voxel figures standing in the 3D world.
//
// Each hardware sprite is matched to the game object it belongs to (Link, an enemy, an
// NPC...) by reading the object tables in WRAM. The object's position tells us where
// its feet are on the ground, so the sprite's pixels are stood up on that spot: pixel
// rows above the feet become height. Jumps (the object's z) come out naturally since
// the game already draws the sprite higher up.
import * as THREE from 'three';
import { rgb555 } from './gb.js';

const MAX_VOXELS = 12000;
const OAM = 0xfe00;

export class SpriteLayer {
  constructor() {
    const box = new THREE.BoxGeometry(1, 1, 1.6);
    const mat = new THREE.MeshLambertMaterial({ color: 0xffffff });
    this.mesh = new THREE.InstancedMesh(box, mat, MAX_VOXELS);
    this.mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    this.mesh.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(MAX_VOXELS * 3), 3);
    this.mesh.instanceColor.setUsage(THREE.DynamicDrawUsage);
    this.mesh.castShadow = true;
    this.mesh.receiveShadow = true;
    this.mesh.frustumCulled = false;
    this.mesh.count = 0;
    this.m4 = new THREE.Matrix4();
    this.col = new THREE.Color();
  }

  // gb: GB; view: { baseX, baseZ } world pixel of the room the camera values refer to,
  // camX/camY (hCamera), plus heightAt(x, z) for the ground under a world pixel and
  // yaw (radians) so flat cards turn to face the camera a bit.
  update(gb, view) {
    const m = gb.m;
    const lcdc = gb.io(0x40);
    const tall = (lcdc & 4) !== 0;
    const objects = readObjects(gb);
    const hi = 0xc800 + (OAM - 0xe000);
    let n = 0;
    const camX = view.camX, camY = view.camY;
    const pal = [];
    for (let p = 0; p < 8; p++) for (let c = 0; c < 4; c++) {
      const [r, g, b] = rgb555(gb.color(p, c, true));
      pal.push([Math.pow(r / 255, 2.2), Math.pow(g / 255, 2.2), Math.pow(b / 255, 2.2)]);
    }
    const sin = Math.sin(view.yaw), cos = Math.cos(view.yaw);

    for (let s = 0; s < 40 && n < MAX_VOXELS; s++) {
      const oy = m[hi + s * 4], ox = m[hi + s * 4 + 1];
      let tile = m[hi + s * 4 + 2];
      const at = m[hi + s * 4 + 3];
      const h = tall ? 16 : 8;
      if (oy === 0 || oy >= 160 || ox === 0 || ox >= 168) continue;
      if (tall) tile &= 0xfe;
      const sx = ox - 8, sy = oy - 16; // screen position
      // Room pixel of this sprite's top-left (the status bar takes the top 16 rows).
      const rx = sx + camX, ry = sy - 16 + camY;
      // Which object does it belong to? Nearest object whose drawn box overlaps.
      const cxs = rx + 4, cys = ry + h / 2;
      let best = null, bestD = 18 * 18;
      for (const o of objects) {
        const dx = o.x - cxs, dy = (o.y + o.z) - cys;
        const d = dx * dx + dy * dy;
        if (d < bestD) { bestD = d; best = o; }
      }
      // Feet: object y + 8 (objects are drawn centred on y). Unowned sprites stand on
      // their own bottom edge.
      const feetRow = best ? best.y + 8 : ry + h;
      const groundZ = best ? best.y : ry + h - 4;
      const anchorX = best ? best.x : rx + 4;
      const wz = view.baseZ + groundZ;
      const ground = view.heightAt(view.baseX + anchorX, wz);
      const vb = (at >> 3) & 1, p = at & 7;
      for (let y = 0; y < h; y++) {
        const ty = at & 0x40 ? h - 1 - y : y;
        const addr = (tile + (ty >> 3)) * 16;
        for (let x = 0; x < 8; x++) {
          const c = gb.tilePixel(vb, addr, at & 0x20 ? 7 - x : x, ty & 7);
          if (c === 0) continue;
          if (n >= MAX_VOXELS) break;
          const px = rx + x + 0.5;
          const elev = feetRow - (ry + y) - 0.5;
          // Stand the pixel up, rotated about the object's anchor to face the camera.
          const dx = px - anchorX;
          const wx = view.baseX + anchorX + dx * cos;
          const wzz = wz + 1 - dx * sin + s * 0.002;
          this.m4.makeRotationY(view.yaw);
          this.m4.setPosition(wx, ground + elev, wzz);
          this.mesh.setMatrixAt(n, this.m4);
          const [r, g, b] = pal[p * 4 + c];
          this.col.setRGB(r, g, b);
          this.mesh.setColorAt(n, this.col);
          n++;
        }
      }
    }
    this.mesh.count = n;
    this.mesh.instanceMatrix.needsUpdate = true;
    if (this.mesh.instanceColor) this.mesh.instanceColor.needsUpdate = true;
  }
}

// All enabled objects in WRAM bank 1: each $100 page holds a special object / item at
// $00, an interaction at $40, an enemy at $80 and a part at $c0. Position fields are at
// the same offsets in every struct (yh $0b, xh $0d, zh $0f).
export function readObjects(gb) {
  const out = [];
  for (let page = 0xd0; page <= 0xdf; page++) {
    for (const off of [0x00, 0x40, 0x80, 0xc0]) {
      const a = (page << 8) | off;
      if (!gb.rd(a, 1)) continue;
      const y = gb.rd(a + 0x0b, 1), x = gb.rd(a + 0x0d, 1);
      const z = (gb.rd(a + 0x0f, 1) << 24) >> 24;
      out.push({ addr: a, x, y, z });
    }
  }
  return out;
}
