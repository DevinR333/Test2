#include "terrain.h"
#include "actors.h"
#include "treasure.h"
#include <stdlib.h>
#include <string.h>

#define MAX_TEMP 512
#define MAX_BITS 32
#define MAX_BOMBS 3
#define REGROW_DISTANCE 220.0f   // cut grass and bushes come back once Link is this far away

typedef struct { int area, tx, ty; uint8_t mt; } TempChange;
typedef struct { float x, y, vx, vy; int t; uint16_t cell; int qx, qy; } Bit;

static struct {
  TempChange temp[MAX_TEMP];
  int n_temp, area;
  // a ledge jump
  int jump_t, jump_len;
  float jx0, jy0, jx1, jy1, jump_h;
  // falling into a hole, drowning, burning
  int fall_t, fall_damage;
  float safe_x, safe_y;
  bool swimming;
  // lifted and thrown
  bool carrying;
  uint16_t carry_cell;
  bool flying;
  float fx, fy, fz, fvx, fvy;
  int fly_t;
  uint16_t fly_cell;
  // a block sliding
  int push_t;
  float px0, py0, pdx, pdy;
  uint16_t push_cell;
  int push_tx, push_ty;
  uint8_t push_mt;
  // bombs and their blasts
  struct { bool live; float x, y; int fuse, blast; } bombs[MAX_BOMBS];
  Bit bits[MAX_BITS];
  Uint32 rng;
} T = {.rng = 99};

void (*terrain_icon)(SDL_Renderer *ren, Item item, float x, float y, float px);

static float frand(void) { T.rng = T.rng * 1664525u + 1013904223u; return (float)(T.rng >> 8) / (float)(1u << 24); }

static const int dir_x[4] = {0, 1, 0, -1}, dir_y[4] = {-1, 0, 1, 0};
// the point in front of Link that his hands, sword or shoulder touch (as for chests)
static const int front_x[4] = {0, 9, 0, -9}, front_y[4] = {-6, 4, 14, 4};

bool terrain_busy(void) { return T.jump_t > 0 || T.fall_t > 0; }
bool terrain_swimming(void) { return T.swimming; }
bool terrain_carrying(void) { return T.carrying; }
float terrain_z(void) {
  if (!T.jump_t) return 0;
  float t = 1.0f - (float)T.jump_t / (float)T.jump_len;
  return SDL_sinf(t * SDL_PI_F) * T.jump_h;
}

static void restore(World *areas, int i) {
  TempChange *c = &T.temp[i];
  world_put(&areas[c->area], c->tx, c->ty, c->mt);
  T.temp[i] = T.temp[--T.n_temp];
}

void terrain_enter(World *areas, int n, World *w, const Link *l) {
  for (int i = T.n_temp - 1; i >= 0; i--)
    if (T.temp[i].area < n) restore(areas, i);
  T.area = (int)(w - areas);
  T.jump_t = T.fall_t = T.push_t = 0;
  T.carrying = T.flying = false;
  memset(T.bombs, 0, sizeof T.bombs);
  T.safe_x = l->x;
  T.safe_y = l->y;
  T.swimming = false;
}

static void debris(uint16_t cell, float x, float y) {
  for (int q = 0; q < 4; q++) {
    for (int i = 0; i < MAX_BITS; i++) {
      Bit *b = &T.bits[i];
      if (b->t) continue;
      int qx = q & 1, qy = q >> 1;
      *b = (Bit){x + (float)(qx * 8 - 8), y + (float)(qy * 8 - 8), (float)(qx * 2 - 1) * (0.6f + frand() * 0.6f),
                 (float)(qy * 2 - 1) * (0.6f + frand() * 0.6f) - 0.8f, 20, cell, qx, qy};
      break;
    }
  }
}

// Breaks a tile the way breakableTileModes says: what it becomes, an item maybe, forever or not.
static void break_tile(World *areas, World *w, int tx, int ty, const BreakMode *m) {
  uint16_t cell = world_cell_at(w, tx, ty);
  int room;
  uint8_t old = world_mt_at(w, tx, ty, &room);
  if (m->result) {
    if (m->flags & 0x80) tile_change_for_good(areas, w, tx, ty, m->result);   // bombed walls and the like
    else {
      if (T.n_temp < MAX_TEMP) T.temp[T.n_temp++] = (TempChange){(int)(w - areas), tx, ty, old};
      world_put(w, tx, ty, m->result);
    }
  }
  float cx = (float)(tx * MT + 8), cy = (float)(ty * MT + 8);
  if (cell != CELL_VOID) debris(cell, cx, cy);
  if (m->drop && frand() < 0.3f) actors_drop(cx, cy);
}

// The breakable mode of a tile if the given source breaks it.
static const BreakMode *breaks(const World *w, int tx, int ty, int source) {
  int room;
  uint8_t mt = world_mt_at(w, tx, ty, &room);
  const TileProps *p = tile_props(w, room, mt);
  if (p->breakable == 0xff) return NULL;
  const BreakMode *m = break_mode(w, p->breakable);
  return m && (m->sources >> source & 1) ? m : NULL;
}

void terrain_sword(World *areas, int n, World *w, SDL_FRect box, int level) {
  (void)n;
  if (box.w <= 0) return;
  int src = level >= 2 ? BREAK_SWORD_L2 : BREAK_SWORD_L1;
  const float pts[5][2] = {{0.5f, 0.5f}, {0.15f, 0.15f}, {0.85f, 0.15f}, {0.15f, 0.85f}, {0.85f, 0.85f}};
  for (int i = 0; i < 5; i++) {
    int tx = (int)(box.x + box.w * pts[i][0]) / MT, ty = (int)(box.y + box.h * pts[i][1]) / MT;
    const BreakMode *m = breaks(w, tx, ty, src);
    if (!m && src == BREAK_SWORD_L2) m = breaks(w, tx, ty, BREAK_SWORD_L1);
    if (m) break_tile(areas, w, tx, ty, m);
  }
}

static void front_tile(const Link *l, int *tx, int *ty) {
  *tx = (int)(l->x + front_x[l->dir]) / MT;
  *ty = (int)(l->y + front_y[l->dir]) / MT;
}

bool terrain_use(Item item, World *areas, int n, World *w, Link *l) {
  (void)n;
  if (terrain_busy()) return true;
  if (T.carrying) {
    // any button throws what Link holds
    T.carrying = false;
    T.flying = true;
    T.fx = l->x; T.fy = l->y; T.fz = 14;
    T.fvx = (float)dir_x[l->dir] * 2.5f; T.fvy = (float)dir_y[l->dir] * 2.5f;
    T.fly_t = 18;
    T.fly_cell = T.carry_cell;
    return true;
  }
  int tx, ty;
  front_tile(l, &tx, &ty);
  switch (item) {
  case ITEM_POWER_BRACELET:
  case ITEM_POWER_GLOVES: {
    const BreakMode *m = breaks(w, tx, ty, BREAK_BRACELET);
    if (!m) return false;
    T.carry_cell = world_cell_at(w, tx, ty);
    int room;
    uint8_t old = world_mt_at(w, tx, ty, &room);
    if (m->result) {
      if (T.n_temp < MAX_TEMP) T.temp[T.n_temp++] = (TempChange){(int)(w - areas), tx, ty, old};
      world_put(w, tx, ty, m->result);
    }
    if (m->drop && frand() < 0.3f) actors_drop((float)(tx * MT + 8), (float)(ty * MT + 8));
    T.carrying = true;
    return true;
  }
  case ITEM_BOMBS:
    if (!game.bombs) return true;
    for (int i = 0; i < MAX_BOMBS; i++)
      if (!T.bombs[i].live) {
        T.bombs[i].live = true;
        T.bombs[i].x = l->x + (float)dir_x[l->dir] * 12;
        T.bombs[i].y = l->y + 4 + (float)dir_y[l->dir] * 12;
        T.bombs[i].fuse = 90;
        T.bombs[i].blast = 0;
        game.bombs--;
        break;
      }
    return true;
  case ITEM_SHOVEL: {
    const BreakMode *m = breaks(w, tx, ty, BREAK_SHOVEL);
    if (m) break_tile(areas, w, tx, ty, m);
    return true;
  }
  case ITEM_SEED_SATCHEL:
  case ITEM_SLINGSHOT:
  case ITEM_SEED_SHOOTER: {
    // ember seeds burn trees and bushes in front (satchel drops them, the shooters fire them)
    if (!game.seeds[0]) return false;
    const BreakMode *m = breaks(w, tx, ty, BREAK_EMBER);
    if (!m) return false;
    game.seeds[0]--;
    break_tile(areas, w, tx, ty, m);
    return true;
  }
  default:
    return false;
  }
}

const char *terrain_read(World *w, const Link *l) {
  int tx, ty, room;
  front_tile(l, &tx, &ty);
  uint8_t mt = world_mt_at(w, tx, ty, &room);
  const TileProps *p = tile_props(w, room, mt);
  if (p->interact == 0xff || (p->interact & 0x0f) != 5) return NULL;
  const char *s = sign_text(w, tx, ty);
  return s && s[0] ? s : NULL;
}

static bool is_hazard(const TileProps *p) {
  return p->type == TT_HOLE || p->type == TT_WATER || p->type == TT_SEAWATER || p->type == TT_LAVA;
}

static bool on_safe_ground(const World *w, const Link *l) {
  const float pts[4][2] = {{-5, -1}, {4, -1}, {-5, 7}, {4, 7}};
  for (int i = 0; i < 4; i++)
    if (is_hazard(tile_props_at(w, l->x + pts[i][0], l->y + pts[i][1]))) return false;
  return true;
}

// Link walked into a ledge he can jump off: where he lands (the first spot past it he fits).
static bool ledge_landing(const World *w, const Link *l, float *lx, float *ly) {
  for (int d = 16; d <= 72; d += 2) {
    float x = l->x + (float)(dir_x[l->dir] * d), y = l->y + (float)(dir_y[l->dir] * d);
    if (link_blocked_at(w, x, y)) continue;
    const TileProps *p = tile_props_at(w, x, y + 3);
    if (p->cliff != 0xff) continue;
    *lx = x; *ly = y;
    return true;
  }
  return false;
}

static void push_block(World *w, Link *l) {
  int tx, ty, room;
  front_tile(l, &tx, &ty);
  uint8_t mt = world_mt_at(w, tx, ty, &room);
  const TileProps *p = tile_props(w, room, mt);
  if (p->interact == 0xff || (p->interact & 0x0f) != 0) return;
  if (!(p->interact & 0x80) && ((p->interact >> 4) & 3) != l->dir) return;
  if ((p->interact & 0x40) && !game.item_level[ITEM_POWER_BRACELET] && !game.item_level[ITEM_POWER_GLOVES]) return;
  int nx = tx + dir_x[l->dir], ny = ty + dir_y[l->dir], room2;
  world_mt_at(w, nx, ny, &room2);
  if (room2 != room || world_collision(w, nx * MT + 1, ny * MT + 1) != 0) return;
  uint8_t under = p->push_under ? p->push_under : 0xa0, becomes = p->push_becomes ? p->push_becomes : mt;
  T.push_cell = world_cell_at(w, tx, ty);
  world_put(w, tx, ty, under);
  T.push_t = 16;
  T.px0 = (float)(tx * MT); T.py0 = (float)(ty * MT);
  T.pdx = (float)dir_x[l->dir]; T.pdy = (float)dir_y[l->dir];
  T.push_tx = nx; T.push_ty = ny; T.push_mt = becomes;
}

TerrainEvents terrain_update(World *areas, int n, World *w, Link *l, int dx, int dy) {
  TerrainEvents ev = {0};
  (void)n;
  // grass and bushes grow back once out of sight
  for (int i = T.n_temp - 1; i >= 0; i--) {
    TempChange *c = &T.temp[i];
    if (c->area != T.area) continue;
    float cx = (float)(c->tx * MT + 8) - l->x, cy = (float)(c->ty * MT + 8) - l->y;
    if (SDL_fabsf(cx) > REGROW_DISTANCE || SDL_fabsf(cy) > REGROW_DISTANCE) restore(areas, i);
  }
  for (int i = 0; i < MAX_BITS; i++)
    if (T.bits[i].t) { Bit *b = &T.bits[i]; b->t--; b->x += b->vx; b->y += b->vy; b->vy += 0.08f; }
  if (T.push_t && --T.push_t == 0) world_put(w, T.push_tx, T.push_ty, T.push_mt);
  if (T.flying) {
    T.fx += T.fvx; T.fy += T.fvy; T.fz -= 14.0f / 18.0f;
    if (--T.fly_t <= 0 || world_solid(w, (int)T.fx, (int)T.fy)) {
      T.flying = false;
      debris(T.fly_cell, T.fx, T.fy);
      actors_hit_area(T.fx, T.fy, 12, 2);
    }
  }
  for (int i = 0; i < MAX_BOMBS; i++) {
    if (!T.bombs[i].live) continue;
    if (T.bombs[i].blast) {
      if (--T.bombs[i].blast == 0) T.bombs[i].live = false;
      continue;
    }
    if (--T.bombs[i].fuse > 0) continue;
    // the blast: walls and rocks that bombs break, enemies and Link nearby
    float bx = T.bombs[i].x, by = T.bombs[i].y;
    T.bombs[i].blast = 18;
    for (int ty = (int)(by - 24) / MT; ty <= (int)(by + 24) / MT; ty++)
      for (int tx = (int)(bx - 24) / MT; tx <= (int)(bx + 24) / MT; tx++) {
        float cx = (float)(tx * MT + 8) - bx, cy = (float)(ty * MT + 8) - by;
        if (cx * cx + cy * cy > 22 * 22) continue;
        const BreakMode *m = breaks(w, tx, ty, BREAK_BOMB);
        if (m) break_tile(areas, w, tx, ty, m);
      }
    actors_hit_area(bx, by, 20, 4);
    float lx = l->x - bx, ly = l->y - by;
    if (lx * lx + ly * ly < 16 * 16) ev.damage = 2;
  }
  // a ledge jump in progress
  if (T.jump_t) {
    T.jump_t--;
    float t = 1.0f - (float)T.jump_t / (float)T.jump_len;
    l->x = T.jx0 + (T.jx1 - T.jx0) * t;
    l->y = T.jy0 + (T.jy1 - T.jy0) * t;
    l->moving = true;
    l->walk_frames++;
    if (!T.jump_t) { l->x = T.jx1; l->y = T.jy1; }
    return ev;
  }
  if (T.fall_t) {
    if (--T.fall_t == 0) {
      l->x = T.safe_x; l->y = T.safe_y;
      ev.damage = T.fall_damage;
    }
    return ev;
  }
  // walking into a ledge he faces jumps off it
  if (l->pushing >= 3 && (dx || dy)) {
    const TileProps *p = tile_props_at(w, l->x + front_x[l->dir], l->y + front_y[l->dir]);
    float lx, ly;
    if (p->cliff == l->dir * 8 && !T.carrying && ledge_landing(w, l, &lx, &ly)) {
      float d = SDL_fabsf(lx - l->x) + SDL_fabsf(ly - l->y);
      T.jx0 = l->x; T.jy0 = l->y; T.jx1 = lx; T.jy1 = ly;
      T.jump_len = T.jump_t = 14 + (int)(d / 2);
      T.jump_h = 8 + d / 6;
      return ev;
    }
  }
  if (l->pushing == 20) push_block(w, l);
  // holes, water, lava under Link's feet
  const TileProps *under = tile_props_at(w, l->x, l->y + 3);
  T.swimming = false;
  if (under->type == TT_HOLE || under->type == TT_LAVA) {
    T.fall_t = 30;
    T.fall_damage = under->type == TT_LAVA ? 4 : 2;
    T.carrying = false;
  } else if (under->type == TT_WATER || under->type == TT_SEAWATER) {
    if (game.item_level[ITEM_MERMAID_SUIT]) T.swimming = true;     // flippers or the mermaid suit
    else { T.fall_t = 30; T.fall_damage = 2; T.carrying = false; }
  } else if (on_safe_ground(w, l)) {
    T.safe_x = l->x; T.safe_y = l->y;
  }
  // conveyors and currents carry Link along
  int t = under->type, cdir = -1;
  if (t >= TT_UPCONVEYOR && t <= TT_LEFTCONVEYOR) cdir = t - TT_UPCONVEYOR;
  if (t >= TT_UPCURRENT && t <= TT_LEFTCURRENT) cdir = t - TT_UPCURRENT;
  if (cdir >= 0) link_push(l, w, (float)dir_x[cdir] * 0.5f, (float)dir_y[cdir] * 0.5f);
  return ev;
}

static void draw_cell(SDL_Renderer *ren, const Image *atlas, const View *v, uint16_t cell, float x, float y) {
  if (cell == CELL_VOID) return;
  int cols = atlas->w / MT;
  float x0 = view_sx(v, x), y0 = view_sy(v, y), x1 = view_sx(v, x + MT), y1 = view_sy(v, y + MT);
  image_draw(ren, atlas, (cell % cols) * MT, (cell / cols) * MT, MT, MT, x0, y0, x1 - x0, y1 - y0, false);
}

void terrain_draw(SDL_Renderer *ren, const Image *atlas, const View *v, const Link *l, bool above) {
  int cols = atlas->w / MT;
  if (!above) {
    if (T.push_t) {
      float t = 1.0f - (float)T.push_t / 16.0f;
      draw_cell(ren, atlas, v, T.push_cell, T.px0 + T.pdx * 16 * t, T.py0 + T.pdy * 16 * t);
    }
    for (int i = 0; i < MAX_BOMBS; i++) {
      if (!T.bombs[i].live) continue;
      float bx = T.bombs[i].x, by = T.bombs[i].y;
      if (T.bombs[i].blast) {
        // a burst of fire: rings growing and fading
        float t = 1.0f - (float)T.bombs[i].blast / 18.0f;
        for (int r = 0; r < 3; r++) {
          float rad = (8 + 14 * t) * (1.0f - (float)r * 0.3f);
          Uint8 g = (Uint8)(r == 2 ? 250 : r == 1 ? 180 : 90);
          for (float a = 0; a < 6.283f; a += 0.5f) {
            float x = bx + SDL_cosf(a) * rad, y = by + SDL_sinf(a) * rad * 0.8f;
            float x0 = view_sx(v, x - 3), y0 = view_sy(v, y - 3), x1 = view_sx(v, x + 3), y1 = view_sy(v, y + 3);
            fill_rect(ren, x0, y0, x1 - x0, y1 - y0, 255, g, 40, (Uint8)(255 * (1 - t)));
          }
        }
        continue;
      }
      if (T.bombs[i].fuse < 30 && (T.bombs[i].fuse / 3) & 1) continue;    // blinking before it goes
      if (terrain_icon) terrain_icon(ren, ITEM_BOMBS, view_sx(v, bx - 8), view_sy(v, by - 12), v->scale);
      else {
        float x0 = view_sx(v, bx - 5), y0 = view_sy(v, by - 5), x1 = view_sx(v, bx + 5), y1 = view_sy(v, by + 5);
        fill_rect(ren, x0, y0, x1 - x0, y1 - y0, 40, 60, 160, 255);
      }
    }
    for (int i = 0; i < MAX_BITS; i++) {
      const Bit *b = &T.bits[i];
      if (!b->t || b->cell == CELL_VOID) continue;
      float x0 = view_sx(v, b->x), y0 = view_sy(v, b->y), x1 = view_sx(v, b->x + 8), y1 = view_sy(v, b->y + 8);
      image_draw(ren, atlas, (b->cell % cols) * MT + b->qx * 8, (b->cell / cols) * MT + b->qy * 8, 8, 8, x0, y0, x1 - x0, y1 - y0, false);
    }
    return;
  }
  if (T.carrying) draw_cell(ren, atlas, v, T.carry_cell, l->x - 8, l->y - 8 - 13 - terrain_z());
  if (T.flying) {
    float sx0 = view_sx(v, T.fx - 5), sy0 = view_sy(v, T.fy + 4), sx1 = view_sx(v, T.fx + 5), sy1 = view_sy(v, T.fy + 7);
    fill_rect(ren, sx0, sy0, sx1 - sx0, sy1 - sy0, 0, 0, 0, 90);
    draw_cell(ren, atlas, v, T.fly_cell, T.fx - 8, T.fy - 8 - T.fz);
  }
}

int terrain_falling(void) { return T.fall_t; }
