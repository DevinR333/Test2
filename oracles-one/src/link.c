#include "link.h"
#include <stdlib.h>
#include <string.h>

#define WALK_SPEED 1.0f          // pixels per frame, the originals' walking speed
#define NUDGE_RANGE 6            // how far off a gap Link may be and still slide into it

// Link touches walls at eight points around his feet (object_code/common/specialObjects/link.s,
// calculateAdjacentWallsBitset): two above at y-3, two below at y+7 (x-3 and x+2), two each side at
// x-5 and x+4 (y and y+5). A step is refused when the points on that side are in a wall now, which is
// the same as asking whether the step's new position has them at one pixel less.

bool (*link_blocker)(float x, float y);
float link_speed = 1.0f;

static bool solid_at(const World *w, float x, float y) { return world_solid(w, (int)SDL_floorf(x), (int)SDL_floorf(y)); }

// Whether Link may step to (x, y) going (dx, dy) (one axis).
static bool step_blocked(const World *w, float x, float y, float dx, float dy) {
  if (link_blocker && link_blocker(x, y + 2)) return true;
  if (dy < 0) return solid_at(w, x - 3, y - 2) || solid_at(w, x + 2, y - 2);
  if (dy > 0) return solid_at(w, x - 3, y + 6) || solid_at(w, x + 2, y + 6);
  if (dx < 0) return solid_at(w, x - 4, y) || solid_at(w, x - 4, y + 5);
  if (dx > 0) return solid_at(w, x + 3, y) || solid_at(w, x + 3, y + 5);
  return false;
}

// Whether Link standing at (x, y) has a wall at any of his points.
static bool box_blocked(const World *w, float x, float y) {
  return step_blocked(w, x, y, 0, -1) || step_blocked(w, x, y, 0, 1) || step_blocked(w, x, y, -1, 0) || step_blocked(w, x, y, 1, 0);
}

// Moves along one axis; when blocked, slides around a corner the way the originals do when only the
// edge of Link's body catches a wall.
static bool move_axis(Link *l, const World *w, float dx, float dy) {
  if (!step_blocked(w, l->x + dx, l->y + dy, dx, dy)) { l->x += dx; l->y += dy; return true; }
  if (dx != 0 && dy != 0) return false;
  for (int off = 1; off <= NUDGE_RANGE; off++) {
    for (int sgn = -1; sgn <= 1; sgn += 2) {
      float ox = dx == 0 ? (float)(off * sgn) : 0, oy = dy == 0 ? (float)(off * sgn) : 0;
      if (!step_blocked(w, l->x + ox, l->y + oy, ox, oy) && !step_blocked(w, l->x + ox + dx, l->y + oy + dy, dx, dy)) {
        l->x += ox != 0 ? (float)sgn * WALK_SPEED : 0;
        l->y += oy != 0 ? (float)sgn * WALK_SPEED : 0;
        return true;
      }
    }
  }
  return false;
}

bool link_blocked_at(const World *w, float x, float y) { return box_blocked(w, x, y); }

bool link_can_move(const World *w, float x, float y) {
  return !step_blocked(w, x, y - 1, 0, -1) || !step_blocked(w, x, y + 1, 0, 1) ||
         !step_blocked(w, x - 1, y, -1, 0) || !step_blocked(w, x + 1, y, 1, 0);
}

void link_push(Link *l, const World *w, float dx, float dy) {
  if (dx && !step_blocked(w, l->x + dx, l->y, dx, 0)) l->x += dx;
  if (dy && !step_blocked(w, l->x, l->y + dy, 0, dy)) l->y += dy;
}

void link_update(Link *l, const World *w, int dx, int dy) {
  l->moving = dx || dy;
  if (!l->moving) { l->walk_frames = 0; l->pushing = 0; return; }
  // face the direction pressed; on a diagonal keep facing whichever of the two Link already faced
  int want_h = dx > 0 ? DIR_RIGHT : DIR_LEFT, want_v = dy > 0 ? DIR_DOWN : DIR_UP;
  if (dx && dy) { if (l->dir != want_h && l->dir != want_v) l->dir = want_v; }
  else l->dir = dx ? want_h : want_v;
  float s = ((dx && dy) ? WALK_SPEED * 0.75f : WALK_SPEED) * link_speed;
  bool moved = false;
  if (dx) moved |= move_axis(l, w, dx * s, 0);
  if (dy) moved |= move_axis(l, w, 0, dy * s);
  l->pushing = moved ? 0 : l->pushing + 1;
  l->walk_frames++;
}


// ---- Link's animations (link_anims.bin from data/seasons/specialObjectAnimationData.s) ----------
// An animation is a list of (duration, gfx frame, parameter); a gfx frame at $54 or above has Link's
// direction added (code/specialObjectAnimationsAndDamage.s). A gfx frame is a piece of spr_link and
// the OAM layout that draws it: 8x16 sprites, as the Game Boy's sprite mode draws them.

typedef struct { uint8_t dur, gfx, param; } AnimFrame;
typedef struct { int n, loop; AnimFrame *f; } Anim;
typedef struct { uint8_t oam; uint16_t tile; } GfxFrame;
typedef struct { int n; uint8_t (*e)[4]; } Layout;

static Anim *anims;
static GfxFrame *gfx;
static Layout *layouts;
static int n_anims, n_gfx, n_layouts;

bool link_anims_load(void) {
  size_t size;
  Uint8 *d = asset_load("link_anims.bin", &size);
  if (!d) return false;
  const Uint8 *p = d, *end = d + size;
#define NEED(k) do { if (p + (k) > end) goto bad; } while (0)
  NEED(2); n_anims = p[0] | p[1] << 8; p += 2;
  anims = calloc((size_t)n_anims, sizeof *anims);
  for (int i = 0; i < n_anims; i++) {
    NEED(2);
    anims[i].n = p[0];
    anims[i].loop = p[1] == 0xff ? -1 : p[1];
    p += 2;
    NEED(anims[i].n * 3);
    anims[i].f = malloc((size_t)(anims[i].n + 1) * sizeof *anims[i].f);
    memcpy(anims[i].f, p, (size_t)anims[i].n * 3);
    p += anims[i].n * 3;
  }
  NEED(2); n_gfx = p[0] | p[1] << 8; p += 2;
  gfx = calloc((size_t)n_gfx, sizeof *gfx);
  for (int i = 0; i < n_gfx; i++, p += 3) { NEED(3); gfx[i] = (GfxFrame){p[0], (uint16_t)(p[1] | p[2] << 8)}; }
  NEED(2); n_layouts = p[0] | p[1] << 8; p += 2;
  layouts = calloc((size_t)n_layouts, sizeof *layouts);
  for (int i = 0; i < n_layouts; i++) {
    NEED(1);
    layouts[i].n = *p++;
    NEED(layouts[i].n * 4);
    layouts[i].e = malloc((size_t)(layouts[i].n + 1) * 4);
    memcpy(layouts[i].e, p, (size_t)layouts[i].n * 4);
    p += layouts[i].n * 4;
  }
#undef NEED
  SDL_free(d);
  return true;
bad:
  SDL_free(d);
  n_anims = 0;
  return false;
}

void link_set_anim(Link *l, int mode) {
  if (l->anim_mode == mode) return;
  l->anim_mode = mode;
  l->anim_frame = 0;
  l->anim_count = 0;
}

void link_anim_update(Link *l, bool advance) {
  if (l->anim_mode < 0 || l->anim_mode >= n_anims) return;
  const Anim *a = &anims[l->anim_mode];
  if (!a->n || !advance) return;
  int dur = a->f[l->anim_frame].dur;
  if (dur >= 0x7f) return;                   // held
  if (++l->anim_count < dur) return;
  l->anim_count = 0;
  if (l->anim_frame + 1 < a->n) l->anim_frame++;
  else if (a->loop >= 0) l->anim_frame = a->loop;
}

void link_draw(SDL_Renderer *ren, const Link *l, const Image *sheet, const View *v, float z) {
  if (z > 0) {
    float sx0 = view_sx(v, SDL_floorf(l->x) - 5), sy0 = view_sy(v, SDL_floorf(l->y) + 5);
    float sx1 = view_sx(v, SDL_floorf(l->x) + 5), sy1 = view_sy(v, SDL_floorf(l->y) + 8);
    fill_rect(ren, sx0, sy0, sx1 - sx0, sy1 - sy0, 0, 0, 0, 90);
  }
  float bx = SDL_floorf(l->x), by = SDL_floorf(l->y - z);
  int mode = l->anim_mode >= 0 && l->anim_mode < n_anims && anims[l->anim_mode].n ? l->anim_mode : LINK_ANIM_WALK;
  if (mode >= n_anims) return;
  const Anim *a = &anims[mode];
  int fi = l->anim_frame < a->n ? l->anim_frame : 0;
  int g = a->f[fi].gfx;
  if (g >= 0x54) g += l->dir;
  if (g >= n_gfx || gfx[g].oam >= n_layouts) return;
  const Layout *lay = &layouts[gfx[g].oam];
  int cols = sheet->w / 8;
  for (int i = 0; i < lay->n; i++) {
    const uint8_t *e = lay->e[i];
    float oy = (float)(int8_t)e[0] - 16, ox = (float)(int8_t)e[1] - 8;
    int pair = (gfx[g].tile + (e[2] & 0xfe)) / 2;
    int sx = (pair % cols) * 8, sy = (pair / cols) * 16;
    if (sy + 16 > sheet->h) continue;
    float x0 = view_sx(v, bx + ox), y0 = view_sy(v, by + oy), x1 = view_sx(v, bx + ox + 8), y1 = view_sy(v, by + oy + 16);
    SDL_FRect src = {(float)sx, (float)sy, 8, 16}, dst = {x0, y0, x1 - x0, y1 - y0};
    SDL_FlipMode flip = (SDL_FlipMode)((e[3] & 0x20 ? SDL_FLIP_HORIZONTAL : 0) | (e[3] & 0x40 ? SDL_FLIP_VERTICAL : 0));
    SDL_RenderTextureRotated(ren, sheet->tex, &src, &dst, 0, NULL, flip);
  }
}
