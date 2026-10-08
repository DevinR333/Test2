// Tile properties from tiles.bin (tools/extract_world.py write_tiles): every tileset's metatiles, so
// a tile can turn into another (a cut bush, a pushed block), and the originals' tables of cliffs,
// hazards, tile types, breakable and interactable tiles, and the signs.
#include "world.h"
#include <stdlib.h>
#include <string.h>

typedef struct { uint16_t cell[256]; uint8_t coll[256]; } Tileset;
typedef struct { uint8_t game, group, room, yx; char text[120]; } Sign;

static Tileset *tilesets;
static int n_tilesets;
static uint16_t season_ts[4][256];             // Holodrum's tileset per room in each season
static TileProps props[2][6][256];
static BreakMode *modes[2];
static int n_modes[2];
static Sign *signs;
static int n_signs;
static const TileProps no_props = {0xff, 0, 0, 0xff, 0xff, 0, 0, 0};

bool tiles_load(World *areas, int n) {
  (void)areas; (void)n;
  size_t size;
  Uint8 *d = asset_load("tiles.bin", &size);
  if (!d) { SDL_Log("missing tiles.bin"); return false; }
  const Uint8 *p = d, *end = d + size;
  if (size < 8 || memcmp(p, "OTIL", 4) != 0 || (p[4] | p[5] << 8) != 1) { SDL_free(d); return false; }
  n_tilesets = p[6] | p[7] << 8;
  p += 8;
  tilesets = calloc((size_t)n_tilesets, sizeof *tilesets);
  for (int i = 0; i < n_tilesets && p + 768 <= end; i++, p += 768) {
    for (int k = 0; k < 256; k++) tilesets[i].cell[k] = (uint16_t)(p[k * 2] | p[k * 2 + 1] << 8);
    memcpy(tilesets[i].coll, p + 512, 256);
  }
  for (int s = 0; s < 4 && p + 512 <= end; s++, p += 512)
    for (int k = 0; k < 256; k++) season_ts[s][k] = (uint16_t)(p[k * 2] | p[k * 2 + 1] << 8);
  for (int g = 0; g < 2; g++) {
    if (p + 6 * 256 * 8 + 2 > end) { SDL_free(d); return false; }
    for (int m = 0; m < 6; m++)
      for (int t = 0; t < 256; t++, p += 8) memcpy(&props[g][m][t], p, 8);
    n_modes[g] = p[0] | p[1] << 8;
    p += 2;
    modes[g] = calloc((size_t)n_modes[g] + 1, sizeof *modes[g]);
    for (int i = 0; i < n_modes[g] && p + 7 <= end; i++, p += 7)
      modes[g][i] = (BreakMode){(uint32_t)(p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24), p[4], p[5], p[6]};
  }
  if (p + 2 <= end) {
    n_signs = p[0] | p[1] << 8;
    p += 2;
    signs = calloc((size_t)n_signs + 1, sizeof *signs);
    for (int i = 0; i < n_signs && p + 124 <= end; i++, p += 124) {
      signs[i] = (Sign){p[0], p[1], p[2], p[3], {0}};
      memcpy(signs[i].text, p + 4, 119);
    }
  }
  SDL_free(d);
  return true;
}

const TileProps *tile_props(const World *w, int room, uint8_t mt) {
  if (room < 0) return &no_props;
  int mode = w->coll_mode[room];
  if (mode >= 6) return &no_props;
  return &props[w->id][mode][mt];
}

const TileProps *tile_props_at(const World *w, float px, float py) {
  if (px < 0 || py < 0) return &no_props;
  int room;
  uint8_t mt = world_mt_at(w, (int)px / MT, (int)py / MT, &room);
  return tile_props(w, room, mt);
}

const BreakMode *break_mode(const World *w, int mode) {
  if (mode < 0 || mode >= n_modes[w->id]) return NULL;
  return &modes[w->id][mode];
}

static const Tileset *tileset(int i) { return i >= 0 && i < n_tilesets ? &tilesets[i] : NULL; }

void world_put(World *w, int tx, int ty, uint8_t mt) {
  if (tx < 0 || ty < 0 || tx >= w->base.w || ty >= w->base.h) return;
  int room = (ty / w->base.room_h) * w->rooms_w + tx / w->base.room_w;
  int i = ty * w->base.w + tx;
  const Tileset *ts = tileset(w->tsidx[room]);
  if (!ts) return;
  w->base.cells[i] = ts->cell[mt];
  w->base.coll[i] = ts->coll[mt];
  w->base.mt[i] = mt;
  for (int s = 0; s < 4; s++) {
    if (!w->seasons[s].cells) continue;
    const Tileset *st = tileset(season_ts[s][w->room_ids[room] & 0xff]);
    if (!st) st = ts;
    w->seasons[s].cells[i] = st->cell[mt];
    w->seasons[s].coll[i] = st->coll[mt];
    w->seasons[s].mt[i] = mt;
  }
}

const char *sign_text(const World *w, int tx, int ty) {
  int room;
  world_mt_at(w, tx, ty, &room);
  if (room < 0) return NULL;
  int yx = (ty % w->base.room_h) << 4 | (tx % w->base.room_w);
  for (int i = 0; i < n_signs; i++)
    if (signs[i].game == w->id && signs[i].group == w->group && signs[i].room == w->room_ids[room] && signs[i].yx == yx)
      return signs[i].text;
  return NULL;
}
