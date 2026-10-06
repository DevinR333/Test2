// Tilt-shift depth of field: a one-direction blur whose strength grows with distance
// from a horizontal focus band. Run twice (horizontal, then vertical).
import * as THREE from 'three';

export const TiltShiftShader = {
  uniforms: {
    tDiffuse: { value: null },
    resolution: { value: new THREE.Vector2(1, 1) },
    dir: { value: new THREE.Vector2(1, 0) },
    focus: { value: 0.5 },
    band: { value: 0.16 },
    amount: { value: 7.0 },
  },
  vertexShader: /* glsl */`
    varying vec2 vUv;
    void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
  fragmentShader: /* glsl */`
    uniform sampler2D tDiffuse;
    uniform vec2 resolution, dir;
    uniform float focus, band, amount;
    varying vec2 vUv;
    void main() {
      float d = max(0.0, abs(vUv.y - focus) - band);
      float r = amount * smoothstep(0.0, 0.45, d) * (resolution.y / 900.0);
      vec2 step = dir / resolution * r / 4.0;
      vec4 sum = vec4(0.0);
      float wsum = 0.0;
      for (int i = -4; i <= 4; i++) {
        float w = exp(-float(i * i) / 8.0);
        sum += texture2D(tDiffuse, vUv + step * float(i)) * w;
        wsum += w;
      }
      gl_FragColor = sum / wsum;
    }`,
};
