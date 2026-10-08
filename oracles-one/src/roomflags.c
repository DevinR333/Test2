#include "roomflags.h"
#include "game.h"
#include <string.h>

typedef struct { uint8_t n, *pairs; } List;
static int n_tables[2];
static List subst[2][5][8];        // per game, per flag bit (0, 1, 2, 3, 7), per table
static List breaks[2][6];          // per game, per collision mode
static List singles[2][8];         // per game, per group: (room, mask, YX, tile) fours
static Uint8 *blob;

static const int flag_bits[5] = {0, 1, 2, 3, 7};

bool roomflags_load(void) {
  size_t size;
  blob = asset_load("roomflags.bin", &size);
  if (!blob || size < 6 || memcmp(blob, "ORFL", 4) != 0) return false;
  Uint8 *p = blob + 6, *end = blob + size;
#define LIST(l) do { if (p >= end) return false; (l).n = *p++; (l).pairs = p; p += (l).n * 2; if (p > end) return false; } while (0)
  for (int g = 0; g < 2; g++) {
    if (p >= end) return false;
    n_tables[g] = *p++;
    if (n_tables[g] > 8) return false;
    for (int b = 0; b < 5; b++)
      for (int t = 0; t < n_tables[g]; t++) LIST(subst[g][b][t]);
    for (int m = 0; m < 6; m++) LIST(breaks[g][m]);
    for (int gr = 0; gr < 8; gr++) {
      if (p >= end) return false;
      singles[g][gr].n = *p++;
      singles[g][gr].pairs = p;
      p += singles[g][gr].n * 4;
      if (p > end) return false;
    }
  }
#undef LIST
  return true;
}

// flagLocationGroupTable (code/bank0.s): which flag array a group's rooms use
static int flag_array(int which, int group) {
  static const int seasons[8] = {0, 1, 1, 1, 2, 3, 2, 3}, ages[8] = {0, 1, 0, 1, 2, 3, 2, 3};
  return (which == WORLD_LABRYNNA ? ages : seasons)[group & 7];
}

uint8_t *room_flags(int which, int group, int room) {
  return &game.room_flags[which & 1][flag_array(which, group)][room & 0xff];
}

static void put(World *w, int r, int tx, int ty, uint8_t mt) {
  // an opened door's floor: the room's own floor tile, as key doors open to
  if (mt == 0xa0 && w->floor_cell[r] != CELL_VOID) world_set_tile(w, tx, ty, w->floor_cell[r], w->floor_coll[r], 0xa0);
  else world_put(w, tx, ty, mt);
}

static void apply_room(World *w, int r) {
  int room = w->room_ids[r];
  if (room == 0xffff) return;
  int g = w->id & 1;
  uint8_t f = *room_flags(w->id, w->group, room);
  int x0 = (r % w->rooms_w) * w->base.room_w, y0 = (r / w->rooms_w) * w->base.room_h;
  // applySingleTileChanges (code/commonTileSubstitutions.s): one tile, when the room's flags meet
  // the mask; Ages also has unlinked-only ($f0), linked-only ($f1) and finished-game ($f2) changes
  const List *sl = &singles[g][w->group & 7];
  for (int i = 0; i < sl->n; i++) {
    const uint8_t *c = sl->pairs + i * 4;
    if (c[0] != (room & 0xff)) continue;
    bool on = g == WORLD_LABRYNNA && c[1] == 0xf0 ? !game.linked
            : g == WORLD_LABRYNNA && c[1] == 0xf1 ? game.linked
            : g == WORLD_LABRYNNA && c[1] == 0xf2 ? game.ganon_beaten
            : (c[1] & f) != 0;
    if (on && (c[2] & 15) < w->base.room_w && (c[2] >> 4) < w->base.room_h) put(w, r, x0 + (c[2] & 15), y0 + (c[2] >> 4), c[3]);
  }
  if (!f) return;
  // Seasons picks the table by group, Ages by the room's collision mode
  int t = g == WORLD_LABRYNNA ? w->coll_mode[r] & 7 : w->group & 7;
  if (t >= n_tables[g]) return;
  for (int b = 0; b < 5; b++) {
    if (!(f >> flag_bits[b] & 1)) continue;
    const List *l = &subst[g][b][t];
    for (int i = 0; i < l->n; i++) {
      uint8_t to = l->pairs[i * 2], from = l->pairs[i * 2 + 1];
      for (int y = y0; y < y0 + w->base.room_h; y++)
        for (int x = x0; x < x0 + w->base.room_w; x++)
          if (w->base.mt[y * w->base.w + x] == from) put(w, r, x, y, to);
    }
  }
}

void roomflags_visit(World *w, float px, float py) {
  int r = world_room_index(w, px, py);
  if (r >= 0 && w->room_ids[r] != 0xffff) *room_flags(w->id, w->group, w->room_ids[r]) |= 0x10;   // ROOMFLAG_VISITED
}

void roomflags_apply(World *w) {
  for (int r = 0; r < w->rooms_w * w->rooms_h; r++) apply_room(w, r);
}

// _adjacentRoomsData: (flag here, room step in the dungeon map, flag there) per door direction
static const uint8_t adjacent[4][3] = {{0x01, 0xf8, 0x04}, {0x02, 0x01, 0x08}, {0x04, 0x08, 0x01}, {0x08, 0xff, 0x02}};

static int room_at(const World *w, int tx, int ty) {
  if (tx < 0 || ty < 0 || tx >= w->base.w || ty >= w->base.h) return -1;
  return (ty / w->base.room_h) * w->rooms_w + tx / w->base.room_w;
}

// A door between two rooms of a dungeon: both get their side marked. Our dungeon floors are laid out
// as the dungeon's map is, so the room behind is the next screen that way.
static void door_flags(World *w, int r, int dir) {
  static const int dx[4] = {0, 1, 0, -1}, dy[4] = {-1, 0, 1, 0};
  *room_flags(w->id, w->group, w->room_ids[r]) |= adjacent[dir][0];
  int rx = r % w->rooms_w + dx[dir], ry = r / w->rooms_w + dy[dir];
  if (rx < 0 || ry < 0 || rx >= w->rooms_w || ry >= w->rooms_h) return;
  int r2 = ry * w->rooms_w + rx;
  if (w->room_ids[r2] == 0xffff) return;
  *room_flags(w->id, w->group, w->room_ids[r2]) |= adjacent[dir][2];
  apply_room(w, r2);
}

void roomflags_key_door(World *w, int tx, int ty, int dir) {
  int r = room_at(w, tx, ty);
  if (r < 0 || w->room_ids[r] == 0xffff) return;
  if (w->kind == AREA_DUNGEON) door_flags(w, r, dir & 3);
  else *room_flags(w->id, w->group, w->room_ids[r]) |= adjacent[dir & 3][0];   // outside dungeons: this room only
  apply_room(w, r);
}

void roomflags_tile_broken(World *w, int tx, int ty, uint8_t tile) {
  int r = room_at(w, tx, ty);
  if (r < 0 || w->room_ids[r] == 0xffff) return;
  int g = w->id & 1, mode = w->coll_mode[r] % 6;
  const List *l = &breaks[g][mode];
  for (int i = 0; i < l->n; i++) {
    if (l->pairs[i * 2] != tile) continue;
    uint8_t v = l->pairs[i * 2 + 1];
    int room = w->room_ids[r];
    if (v & 0x80) {
      if (w->kind == AREA_DUNGEON) door_flags(w, r, (v & 0x0f) >> 2);
      else *room_flags(w->id, w->group, room) |= adjacent[(v & 0x0f) >> 2][0];
    } else if (v & 0x40) {
      // setRoomFlagsForUnlockedKeyDoor_overworldOnly: this room and the one the table's step names
      const uint8_t *a = adjacent[(v & 0x0f) >> 2];
      *room_flags(w->id, w->group, room) |= a[0];
      *room_flags(w->id, w->group, (room + a[1]) & 0xff) |= a[2];
    } else {
      *room_flags(w->id, w->group, room) |= (uint8_t)(1 << (v & 7));
    }
    apply_room(w, r);
    return;
  }
}
