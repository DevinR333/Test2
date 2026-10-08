#include "companion.h"
#include <stdlib.h>
#include <string.h>

typedef struct { uint8_t dur, gfx; } CFrame;
typedef struct { int n, loop; CFrame f[32]; } CAnim;
typedef struct { uint16_t x, y; uint8_t w, h; int8_t ox, oy; } CGfx;
typedef struct { CAnim *anims; int n_anims; CGfx *gfx; int n_gfx; } Companion;

static Companion comps[3];
static Image sheet;
static int anim[3], frame[3], count[3], last_anim[3] = {-1, -1, -1};

// The animations each one walks and stands with, by direction (up, right, down, left).
static const int walk_base[3] = {9, 8, 5}, stand_base[3] = {1, 8, 1};

bool companion_load(SDL_Renderer *ren) {
  if (!image_load(ren, "companions.rgba", &sheet)) return false;
  size_t size;
  Uint8 *d = asset_load("companions.bin", &size);
  if (!d) return false;
  const Uint8 *p = d, *end = d + size;
  for (int c = 0; c < 3; c++) {
    Companion *k = &comps[c];
    if (p + 2 > end) break;
    k->n_anims = p[0] | p[1] << 8;
    p += 2;
    k->anims = calloc((size_t)k->n_anims + 1, sizeof *k->anims);
    for (int i = 0; i < k->n_anims && p + 2 <= end; i++) {
      CAnim *a = &k->anims[i];
      a->n = p[0] > 32 ? 32 : p[0];
      a->loop = p[1] == 0xff ? -1 : p[1];
      int raw = p[0];
      p += 2;
      for (int f = 0; f < raw && p + 2 <= end; f++, p += 2)
        if (f < 32) a->f[f] = (CFrame){p[0], p[1]};
    }
    if (p + 2 > end) break;
    k->n_gfx = p[0] | p[1] << 8;
    p += 2;
    k->gfx = calloc((size_t)k->n_gfx + 1, sizeof *k->gfx);
    for (int i = 0; i < k->n_gfx && p + 8 <= end; i++, p += 8)
      k->gfx[i] = (CGfx){(uint16_t)(p[0] | p[1] << 8), (uint16_t)(p[2] | p[3] << 8), p[4], p[5], (int8_t)p[6], (int8_t)p[7]};
  }
  SDL_free(d);
  return true;
}

void companion_update(int which, const Link *l) {
  if (which < 0 || which > 2) return;
  Companion *k = &comps[which];
  int a = (l->moving ? walk_base[which] : stand_base[which]) + l->dir;
  if (a >= k->n_anims) return;
  if (a != last_anim[which]) { last_anim[which] = a; frame[which] = 0; count[which] = 0; }
  anim[which] = a;
  const CAnim *an = &k->anims[a];
  if (!an->n) return;
  int dur = an->f[frame[which]].dur;
  if (dur >= 0x7f || ++count[which] < dur) return;
  count[which] = 0;
  if (frame[which] + 1 < an->n) frame[which]++;
  else if (an->loop >= 0) frame[which] = an->loop;
}

void companion_draw(SDL_Renderer *ren, const View *v, int which, const Link *l, float *rider_dy) {
  *rider_dy = 0;
  if (which < 0 || which > 2 || !comps[which].n_anims) return;
  Companion *k = &comps[which];
  const CAnim *an = &k->anims[anim[which] < k->n_anims ? anim[which] : 0];
  if (!an->n) return;
  int g = an->f[frame[which] < an->n ? frame[which] : 0].gfx;
  if (g >= k->n_gfx || !k->gfx[g].w) return;
  const CGfx *f = &k->gfx[g];
  float bx = SDL_floorf(l->x), by = SDL_floorf(l->y) + 2;
  float x0 = view_sx(v, bx + f->ox), y0 = view_sy(v, by + f->oy), x1 = view_sx(v, bx + f->ox + f->w), y1 = view_sy(v, by + f->oy + f->h);
  image_draw(ren, &sheet, f->x, f->y, f->w, f->h, x0, y0, x1 - x0, y1 - y0, false);
  *rider_dy = 8;
}
