#pragma once
#include "gfx.h"
#include <stdint.h>

#define MT 16                    // metatile size in world pixels
#define CELL_VOID 0xffff         // unused screen: drawn as nothing, solid

// Which original game a place belongs to (its inventory page, its music, its warp tables).
typedef enum { WORLD_HOLODRUM, WORLD_LABRYNNA, WORLD_COUNT } WorldId;   // Seasons, Ages
typedef enum { SPRING, SUMMER, AUTUMN, WINTER, SEASON_DEFAULT } Season;
typedef enum { AREA_OVERWORLD, AREA_ROOM, AREA_DUNGEON } AreaKind;

typedef struct {
  int w, h;                      // metatiles
  int room_w, room_h;            // metatiles per original screen
  uint16_t *cells;               // atlas index per metatile
  uint8_t *coll;                 // the game's collision byte per metatile
} Map;

// An area from tools/extract_world.py: an overworld, a dungeon floor or a single room, its screens
// laid edge to edge so walking off one just keeps going.
typedef struct {
  WorldId id;                    // the game it is from
  AreaKind kind;
  int group;                     // the original room group
  int rooms_w, rooms_h;
  char name[32];
  uint16_t *room_ids;            // per screen: the original room number, 0xffff none
  uint8_t *coll_mode;            // per screen: the tileset's collision mode (which tiles warp)
  char (*room_name)[32];         // per screen: map-screen label ("Horon Village"), may be empty
  uint8_t *mt;                   // per metatile: the original metatile index (warp tiles)
  Map base;                      // Holodrum: every area in its default season
  Map seasons[4];                // Holodrum only: the whole map in each season (Rod of Seasons)
  int8_t *room_season;           // Holodrum only: per screen, SEASON_DEFAULT or a season set with the rod
  // per screen: what key blocks and doors ($a0) and chests ($f0) turn into, and the dungeon number
  uint16_t *floor_cell, *chest_cell, *closed_chest_cell;
  uint8_t *floor_coll, *chest_coll, *closed_chest_coll, *dungeon;
  uint8_t *state;                // per screen: the season its objects' conditions test (Holodrum)
} World;

// Loads every area of both games; returns how many (0 on failure).
int world_load_all(World **out);
void world_free(World *w);
int world_px_w(const World *w);
int world_px_h(const World *w);
// The overworld of a game (Holodrum, Labrynna's present): its index in the area list.
int world_overworld(const World *areas, int n, WorldId game, int group);

// The collision byte of the metatile under a world pixel (out of bounds: wall).
uint8_t world_collision(const World *w, int px, int py);
// Whether Link can't stand on this pixel (the game's checkGivenCollision_allowHoles).
bool world_solid(const World *w, int px, int py);
// The screen under a world pixel: its index in the area's room grid, or -1.
int world_room_index(const World *w, float px, float py);
// The area name of the screen under a world pixel ("" when unknown).
const char *world_area_name(const World *w, float px, float py);

void world_draw(SDL_Renderer *ren, const World *w, const Image *atlas, const View *v);
// The original metatile under a world pixel (0 outside).
uint8_t world_metatile(const World *w, float px, float py);
// Changes one metatile (in every season of Holodrum too).
void world_set_tile(World *w, int tx, int ty, uint16_t cell, uint8_t coll, uint8_t mt);
// Where a room of a game is: its area and the top-left pixel of that screen.
bool world_find_room(const World *areas, int n, int game, int group, int room, int *area, float *ox, float *oy);

// ---- warps (data/{game}/warpSources.s, warpDestinations.s) -----------------------------------
typedef struct {
  int area;                      // where Link arrives
  float x, y;
} WarpTarget;

bool warps_load(void);
// Whether the metatile under this pixel starts a warp (warpTiles.s for the screen's collision mode).
bool world_on_warp_tile(const World *w, float px, float py);
// The warp for stepping on a warp tile at px, py (positioned warps first, then the screen's own).
bool warp_from_tile(const World *areas, int n, const World *w, float px, float py, WarpTarget *out);
// The warp for walking off the edge of a screen that leads elsewhere (leaving a house).
bool warp_from_edge(const World *areas, int n, const World *w, float px, float py, WarpTarget *out);
// For tests: how many warp sources lead to a room that exists; *total gets the number of sources.
int warps_resolvable(const World *areas, int n, int *total);
