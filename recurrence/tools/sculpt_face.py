"""Sculpts the Host's head at high resolution: an original, gaunt, dead face (SDF -> marching cubes),
plus separate eyeballs and wet hair ribbons. Head-local coordinates: origin = skull centre, +Z = face
forward, +Y up, metres. Outputs models/host_head.obj, host_eyes.obj, host_hair.obj."""
import numpy as np
from skimage import measure
from scipy.ndimage import gaussian_filter
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from sculpt_host import smin, capsule, ellipsoid, OUT

RES = float(sys.argv[1]) if len(sys.argv) > 1 else 0.0022
EYE_X, EYE_Y, EYE_Z, EYE_R = 0.031, 0.004, 0.061, 0.0118

def smax(a, b, k):
    return -smin(-a, -b, k)

def head_sdf(p):
    x, y, z = p[..., 0], p[..., 1], p[..., 2]
    ax = np.abs(x)
    pm = p.copy(); pm[..., 0] = ax  # mirrored for symmetric features
    # cranium: long, narrow skull
    d = ellipsoid(p, [0, 0.025, -0.012], [0.072, 0.098, 0.092])
    # facial mass
    d = smin(d, ellipsoid(p, [0, -0.015, 0.040], [0.062, 0.074, 0.062]), 0.03)
    # brow ridge
    d = smin(d, capsule(pm, [0.0, 0.026, 0.080], [0.050, 0.024, 0.066], 0.011, 0.008), 0.012)
    # cheekbones, sharp and high
    d = smin(d, ellipsoid(pm, [0.047, -0.014, 0.058], [0.019, 0.011, 0.020]), 0.012)
    # deep eye sockets
    d = smax(d, -ellipsoid(pm, [EYE_X, EYE_Y, 0.080], [0.020, 0.015, 0.018]), 0.008)
    # temples sunken
    d = smax(d, -ellipsoid(pm, [0.075, 0.02, 0.03], [0.012, 0.03, 0.03]), 0.015)
    # hollow cheeks under the cheekbones
    d = smax(d, -ellipsoid(pm, [0.052, -0.050, 0.064], [0.012, 0.018, 0.010]), 0.012)
    # nose: thin, long, slightly hooked bridge
    d = smin(d, capsule(p, [0, 0.012, 0.086], [0, -0.028, 0.104], 0.0065, 0.010), 0.008)
    d = smin(d, ellipsoid(pm, [0.009, -0.031, 0.096], [0.007, 0.006, 0.007]), 0.005)
    d = smax(d, -ellipsoid(pm, [0.006, -0.0355, 0.097], [0.0024, 0.0016, 0.003]), 0.002)
    # jaw: too long, hanging slack and slightly open
    d = smin(d, ellipsoid(p, [0, -0.082, 0.040], [0.040, 0.040, 0.046]), 0.02)
    d = smin(d, capsule(pm, [0.050, -0.040, 0.000], [0.022, -0.105, 0.050], 0.010, 0.012), 0.015)
    # mouth stretched into a smile while sobbing: corners pulled up and back, lips thin and drawn
    for sx in (-1, 1):
        d = smin(d, capsule(p, [0, -0.049, 0.091], [sx * 0.031, -0.044, 0.076], 0.0042, 0.0028), 0.005)
        d = smin(d, capsule(p, [0, -0.077, 0.087], [sx * 0.030, -0.049, 0.075], 0.0048, 0.0028), 0.005)
    # open mouth cavity (wide, crescent)
    mouth = smin(ellipsoid(p, [0, -0.062, 0.090], [0.024, 0.011, 0.032]),
                 ellipsoid(pm, [0.020, -0.052, 0.082], [0.010, 0.006, 0.02]), 0.006)
    d = smax(d, -mouth, 0.003)
    d = smin(d, ellipsoid(pm, [0.034, -0.030, 0.082], [0.016, 0.012, 0.012]), 0.010)
    # deep nasolabial folds from the strain of the grin
    d = smax(d, -capsule(pm, [0.014, -0.030, 0.096], [0.034, -0.060, 0.078], 0.0022, 0.0018), 0.003)
    # ears, small and flat
    d = smin(d, ellipsoid(pm, [0.070, 0.000, -0.008], [0.010, 0.026, 0.016]), 0.006)
    # neck (tendons standing out)
    d = smin(d, capsule(p, [0, -0.06, -0.02], [0, -0.20, -0.045], 0.040, 0.045), 0.03)
    d = smin(d, capsule(pm, [0.035, -0.065, 0.000], [0.012, -0.20, 0.010], 0.008, 0.009), 0.012)
    # eyelids: swollen lower lids that sag away from the eye
    d = smin(d, capsule(pm, [EYE_X - 0.016, EYE_Y - 0.0115, 0.0715], [EYE_X + 0.016, EYE_Y - 0.0095, 0.068], 0.0058, 0.0052), 0.005)
    d = smin(d, capsule(pm, [EYE_X - 0.016, EYE_Y + 0.0075, 0.0715], [EYE_X + 0.017, EYE_Y + 0.0045, 0.067], 0.0058, 0.005), 0.005)
    # carve the eyeball pocket so the eyeballs sit inside the lids
    d = smax(d, -(np.linalg.norm(pm - np.array([EYE_X, EYE_Y, EYE_Z], np.float32), axis=-1) - (EYE_R + 0.0012)), 0.002)
    return d

def write_obj(path, verts, normals, faces):
    with open(path, "w") as f:
        f.write(f"# {os.path.basename(path)} ({len(verts)} verts)\n")
        np.savetxt(f, verts, fmt="v %.5f %.5f %.5f")
        np.savetxt(f, normals, fmt="vn %.4f %.4f %.4f")
        fi = faces + 1
        np.savetxt(f, np.repeat(fi, 2, axis=1).reshape(-1, 6), fmt="f %d//%d %d//%d %d//%d")
    print("wrote", path, len(verts), "verts", flush=True)

def build_head():
    lo = np.array([-0.10, -0.215, -0.125]); hi = np.array([0.10, 0.135, 0.130])
    n = np.ceil((hi - lo) / RES).astype(int)
    xs = [lo[i] + RES * np.arange(n[i], dtype=np.float32) for i in range(3)]
    X, Y, Z = np.meshgrid(*xs, indexing="ij")
    vol = head_sdf(np.stack([X, Y, Z], -1)).astype(np.float32)
    rng = np.random.default_rng(11)
    wrinkle = gaussian_filter(rng.standard_normal(vol.shape).astype(np.float32), 0.004 / RES)
    pores = gaussian_filter(rng.standard_normal(vol.shape).astype(np.float32), 0.0012 / RES)
    vol += wrinkle / wrinkle.std() * 0.0004 + pores / pores.std() * 0.00025
    v, f, nrm, _ = measure.marching_cubes(vol, 0.0, spacing=(RES,) * 3, gradient_direction="ascent")
    v += lo
    write_obj(os.path.join(OUT, "host_head.obj"), v, -nrm, f[:, ::-1])  # skimage normals point inward

def sphere(c, r, seg=48):
    us = np.linspace(0, np.pi, seg // 2 + 1); vs = np.linspace(0, 2 * np.pi, seg + 1)
    U, V = np.meshgrid(us, vs, indexing="ij")
    nrm = np.stack([np.sin(U) * np.cos(V), np.cos(U), np.sin(U) * np.sin(V)], -1).reshape(-1, 3)
    verts = c + nrm * r
    W = seg + 1; faces = []
    for i in range(seg // 2):
        for j in range(seg):
            a = i * W + j; b = a + W
            faces += [[a, a + 1, b], [a + 1, b + 1, b]]
    return verts, nrm, np.array(faces)

def build_eyes():
    vs, ns, fs, off = [], [], [], 0
    for sx in (-1, 1):
        v, n, f = sphere(np.array([sx * EYE_X, EYE_Y, EYE_Z]), EYE_R)
        vs.append(v); ns.append(n); fs.append(f + off); off += len(v)
    write_obj(os.path.join(OUT, "host_eyes.obj"), np.concatenate(vs), np.concatenate(ns), np.concatenate(fs))

def build_hair():
    """Sparse, wet, clumped hair: ribbons that cling to the scalp then fall, some strands across the face."""
    rng = np.random.default_rng(5)
    verts, norms, faces = [], [], []
    def scalp_point(theta, phi):
        # point on the cranium ellipsoid (slightly outside)
        c = np.array([0, 0.025, -0.012]); r = np.array([0.075, 0.101, 0.095])
        return c + r * np.array([np.sin(phi) * np.sin(theta), np.cos(phi), np.sin(phi) * np.cos(theta)])
    n_clumps = 70
    for k in range(n_clumps):
        theta = rng.uniform(1.1, 2 * np.pi - 1.1) - np.pi * 0 if False else rng.choice([-1, 1]) * rng.uniform(1.0, np.pi)
        across_face = False
        if across_face:
            theta = rng.uniform(-0.5, 0.5)
        phi = rng.uniform(0.05, 0.55 if across_face else 0.9)
        strands = rng.integers(3, 7)
        for s in range(strands):
            th = theta + rng.normal(0, 0.05); ph = phi + rng.normal(0, 0.03)
            pts = []
            # follow the scalp downward to the hairline
            ph_end = 1.35 if not across_face else 1.0
            for t in np.linspace(ph, ph_end, 10):
                pts.append(scalp_point(th, t) * 1.0)
            # then fall with gravity, clinging to cheeks if across face
            p = pts[-1].copy()
            length = rng.uniform(0.15, 0.40) if not across_face else rng.uniform(0.10, 0.17)
            seg = 14
            for i in range(seg):
                if across_face:
                    p = p + np.array([rng.normal(0, 0.002), -length / seg, 0.0006])
                    p[2] = max(p[2], 0.085 + 0.002)  # hug the face
                else:
                    out = np.array([p[0], 0, p[2]]); out /= np.linalg.norm(out) + 1e-6
                    p = p + np.array([0, -length / seg, 0]) + out * 0.0015 + rng.normal(0, 0.0015, 3)
                pts.append(p.copy())
            pts = np.array(pts)
            w = rng.uniform(0.0016, 0.0035)
            base = len(verts)
            for i, q in enumerate(pts):
                tng = pts[min(i + 1, len(pts) - 1)] - pts[max(i - 1, 0)]
                tng /= np.linalg.norm(tng) + 1e-9
                outn = q - np.array([0, 0.0, 0.0]); outn /= np.linalg.norm(outn) + 1e-9
                side = np.cross(tng, outn); side /= np.linalg.norm(side) + 1e-9
                taper = w * (1.0 - 0.8 * i / len(pts))
                verts += [q - side * taper, q + side * taper]
                nn = np.cross(side, tng); nn /= np.linalg.norm(nn) + 1e-9
                norms += [nn, nn]
            for i in range(len(pts) - 1):
                a = base + 2 * i
                faces += [[a, a + 2, a + 1], [a + 1, a + 2, a + 3]]
    write_obj(os.path.join(OUT, "host_hair.obj"), np.array(verts), np.array(norms), np.array(faces))

def teeth_sdf(p):
    """Two rows of small, slightly crooked teeth following the arc of the grin."""
    x = p[..., 0]
    rng = np.random.default_rng(2)
    d = np.full(p.shape[:-1], 1e9, np.float32)
    for row_y, h in ((-0.0545, 0.0065), (-0.0700, -0.0055)):
        for i, tx in enumerate(np.linspace(-0.022, 0.022, 12)):
            tz = 0.0855 - 18.0 * tx * tx
            jit = rng.normal(0, 0.0007, 3)
            c = np.array([tx, row_y, tz]) + jit
            d = np.minimum(d, capsule(p, c, c + np.array([0, -h * 0.6, 0]), 0.0026, 0.0022))
    return d

def build_teeth():
    lo = np.array([-0.032, -0.082, 0.065]); hi = np.array([0.032, -0.042, 0.095]); r = 0.0006
    n = np.ceil((hi - lo) / r).astype(int)
    xs = [lo[i] + r * np.arange(n[i], dtype=np.float32) for i in range(3)]
    X, Y, Z = np.meshgrid(*xs, indexing="ij")
    vol = teeth_sdf(np.stack([X, Y, Z], -1)).astype(np.float32)
    v, f, nrm, _ = measure.marching_cubes(vol, 0.0, spacing=(r,) * 3, gradient_direction="ascent")
    write_obj(os.path.join(OUT, "host_teeth.obj"), v + lo, -nrm, f[:, ::-1])

if __name__ == "__main__":
    build_teeth()
    build_eyes()
    build_hair()
    build_head()
