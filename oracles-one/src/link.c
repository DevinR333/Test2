#include "link.h"

#define WALK_SPEED 1.0f          // pixels per frame, the originals' walking speed
#define NUDGE_RANGE 6            // how far off a gap Link may be and still slide into it

// Link touches walls with the lower part of his body: a box below his middle.
#define BOX_L (-4)
#define BOX_R 3
#define BOX_T (-1)
#define BOX_B 6

static bool box_blocked(const World *w, float x, float y) {
  int l = (int)SDL_floorf(x) + BOX_L, r = (int)SDL_floorf(x) + BOX_R;
  int t = (int)SDL_floorf(y) + BOX_T, b = (int)SDL_floorf(y) + BOX_B;
  for (int px = l; px <= r; px += 4) {
    if (world_solid(w, px, t) || world_solid(w, px, b)) return true;
  }
  for (int py = t; py <= b; py += 3) {
    if (world_solid(w, l, py) || world_solid(w, r, py)) return true;
  }
  return world_solid(w, r, t) || world_solid(w, r, b);
}

// Moves along one axis; when blocked, slides around a corner the way the originals do when only the
// edge of Link's body catches a wall.
static bool move_axis(Link *l, const World *w, float dx, float dy) {
  if (!box_blocked(w, l->x + dx, l->y + dy)) { l->x += dx; l->y += dy; return true; }
  if (dx != 0 && dy != 0) return false;
  for (int off = 1; off <= NUDGE_RANGE; off++) {
    for (int sgn = -1; sgn <= 1; sgn += 2) {
      float ox = dx == 0 ? (float)(off * sgn) : 0, oy = dy == 0 ? (float)(off * sgn) : 0;
      if (!box_blocked(w, l->x + ox, l->y + oy) && !box_blocked(w, l->x + ox + dx, l->y + oy + dy)) {
        l->x += ox != 0 ? (float)sgn * WALK_SPEED : 0;
        l->y += oy != 0 ? (float)sgn * WALK_SPEED : 0;
        return true;
      }
    }
  }
  return false;
}

void link_update(Link *l, const World *w, int dx, int dy) {
  l->moving = dx || dy;
  if (!l->moving) { l->walk_frames = 0; l->pushing = 0; return; }
  // face the direction pressed; on a diagonal keep facing whichever of the two Link already faced
  int want_h = dx > 0 ? DIR_RIGHT : DIR_LEFT, want_v = dy > 0 ? DIR_DOWN : DIR_UP;
  if (dx && dy) { if (l->dir != want_h && l->dir != want_v) l->dir = want_v; }
  else l->dir = dx ? want_h : want_v;
  float s = (dx && dy) ? WALK_SPEED * 0.75f : WALK_SPEED;
  bool moved = false;
  if (dx) moved |= move_axis(l, w, dx * s, 0);
  if (dy) moved |= move_axis(l, w, 0, dy * s);
  l->pushing = moved ? 0 : l->pushing + 1;
  l->walk_frames++;
}

// Cells of gfx/common/spr_link.png (16x16 each): two walking frames per direction.
static const int frames[4][2][2] = {
  [DIR_UP]    = {{0, 0}, {1, 0}},
  [DIR_RIGHT] = {{2, 0}, {3, 0}},
  [DIR_DOWN]  = {{0, 1}, {1, 1}},
  [DIR_LEFT]  = {{2, 0}, {3, 0}},
};

void link_draw(SDL_Renderer *ren, const Link *l, const Image *sheet, const View *v, float z) {
  int step = l->moving ? (l->walk_frames / 6) & 1 : 0;
  const int *f = frames[l->dir][step];
  if (z > 0) {
    float sx0 = view_sx(v, SDL_floorf(l->x) - 5), sy0 = view_sy(v, SDL_floorf(l->y) + 5);
    float sx1 = view_sx(v, SDL_floorf(l->x) + 5), sy1 = view_sy(v, SDL_floorf(l->y) + 8);
    fill_rect(ren, sx0, sy0, sx1 - sx0, sy1 - sy0, 0, 0, 0, 90);
  }
  float y = SDL_floorf(l->y - z);
  float x0 = view_sx(v, SDL_floorf(l->x) - 8), y0 = view_sy(v, y - 8);
  float x1 = view_sx(v, SDL_floorf(l->x) + 8), y1 = view_sy(v, y + 8);
  image_draw(ren, sheet, f[0] * 16, f[1] * 16, 16, 16, x0, y0, x1 - x0, y1 - y0, l->dir == DIR_LEFT);
}
