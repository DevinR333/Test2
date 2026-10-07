#include "world.h"
#include <stdlib.h>
#include <string.h>

static bool map_load(Map *m, const char *name) {
  size_t size;
  Uint8 *d = asset_load(name, &size);
  if (!d) { SDL_Log("missing map %s", name); return false; }
  Uint16 hdr[5];
  memcpy(hdr, d + 4, sizeof hdr);
  if (size < 14 || memcmp(d, "OWLD", 4) != 0 || SDL_Swap16LE(hdr[0]) != 1) { SDL_Log("%s is not a version 1 map", name); SDL_free(d); return false; }
  m->w = SDL_Swap16LE(hdr[1]);
  m->h = SDL_Swap16LE(hdr[2]);
  m->room_w = SDL_Swap16LE(hdr[3]);
  m->room_h = SDL_Swap16LE(hdr[4]);
  size_t n = (size_t)m->w * m->h;
  if (size < 14 + n * 3) { SDL_Log("%s is truncated", name); SDL_free(d); return false; }
  m->cells = malloc(n * 2);
  m->coll = malloc(n);
  for (size_t i = 0; i < n; i++) m->cells[i] = (uint16_t)(d[14 + i * 2] | d[15 + i * 2] << 8);
  memcpy(m->coll, d + 14 + n * 2, n);
  SDL_free(d);
  return true;
}

static void names_load(World *w, const char *file) {
  int rooms = (w->base.w / w->base.room_w) * (w->base.h / w->base.room_h);
  w->area_name = calloc((size_t)rooms, sizeof *w->area_name);
  size_t size;
  char *d = asset_load(file, &size);
  if (!d) return;
  size_t at = 0;
  for (int r = 0; r < rooms && at < size; r++) {
    size_t n = 0;
    while (at < size && d[at] != '\n') {
      if (n + 1 < sizeof w->area_name[r]) w->area_name[r][n++] = d[at];
      at++;
    }
    at++;
  }
  SDL_free(d);
}

const char *world_area_name(const World *w, float px, float py) {
  if (!w->area_name || px < 0 || py < 0) return "";
  int rx = (int)px / (w->base.room_w * MT), ry = (int)py / (w->base.room_h * MT);
  int cols = w->base.w / w->base.room_w;
  if (rx >= cols || ry >= w->base.h / w->base.room_h) return "";
  return w->area_name[ry * cols + rx];
}

bool world_load(World *w, WorldId id) {
  memset(w, 0, sizeof *w);
  w->id = id;
  if (id == WORLD_LABRYNNA) {
    w->name = "LABRYNNA";
    if (!map_load(&w->base, "labrynna.map")) return false;
    names_load(w, "labrynna.names");
    return true;
  }
  w->name = "HOLODRUM";
  static const char *files[4] = {"holodrum_spring.map", "holodrum_summer.map", "holodrum_autumn.map", "holodrum_winter.map"};
  if (!map_load(&w->base, "holodrum.map")) return false;
  names_load(w, "holodrum.names");
  for (int s = 0; s < 4; s++) if (!map_load(&w->seasons[s], files[s])) return false;
  int rooms = (w->base.w / w->base.room_w) * (w->base.h / w->base.room_h);
  w->room_season = malloc((size_t)rooms);
  memset(w->room_season, SEASON_DEFAULT, (size_t)rooms);
  return true;
}

void world_free(World *w) {
  free(w->base.cells); free(w->base.coll);
  for (int s = 0; s < 4; s++) { free(w->seasons[s].cells); free(w->seasons[s].coll); }
  free(w->room_season);
  free(w->area_name);
  memset(w, 0, sizeof *w);
}

int world_px_w(const World *w) { return w->base.w * MT; }
int world_px_h(const World *w) { return w->base.h * MT; }

// The map a metatile is read from: the base map, or a season layer where the rod changed it.
static const Map *map_at(const World *w, int tx, int ty) {
  if (!w->room_season) return &w->base;
  int room = (ty / w->base.room_h) * (w->base.w / w->base.room_w) + tx / w->base.room_w;
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
