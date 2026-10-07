#pragma once
#include "gfx.h"
#include <stdint.h>

#define MT 16                    // metatile size in world pixels
#define CELL_VOID 0xffff         // unused screen: drawn as nothing, solid

typedef enum { WORLD_HOLODRUM, WORLD_LABRYNNA, WORLD_COUNT } WorldId;
typedef enum { SPRING, SUMMER, AUTUMN, WINTER, SEASON_DEFAULT } Season;

// One overworld from tools/extract_world.py: rooms laid edge to edge, so walking off a screen just
// keeps going.
typedef struct {
  int w, h;                      // metatiles
  int room_w, room_h;            // metatiles per original screen
  uint16_t *cells;               // atlas index per metatile
  uint8_t *coll;                 // the game's collision byte per metatile
} Map;

typedef struct {
  WorldId id;
  const char *name;
  Map base;                      // Holodrum: every area in its default season
  Map seasons[4];                // Holodrum only: the whole map in each season (Rod of Seasons)
  int8_t *room_season;           // Holodrum only: per screen, SEASON_DEFAULT or a season set with the rod
  char (*area_name)[32];         // per screen, from the map screen's labels ("Horon Village")
} World;

bool world_load(World *w, WorldId id);
void world_free(World *w);
int world_px_w(const World *w);
int world_px_h(const World *w);

// The collision byte of the metatile under a world pixel (out of bounds: wall).
uint8_t world_collision(const World *w, int px, int py);
// Whether Link can't stand on this pixel (the game's checkGivenCollision_allowHoles).
bool world_solid(const World *w, int px, int py);

// The area name of the screen under a world pixel ("" when unknown).
const char *world_area_name(const World *w, float px, float py);

void world_draw(SDL_Renderer *ren, const World *w, const Image *atlas, const View *v);
