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
  uint8_t *mt;                   // the original metatile index per metatile
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
  uint8_t *mt;                   // per metatile: the original metatile index (= base.mt)
  Map base;                      // Holodrum: every area in its default season
  Map seasons[4];                // Holodrum only: the whole map in each season (Rod of Seasons)
  int8_t *room_season;           // Holodrum only: per screen, SEASON_DEFAULT or a season set with the rod
  // per screen: what key blocks and doors ($a0) and chests ($f0) turn into, and the dungeon number
  uint16_t *floor_cell, *chest_cell, *closed_chest_cell;
  uint8_t *floor_coll, *chest_coll, *closed_chest_coll, *dungeon;
  uint8_t *state;                // per screen: the season its objects' conditions test (Holodrum)
  uint16_t *tsidx;               // per screen: its tileset in tiles.bin (what tiles turn into)
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
// Changes one metatile to another of the room's own tileset (in each season's tileset in Holodrum).
void world_put(World *w, int tx, int ty, uint8_t mt);
// The original metatile at a metatile position, and which screen it's on (-1 outside).
uint8_t world_mt_at(const World *w, int tx, int ty, int *room);
// The atlas cell drawn at a metatile position.
uint16_t world_cell_at(const World *w, int tx, int ty);

// ---- tile properties (tiles.bin: data/{game}/tile_properties) ----------------------------------
typedef struct {
  uint8_t cliff;                 // ANGLE_* Link can jump off it towards ($ff: not a cliff)
  uint8_t hazard;                // 1 water, 2 hole, 4 lava
  uint8_t type;                  // TILETYPE_* (tileTypes.s)
  uint8_t breakable;             // breakable mode ($ff: no)
  uint8_t interact;              // interactableTiles.s byte ($ff: none)
  uint8_t push_under, push_becomes;
  uint8_t pad;
} TileProps;
typedef struct { uint32_t sources; uint8_t drop, flags, result; } BreakMode;
enum { TT_HOLE = 1, TT_WARPHOLE, TT_CRACKEDFLOOR, TT_VINES, TT_GRASS, TT_STAIRS, TT_WATER, TT_STUMP,
       TT_UPCONVEYOR, TT_RIGHTCONVEYOR, TT_DOWNCONVEYOR, TT_LEFTCONVEYOR, TT_SPIKE, TT_CRACKED_ICE, TT_ICE,
       TT_LAVA, TT_PUDDLE, TT_UPCURRENT, TT_RIGHTCURRENT, TT_DOWNCURRENT, TT_LEFTCURRENT, TT_RAISABLE_FLOOR,
       TT_SEAWATER, TT_WHIRLPOOL };
enum { BREAK_BRACELET, BREAK_SWORD_L1, BREAK_SWORD_L2, BREAK_EXPERTS_RING, BREAK_BOMB, BREAK_LANDED, BREAK_SHOVEL,
       BREAK_SWITCH_HOOK = 8, BREAK_EMBER = 12, BREAK_GALE = 13 };

bool tiles_load(World *areas, int n);
// The properties of the tile under a world pixel (all clear outside the map).
const TileProps *tile_props_at(const World *w, float px, float py);
const TileProps *tile_props(const World *w, int room, uint8_t mt);
const BreakMode *break_mode(const World *w, int mode);
// The sign text at a metatile, or NULL.
const char *sign_text(const World *w, int tx, int ty);
// Where a room of a game is: its area and the top-left pixel of that screen.
bool world_find_room(const World *areas, int n, int game, int group, int room, int *area, float *ox, float *oy);

// ---- warps (data/{game}/warpSources.s, warpDestinations.s) -----------------------------------
typedef struct {
  int area;                      // where Link arrives
  float x, y;
  int transition, param;         // the destination's TRANSITION_DEST_* and its parameter (constants/common/transitions.s)
  int walk_frames, walk_dir;     // TRANSITION_DEST_ENTERSCREEN: Link walks in this many frames (0 none)
} WarpTarget;

enum { TRANSITION_DEST_BASIC = 0, TRANSITION_DEST_SET_RESPAWN = 1, TRANSITION_DEST_ENTERSCREEN = 3,
       TRANSITION_DEST_DONT_SET_RESPAWN = 4, TRANSITION_DEST_FALL = 5, TRANSITION_DEST_X_SHIFTED = 14 };

bool warps_load(void);
// Whether the metatile under this pixel starts a warp (warpTiles.s for the screen's collision mode).
bool world_on_warp_tile(const World *w, float px, float py);
// The warp for stepping on a warp tile at px, py (positioned warps first, then the screen's own).
bool warp_from_tile(const World *areas, int n, const World *w, float px, float py, WarpTarget *out);
// The warp for walking off the edge of a screen that leads elsewhere (leaving a house).
bool warp_from_edge(const World *areas, int n, const World *w, float px, float py, WarpTarget *out);
// For tests: how many warp sources lead to a room that exists; *total gets the number of sources.
int warps_resolvable(const World *areas, int n, int *total);
// Where every warp leads (for checks): fills out, returns how many.
int warps_targets(const World *areas, int n, WarpTarget *out, int max);
