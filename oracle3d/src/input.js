// Keyboard, gamepad and touch input. Directions are camera-relative: "up" walks away
// from the camera whichever way it is turned, then gets snapped to the game's d-pad.
import { BTN } from './gb.js';

const KEYS = {
  ArrowUp: 'UP', KeyW: 'UP', ArrowDown: 'DOWN', KeyS: 'DOWN',
  ArrowLeft: 'LEFT', KeyA: 'LEFT', ArrowRight: 'RIGHT', KeyD: 'RIGHT',
  KeyX: 'A', KeyK: 'A', KeyZ: 'B', KeyJ: 'B',
  Enter: 'START', ShiftLeft: 'SELECT', ShiftRight: 'SELECT', Backspace: 'SELECT',
};

export class Input {
  constructor(root) {
    this.held = new Set();
    this.touchDir = [0, 0];
    this.touchButtons = new Set();
    this.onKey = null; // (code) => bool consumed, for app shortcuts

    addEventListener('keydown', (e) => {
      if (this.onKey && this.onKey(e.code)) { e.preventDefault(); return; }
      const k = KEYS[e.code];
      if (k) { this.held.add(k); e.preventDefault(); }
    });
    addEventListener('keyup', (e) => {
      const k = KEYS[e.code];
      if (k) this.held.delete(k);
    });
    addEventListener('blur', () => this.held.clear());

    this.setupTouch(root);
  }

  setupTouch(root) {
    const touch = root.querySelector('#touch');
    const isTouch = matchMedia('(pointer: coarse)').matches || 'ontouchstart' in window;
    if (isTouch) touch.hidden = false;

    const pad = touch.querySelector('#dpad');
    const stick = pad.querySelector('.stick');
    let padId = null;
    const move = (e) => {
      const r = pad.getBoundingClientRect();
      let dx = (e.clientX - (r.left + r.width / 2)) / (r.width / 2);
      let dy = (e.clientY - (r.top + r.height / 2)) / (r.height / 2);
      const l = Math.hypot(dx, dy);
      if (l > 1) { dx /= l; dy /= l; }
      this.touchDir = l < 0.25 ? [0, 0] : [dx, -dy];
      stick.style.transform = `translate(${dx * 40}px, ${dy * 40}px)`;
    };
    pad.addEventListener('pointerdown', (e) => { padId = e.pointerId; pad.setPointerCapture(padId); move(e); });
    pad.addEventListener('pointermove', (e) => { if (e.pointerId === padId) move(e); });
    const end = (e) => {
      if (e.pointerId !== padId) return;
      padId = null; this.touchDir = [0, 0]; stick.style.transform = '';
    };
    pad.addEventListener('pointerup', end);
    pad.addEventListener('pointercancel', end);

    for (const b of touch.querySelectorAll('button[data-btn]')) {
      const name = b.dataset.btn;
      b.addEventListener('pointerdown', (e) => { e.preventDefault(); this.touchButtons.add(name); b.setPointerCapture(e.pointerId); });
      const up = () => this.touchButtons.delete(name);
      b.addEventListener('pointerup', up);
      b.addEventListener('pointercancel', up);
    }
  }

  // Game Boy button bitmask. yaw: camera yaw in radians (0 = looking north).
  // relative: false while menus/2D screens are up, so the d-pad maps straight.
  buttons(yaw, relative) {
    let ix = 0, iy = 0;
    if (this.held.has('LEFT')) ix -= 1;
    if (this.held.has('RIGHT')) ix += 1;
    if (this.held.has('UP')) iy += 1;
    if (this.held.has('DOWN')) iy -= 1;
    ix += this.touchDir[0]; iy += this.touchDir[1];

    const pads = navigator.getGamepads ? navigator.getGamepads() : [];
    const extra = new Set();
    for (const gp of pads) {
      if (!gp) continue;
      const ax = gp.axes[0] || 0, ay = gp.axes[1] || 0;
      if (Math.hypot(ax, ay) > 0.3) { ix += ax; iy -= ay; }
      const pressed = (i) => gp.buttons[i] && gp.buttons[i].pressed;
      if (pressed(12)) iy += 1;
      if (pressed(13)) iy -= 1;
      if (pressed(14)) ix -= 1;
      if (pressed(15)) ix += 1;
      if (pressed(0)) extra.add('A');
      if (pressed(1) || pressed(2)) extra.add('B');
      if (pressed(9)) extra.add('START');
      if (pressed(8)) extra.add('SELECT');
    }

    let bits = 0;
    const len = Math.hypot(ix, iy);
    if (len > 0.3) {
      ix /= len; iy /= len;
      let wx = ix, wz = -iy; // world x/z (north is -z)
      if (relative) {
        const c = Math.cos(yaw), s = Math.sin(yaw);
        wx = ix * c - iy * s;
        wz = -ix * s - iy * c;
      }
      if (wx > 0.38) bits |= 1 << BTN.RIGHT;
      if (wx < -0.38) bits |= 1 << BTN.LEFT;
      if (wz < -0.38) bits |= 1 << BTN.UP;
      if (wz > 0.38) bits |= 1 << BTN.DOWN;
    }
    for (const n of ['A', 'B', 'START', 'SELECT']) {
      if (this.held.has(n) || this.touchButtons.has(n) || extra.has(n)) bits |= 1 << BTN[n];
    }
    return bits;
  }
}
