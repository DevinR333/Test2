"""Procedurally sculpts 'the Host' (an original creature) as SDFs -> marching cubes -> OBJ.

Outputs models/host_body.obj (skin) and models/host_veil.obj (lace curtain draped over head/shoulders).
Units: metres, Y up, creature faces -Z... no: faces +Z (toward the viewer at +Z).
"""
import numpy as np
from skimage import measure
from scipy.ndimage import gaussian_filter
import os, sys

OUT = os.path.join(os.path.dirname(__file__), "..", "models")
RES = float(sys.argv[1]) if len(sys.argv) > 1 else 0.008  # voxel size (m)

def smin(a, b, k):
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0.0, 1.0)
    return b * (1 - h) + a * h - k * h * (1 - h)

def capsule(p, a, b, ra, rb):
    a = np.asarray(a, np.float32); b = np.asarray(b, np.float32)
    pa = p - a; ba = b - a
    h = np.clip((pa @ ba) / (ba @ ba), 0, 1)
    r = ra + (rb - ra) * h
    return np.linalg.norm(pa - h[..., None] * ba, axis=-1) - r

def ellipsoid(p, c, r):
    q = (p - np.asarray(c, np.float32)) / np.asarray(r, np.float32)
    k0 = np.linalg.norm(q, axis=-1)
    k1 = np.linalg.norm(q / np.asarray(r, np.float32), axis=-1)
    return k0 * (k0 - 1.0) / np.maximum(k1, 1e-6)

def rot(p, center, axis, ang):
    """rotate points p about center by -ang (to place a rotated primitive)."""
    c = np.asarray(center, np.float32); axis = np.asarray(axis, np.float32); axis /= np.linalg.norm(axis)
    q = p - c
    cos, sin = np.cos(-ang), np.sin(-ang)
    return c + q * cos + np.cross(axis, q) * sin + axis * (q @ axis)[..., None] * (1 - cos)

rng = np.random.default_rng(7)
def value_noise_field(shape, scale_vox, seed):
    g = np.random.default_rng(seed).standard_normal(shape).astype(np.float32)
    g = gaussian_filter(g, scale_vox)
    return g / (g.std() + 1e-6)

# ---- skeleton (metres). Creature stands ~2.45 m, stooped, head lolled to its left.
H_NECK = np.array([0.04, 2.02, 0.10])
HEAD_C = np.array([0.20, 2.08, 0.20])   # head hangs sideways & forward
def body_sdf(p):
    d = 1e9
    # pelvis / abdomen: narrow, sunken
    d = ellipsoid(p, [0, 1.12, 0], [0.15, 0.12, 0.10])
    # spine column (stooped forward toward top)
    spine = [[0, 1.12, 0.0], [0, 1.38, -0.03], [0.0, 1.62, 0.0], [0.02, 1.86, 0.07], H_NECK]
    for a, b in zip(spine[:-1], spine[1:]):
        d = smin(d, capsule(p, a, b, 0.085, 0.08), 0.06)
    # emaciated ribcage: ellipsoid minus rib grooves
    rib = ellipsoid(p, [0.0, 1.66, 0.02], [0.17, 0.24, 0.12])
    yy = p[..., 1]
    grooves = 0.006 * np.clip(np.sin((yy - 1.45) * 2 * np.pi / 0.055), 0, 1) * ((yy > 1.48) & (yy < 1.86))
    rib = rib + grooves
    d = smin(d, rib, 0.05)
    # sunken belly (carve)
    belly = ellipsoid(p, [0, 1.32, 0.13], [0.11, 0.09, 0.05])
    d = np.maximum(d, -(belly - 0.012))
    # clavicles / shoulders: high, bony, wide
    shL = np.array([-0.21, 1.90, 0.05]); shR = np.array([0.22, 1.86, 0.06])
    d = smin(d, capsule(p, [0.0, 1.92, 0.08], shL, 0.035, 0.05), 0.04)
    d = smin(d, capsule(p, [0.0, 1.92, 0.08], shR, 0.035, 0.05), 0.04)
    # neck: too long, bent hard to the side
    d = smin(d, capsule(p, [0.0, 1.90, 0.07], H_NECK, 0.05, 0.045), 0.04)
    d = smin(d, capsule(p, H_NECK, HEAD_C + np.array([-0.06, -0.02, -0.03]), 0.045, 0.05), 0.04)
    # arms: far too long, hanging to the knees; extra-long forearms, one reaching forward
    def arm(sh, el, wr, hand_dir, curl):
        nonlocal_d = capsule(p, sh, el, 0.045, 0.032)
        nonlocal_d = smin(nonlocal_d, ellipsoid(p, el, [0.035, 0.035, 0.035]), 0.02)
        nonlocal_d = smin(nonlocal_d, capsule(p, el, wr, 0.03, 0.022), 0.02)
        # hand: bony palm + 5 very long fingers with knuckle bulges
        hd = np.asarray(hand_dir, np.float32); hd /= np.linalg.norm(hd)
        side = np.cross(hd, [0, 0, 1.0]).astype(np.float32); side /= np.linalg.norm(side) + 1e-6
        palm_end = wr + hd * 0.08
        hand = capsule(p, wr, palm_end, 0.03, 0.035)
        for i, off in enumerate([-0.03, -0.012, 0.006, 0.022, 0.034]):
            base = palm_end + side * off
            length = [0.20, 0.25, 0.27, 0.24, 0.16][i]
            seg = length / 3
            pts = [base]
            dirv = hd.copy()
            for s in range(3):
                dirv = dirv + np.array([0, 0, curl]) * (s + 1) * 0.6
                dirv /= np.linalg.norm(dirv)
                pts.append(pts[-1] + dirv * seg)
            for s in range(3):
                hand = smin(hand, capsule(p, pts[s], pts[s + 1], 0.011 - s * 0.002, 0.009 - s * 0.002), 0.006)
                hand = smin(hand, ellipsoid(p, pts[s + 1], [0.012, 0.012, 0.012]), 0.004)
        return smin(nonlocal_d, hand, 0.015)
    # left arm hangs limp to below the knee
    d = smin(d, arm(shL, np.array([-0.27, 1.45, 0.02]), np.array([-0.28, 0.98, 0.06]), [-0.05, -1, 0.1], 0.15), 0.03)
    # right arm reaches toward the viewer
    d = smin(d, arm(shR, np.array([0.33, 1.52, 0.16]), np.array([0.30, 1.30, 0.55]), [-0.05, 0.05, 1], -0.35), 0.03)
    # legs: thin, knees slightly bent, mostly hidden by the curtain
    for sx in (-0.09, 0.09):
        hip = [sx, 1.05, 0.0]; knee = [sx * 1.1, 0.56, 0.08]; ank = [sx * 1.15, 0.08, 0.0]
        d = smin(d, capsule(p, hip, knee, 0.07, 0.045), 0.04)
        d = smin(d, ellipsoid(p, knee, [0.05, 0.05, 0.05]), 0.02)
        d = smin(d, capsule(p, knee, ank, 0.04, 0.028), 0.02)
        d = smin(d, capsule(p, ank, [sx * 1.15, 0.03, 0.17], 0.03, 0.022), 0.02)
    return d

def veil_sdf(p, body):
    """Wet lace clinging to the skull and shoulders (so the face pushes through it),
    plus torn strips hanging down the back."""
    y = p[..., 1]
    x = p[..., 0]; z = p[..., 2]
    # ragged hem height around the shoulders
    ang = np.arctan2(z - 0.05, x - 0.05)
    hem = 1.80 + 0.05 * np.sin(ang * 5.0 + 0.7) + 0.03 * np.sin(ang * 13.0)
    hood = np.abs(body - 0.016) - 0.0035
    hood = np.maximum(hood, hem - y)
    # torn strips down the back: thin shell behind the body, cut into ragged bands
    back_z = -0.13 - 0.05 * np.clip((1.9 - y) / 1.2, 0, 1) + 0.025 * np.sin(x * 40.0)
    back = np.abs(z - back_z) - 0.0035
    strip = np.sin(x * 26.0 + 1.0)
    bottom = 0.95 + 0.35 * (0.5 + 0.5 * np.sin(x * 11.0 + 2.0))
    back = np.maximum(back, np.where(strip > -0.3, -1.0, 1.0) * 0.0 + (strip < -0.3) * 0.05)
    back = np.maximum(back, bottom - y)
    back = np.maximum(back, y - 1.92)
    back = np.maximum(back, np.abs(x) - 0.26)
    shell = np.minimum(hood, back)
    return shell

def build(name, fn_kind):
    lo = np.array([-0.62, -0.02, -0.55]); hi = np.array([0.72, 2.40, 1.05])
    n = np.ceil((hi - lo) / RES).astype(int)
    print(name, "grid", n, n.prod() / 1e6, "M", flush=True)
    xs = [lo[i] + RES * np.arange(n[i], dtype=np.float32) for i in range(3)]
    vol = np.empty(n, np.float32)
    # evaluate in Y slabs to save memory
    bodyvol = np.empty(n, np.float32) if fn_kind == "veil" else None
    X, Z = np.meshgrid(xs[0], xs[2], indexing="ij")
    for j, yv in enumerate(xs[1]):
        P = np.stack([X, np.full_like(X, yv), Z], -1)
        b = body_sdf(P)
        vol[:, j, :] = b if fn_kind == "body" else veil_sdf(P, b)
    if fn_kind == "body":
        # organic surface: sinew/vein ridges + skin wrinkle noise, applied as SDF displacement
        n1 = value_noise_field(vol.shape, 0.035 / RES, 3) * 0.004
        n2 = value_noise_field(vol.shape, 0.010 / RES, 4) * 0.0012
        vol = vol + n1 + n2
    else:
        n1 = value_noise_field(vol.shape, 0.03 / RES, 5) * 0.004
        vol = vol + n1
    verts, faces, normals, _ = measure.marching_cubes(vol, 0.0, spacing=(RES, RES, RES), gradient_direction="ascent")
    verts += lo
    # marching_cubes 'ascent' normals point into increasing SDF = outward
    path = os.path.join(OUT, f"host_{name}.obj")
    with open(path, "w") as f:
        f.write(f"# the Host - procedurally sculpted ({len(verts)} verts)\n")
        np.savetxt(f, verts, fmt="v %.5f %.5f %.5f")
        np.savetxt(f, -normals, fmt="vn %.4f %.4f %.4f")  # skimage "ascent" normals point inward for an SDF
        fi = faces[:, ::-1] + 1  # flip winding for Godot (CCW front-faces outward)
        np.savetxt(f, np.repeat(fi, 2, axis=1).reshape(-1, 6), fmt="f %d//%d %d//%d %d//%d")
    print("wrote", path, len(verts), "verts", len(faces), "tris", flush=True)

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    build("body", "body")
