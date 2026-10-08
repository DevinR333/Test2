#include "world.h"
#include <stdlib.h>
#include <string.h>

// ---- loading -----------------------------------------------------------------------------------

typedef struct { const Uint8 *p, *end; bool bad; } Reader;
static const Uint8 *take(Reader *r, size_t n) {
  if (r->bad || (size_t)(r->end - r->p) < n) { r->bad = true; return NULL; }
  const Uint8 *at = r->p;
  r->p += n;
  return at;
}
static unsigned u8(Reader *r) { const Uint8 *b = take(r, 1); return b ? b[0] : 0; }
static unsigned u16(Reader *r) { const Uint8 *b = take(r, 2); return b ? (unsigned)(b[0] | b[1] << 8) : 0; }

static bool map_load(Map *m, const char *name) {
  size_t size;
  Uint8 *d = asset_load(name, &size);
  if (!d) { SDL_Log("missing map %s", name); return false; }
  Reader r = {d, d + size, false};
  const Uint8 *magic = take(&r, 4);
  unsigned version = u16(&r);
  m->w = (int)u16(&r); m->h = (int)u16(&r); m->room_w = (int)u16(&r); m->room_h = (int)u16(&r);
  if (r.bad || memcmp(magic, "OWLD", 4) != 0 || version != 2) { SDL_Log("%s is not a version 2 map", name); SDL_free(d); return false; }
  size_t n = (size_t)m->w * m->h;
  m->cells = malloc(n * 2);
  m->coll = malloc(n);
  m->mt = malloc(n);
  for (size_t i = 0; i < n; i++) m->cells[i] = (uint16_t)u16(&r);
  const Uint8 *c = take(&r, n), *mt = take(&r, n);
  if (c) memcpy(m->coll, c, n);
  if (mt) memcpy(m->mt, mt, n);
  SDL_free(d);
  if (r.bad) SDL_Log("%s is truncated", name);
  return !r.bad;
}

int world_load_all(World **out) {
  size_t size;
  Uint8 *d = asset_load("areas.bin", &size);
  if (!d) { SDL_Log("missing areas.bin"); return 0; }
  Reader r = {d, d + size, false};
  const Uint8 *magic = take(&r, 4);
  unsigned version = u16(&r), count = u16(&r);
  if (r.bad || memcmp(magic, "OARE", 4) != 0 || version != 4) { SDL_Log("areas.bin: wrong format"); SDL_free(d); return 0; }
  World *areas = calloc(count, sizeof *areas);
  for (unsigned i = 0; i < count && !r.bad; i++) {
    World *w = &areas[i];
    w->id = (WorldId)u8(&r);
    w->kind = (AreaKind)u8(&r);
    w->group = (int)u8(&r);
    u8(&r);
    w->rooms_w = (int)u16(&r); w->rooms_h = (int)u16(&r);
    w->base.room_w = (int)u16(&r); w->base.room_h = (int)u16(&r);
    const Uint8 *name = take(&r, 32);
    if (name) { memcpy(w->name, name, 31); w->name[31] = 0; }
    int rooms = w->rooms_w * w->rooms_h;
    w->room_ids = malloc((size_t)rooms * 2);
    for (int k = 0; k < rooms; k++) w->room_ids[k] = (uint16_t)u16(&r);
    w->coll_mode = malloc((size_t)rooms);
    w->room_name = calloc((size_t)rooms, sizeof *w->room_name);
    const Uint8 *cm = take(&r, (size_t)rooms);
    if (cm) memcpy(w->coll_mode, cm, (size_t)rooms);
    for (int k = 0; k < rooms; k++) {
      const Uint8 *nm = take(&r, 32);
      if (nm) { memcpy(w->room_name[k], nm, 31); w->room_name[k][31] = 0; }
    }
    w->floor_cell = malloc((size_t)rooms * 2); w->chest_cell = malloc((size_t)rooms * 2);
    w->floor_coll = malloc((size_t)rooms); w->chest_coll = malloc((size_t)rooms); w->dungeon = malloc((size_t)rooms);
    w->closed_chest_cell = malloc((size_t)rooms * 2); w->closed_chest_coll = malloc((size_t)rooms);
    for (int k = 0; k < rooms; k++) {
      w->floor_cell[k] = (uint16_t)u16(&r); w->floor_coll[k] = (uint8_t)u8(&r);
      w->chest_cell[k] = (uint16_t)u16(&r); w->chest_coll[k] = (uint8_t)u8(&r);
      w->closed_chest_cell[k] = (uint16_t)u16(&r); w->closed_chest_coll[k] = (uint8_t)u8(&r);
      w->dungeon[k] = (uint8_t)u8(&r);
    }
    w->state = malloc((size_t)rooms);
    const Uint8 *st = take(&r, (size_t)rooms);
    if (st) memcpy(w->state, st, (size_t)rooms);
    w->tsidx = malloc((size_t)rooms * 2);
    for (int k = 0; k < rooms; k++) w->tsidx[k] = (uint16_t)u16(&r);
    w->base.w = w->rooms_w * w->base.room_w;
    w->base.h = w->rooms_h * w->base.room_h;
    size_t n = (size_t)w->base.w * w->base.h;
    w->base.cells = malloc(n * 2);
    for (size_t k = 0; k < n; k++) w->base.cells[k] = (uint16_t)u16(&r);
    w->base.coll = malloc(n);
    w->mt = malloc(n);
    const Uint8 *c = take(&r, n), *m = take(&r, n);
    if (c) memcpy(w->base.coll, c, n);
    if (m) memcpy(w->mt, m, n);
    w->base.mt = w->mt;
  }
  SDL_free(d);
  if (r.bad) { SDL_Log("areas.bin is truncated"); return 0; }
  // Holodrum (the Seasons overworld) also has its map in each season, for the Rod of Seasons
  int hol = world_overworld(areas, (int)count, WORLD_HOLODRUM, 0);
  if (hol >= 0) {
    World *w = &areas[hol];
    static const char *files[4] = {"holodrum_spring.map", "holodrum_summer.map", "holodrum_autumn.map", "holodrum_winter.map"};
    for (int s = 0; s < 4; s++) if (!map_load(&w->seasons[s], files[s])) return 0;
    int rooms = w->rooms_w * w->rooms_h;
    w->room_season = malloc((size_t)rooms);
    memset(w->room_season, SEASON_DEFAULT, (size_t)rooms);
  }
  *out = areas;
  return (int)count;
}

void world_free(World *w) {
  free(w->base.cells); free(w->base.coll);
  for (int s = 0; s < 4; s++) { free(w->seasons[s].cells); free(w->seasons[s].coll); free(w->seasons[s].mt); }
  free(w->tsidx);
  free(w->room_season); free(w->room_ids); free(w->coll_mode); free(w->room_name); free(w->mt);
  free(w->floor_cell); free(w->chest_cell); free(w->floor_coll); free(w->chest_coll); free(w->dungeon);
  free(w->closed_chest_cell); free(w->closed_chest_coll); free(w->state);
  memset(w, 0, sizeof *w);
}

int world_overworld(const World *areas, int n, WorldId game, int group) {
  for (int i = 0; i < n; i++)
    if (areas[i].kind == AREA_OVERWORLD && areas[i].id == game && areas[i].group == group) return i;
  return -1;
}

int world_px_w(const World *w) { return w->base.w * MT; }
int world_px_h(const World *w) { return w->base.h * MT; }

int world_room_index(const World *w, float px, float py) {
  if (px < 0 || py < 0) return -1;
  int rx = (int)px / (w->base.room_w * MT), ry = (int)py / (w->base.room_h * MT);
  if (rx >= w->rooms_w || ry >= w->rooms_h) return -1;
  return ry * w->rooms_w + rx;
}

const char *world_area_name(const World *w, float px, float py) {
  int r = world_room_index(w, px, py);
  if (r < 0) return "";
  return w->room_name[r][0] ? w->room_name[r] : w->name;
}

// The map a metatile is read from: the base map, or a season layer where the rod changed it.
static const Map *map_at(const World *w, int tx, int ty) {
  if (!w->room_season) return &w->base;
  int room = (ty / w->base.room_h) * w->rooms_w + tx / w->base.room_w;
  int s = w->room_season[room];
  return s == SEASON_DEFAULT ? &w->base : &w->seasons[s];
}

uint8_t world_collision(const World *w, int px, int py) {
  if (px < 0 || py < 0) return 0xff;
  int tx = px / MT, ty = py / MT;
  if (tx >= w->base.w || ty >= w->base.h) return 0xff;
  const Map *m = map_at(w, tx, ty);
  if (m->cells[ty * m->w + tx] == CELL_VOID) return 0xff;
  return m->coll[ty * m->w + tx];
}

// code/bank0.s checkGivenCollision_allowHoles: the table for collision values $10-$1f. Values below
// $18 are columns of a 16-pixel tile (bit n = pixels 2n, 2n+1 across), the rest rows.
static const uint8_t special_allow_holes[16] = {
  0x00, 0xc3, 0x03, 0xc0, 0x00, 0xc3, 0xc3, 0x00,
  0x00, 0xc3, 0x03, 0xc0, 0xc0, 0xc1, 0xff, 0x00,
};

bool world_solid(const World *w, int px, int py) {
  uint8_t c = world_collision(w, px, py);
  if (c == 0xff) return true;
  if (c < 0x10) {
    // bit 3: top-left quarter, 2: top-right, 1: bottom-left, 0: bottom-right
    int bit = 3 - ((py & 8) ? 2 : 0) - ((px & 8) ? 1 : 0);
    return c >> bit & 1;
  }
  if (c < 0x20) {
    int i = c & 0x0f;
    int pos = i < 8 ? px : py;
    return special_allow_holes[i] >> ((pos >> 1) & 7) & 1;
  }
  return true;
}

void world_draw(SDL_Renderer *ren, const World *w, const Image *atlas, const View *v) {
  int cols = atlas->w / MT;
  float vw = (float)v->viewport.w / v->scale, vh = (float)v->viewport.h / v->scale;
  int x0 = (int)SDL_floorf(v->x / MT), y0 = (int)SDL_floorf(v->y / MT);
  int x1 = (int)SDL_floorf((v->x + vw) / MT), y1 = (int)SDL_floorf((v->y + vh) / MT);
  if (x0 < 0) x0 = 0;
  if (y0 < 0) y0 = 0;
  if (x1 >= w->base.w) x1 = w->base.w - 1;
  if (y1 >= w->base.h) y1 = w->base.h - 1;
  for (int ty = y0; ty <= y1; ty++) {
    float sy = view_sy(v, (float)(ty * MT)), sy2 = view_sy(v, (float)((ty + 1) * MT));
    for (int tx = x0; tx <= x1; tx++) {
      const Map *m = map_at(w, tx, ty);
      uint16_t c = m->cells[ty * m->w + tx];
      if (c == CELL_VOID) continue;
      float sx = view_sx(v, (float)(tx * MT)), sx2 = view_sx(v, (float)((tx + 1) * MT));
      image_draw(ren, atlas, (c % cols) * MT, (c / cols) * MT, MT, MT, sx, sy, sx2 - sx, sy2 - sy, false);
    }
  }
}

// ---- warps -------------------------------------------------------------------------------------

typedef struct { uint8_t game, group, room, positioned, mask_or_yx, dest_index, dest_group, transition; } WarpSource;
typedef struct { uint8_t game, group, index, room, yx, param_transition; } WarpDest;

static WarpSource *sources;
static WarpDest *dests;
static int n_sources, n_dests;
static uint8_t warp_tile_ids[2][8][16];   // per game, per collision mode: tiles that warp, 0-ended

bool warps_load(void) {
  size_t size;
  Uint8 *d = asset_load("warps.bin", &size);
  if (!d) { SDL_Log("missing warps.bin"); return false; }
  Reader r = {d, d + size, false};
  const Uint8 *magic = take(&r, 4);
  unsigned version = u16(&r);
  n_sources = (int)u16(&r);
  n_dests = (int)u16(&r);
  if (r.bad || memcmp(magic, "OWRP", 4) != 0 || version != 1) { SDL_free(d); return false; }
  sources = calloc((size_t)n_sources, sizeof *sources);
  dests = calloc((size_t)n_dests, sizeof *dests);
  const Uint8 *s = take(&r, (size_t)n_sources * 8), *t = take(&r, (size_t)n_dests * 7), *tiles = take(&r, sizeof warp_tile_ids);
  if (r.bad) { SDL_free(d); return false; }
  for (int i = 0; i < n_sources; i++) memcpy(&sources[i], s + i * 8, 8);
  for (int i = 0; i < n_dests; i++) {
    const Uint8 *e = t + i * 7;
    dests[i] = (WarpDest){e[0], e[1], e[2], e[3], e[4], (uint8_t)(e[5] << 4 | e[6])};
  }
  memcpy(warp_tile_ids, tiles, sizeof warp_tile_ids);
  SDL_free(d);
  return true;
}

uint8_t world_metatile(const World *w, float px, float py) {
  if (px < 0 || py < 0 || px >= (float)world_px_w(w) || py >= (float)world_px_h(w)) return 0;
  int tx = (int)px / MT, ty = (int)py / MT;
  return map_at(w, tx, ty)->mt[ty * w->base.w + tx];
}

uint16_t world_cell_at(const World *w, int tx, int ty) {
  if (tx < 0 || ty < 0 || tx >= w->base.w || ty >= w->base.h) return CELL_VOID;
  return map_at(w, tx, ty)->cells[ty * w->base.w + tx];
}

uint8_t world_mt_at(const World *w, int tx, int ty, int *room) {
  if (tx < 0 || ty < 0 || tx >= w->base.w || ty >= w->base.h) { if (room) *room = -1; return 0; }
  if (room) *room = (ty / w->base.room_h) * w->rooms_w + tx / w->base.room_w;
  return map_at(w, tx, ty)->mt[ty * w->base.w + tx];
}

void world_set_tile(World *w, int tx, int ty, uint16_t cell, uint8_t coll, uint8_t mt) {
  if (tx < 0 || ty < 0 || tx >= w->base.w || ty >= w->base.h) return;
  int i = ty * w->base.w + tx;
  w->base.cells[i] = cell;
  w->base.coll[i] = coll;
  w->mt[i] = mt;
  for (int s = 0; s < 4; s++)
    if (w->seasons[s].cells) { w->seasons[s].cells[i] = cell; w->seasons[s].coll[i] = coll; w->seasons[s].mt[i] = mt; }
}

bool world_find_room(const World *areas, int n, int game, int group, int room, int *area, float *ox, float *oy);
// Where a room of a game sits: its area and the top-left world pixel of that screen.
static bool find_room(const World *areas, int n, int game, int group, int room, int *area, float *ox, float *oy) {
  // the overworld first (rooms of one group can also appear as single-room areas elsewhere)
  for (int pass = 0; pass < 2; pass++)
    for (int i = 0; i < n; i++) {
      const World *w = &areas[i];
      if ((int)w->id != game || w->group != group || (pass == 0) != (w->kind != AREA_ROOM)) continue;
      for (int k = 0; k < w->rooms_w * w->rooms_h; k++)
        if (w->room_ids[k] == room) {
          *area = i;
          *ox = (float)((k % w->rooms_w) * w->base.room_w * MT);
          *oy = (float)((k / w->rooms_w) * w->base.room_h * MT);
          return true;
        }
    }
  return false;
}

static bool resolve(const World *areas, int n, const WarpSource *s, WarpTarget *out) {
  for (int i = 0; i < n_dests; i++) {
    const WarpDest *d = &dests[i];
    if (d->game != s->game || d->group != s->dest_group || d->index != s->dest_index) continue;
    float ox, oy;
    if (!find_room(areas, n, d->game, d->group, d->room, &out->area, &ox, &oy)) return false;
    int yx = d->yx == 0xff ? 0x44 : d->yx;    // $ff: the game places Link itself; the middle will do
    out->x = ox + (float)((yx & 15) * MT + MT / 2);
    out->y = oy + (float)((yx >> 4) * MT + MT / 2);
    return true;
  }
  return false;
}

bool world_on_warp_tile(const World *w, float px, float py) {
  int r = world_room_index(w, px, py);
  if (r < 0) return false;
  int tx = (int)px / MT, ty = (int)py / MT;
  uint8_t mt = w->mt[ty * w->base.w + tx];
  const uint8_t *list = warp_tile_ids[w->id][w->coll_mode[r] & 7];
  for (int i = 0; i < 16 && list[i]; i++) if (list[i] == mt) return true;
  return false;
}

bool warp_from_tile(const World *areas, int n, const World *w, float px, float py, WarpTarget *out) {
  int r = world_room_index(w, px, py);
  if (r < 0 || w->room_ids[r] == 0xffff) return false;
  int room = w->room_ids[r];
  int lx = ((int)px / MT) % w->base.room_w, ly = ((int)py / MT) % w->base.room_h;
  const WarpSource *whole = NULL;
  for (int i = 0; i < n_sources; i++) {
    const WarpSource *s = &sources[i];
    if (s->game != w->id || s->group != w->group || s->room != room) continue;
    if (s->positioned && s->mask_or_yx == (ly << 4 | lx)) return resolve(areas, n, s, out);
    if (!s->positioned && s->mask_or_yx == 0 && !whole) whole = s;   // the screen's tile warp
  }
  return whole && resolve(areas, n, whole, out);
}

bool warp_from_edge(const World *areas, int n, const World *w, float px, float py, WarpTarget *out) {
  // code/bank4 findScreenEdgeWarpSource: only the top and bottom edges warp. The source's bits are
  // the half of that edge Link leaves by: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right,
  // split at x $60 (Seasons) or $58 (Ages) in small rooms and $80 in large ones (getLinkWarpQuadrant).
  float cx = SDL_clamp(px, 0, (float)world_px_w(w) - 1), cy = SDL_clamp(py, 0, (float)world_px_h(w) - 1);
  int r = world_room_index(w, cx, cy);
  if (r < 0 || w->room_ids[r] == 0xffff) return false;
  float rw = (float)(w->base.room_w * MT), rh = (float)(w->base.room_h * MT);
  float lx = SDL_fmodf(cx, rw), ly = SDL_fmodf(cy, rh);
  float split = w->base.room_w > 10 ? 0x80 : w->id == WORLD_LABRYNNA ? 0x58 : 0x60;
  int bit = 1 << ((ly >= rh / 2 ? 2 : 0) + (lx >= split ? 1 : 0));
  for (int i = 0; i < n_sources; i++) {
    const WarpSource *s = &sources[i];
    if (s->game != w->id || s->group != w->group || s->room != w->room_ids[r] || s->positioned) continue;
    if (s->mask_or_yx & bit) return resolve(areas, n, s, out);
  }
  return false;
}

bool world_find_room(const World *areas, int n, int game, int group, int room, int *area, float *ox, float *oy) {
  return find_room(areas, n, game, group, room, area, ox, oy);
}

int warps_resolvable(const World *areas, int n, int *total) {
  int ok = 0;
  *total = n_sources;
  for (int i = 0; i < n_sources; i++) {
    WarpTarget t;
    if (resolve(areas, n, &sources[i], &t)) ok++;
    else SDL_Log("warp %s group %d room %02x -> group %d dest %02x: no such room",
                 sources[i].game ? "ages" : "seasons", sources[i].group, sources[i].room, sources[i].dest_group, sources[i].dest_index);
  }
  return ok;
}
