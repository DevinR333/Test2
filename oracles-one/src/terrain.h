#pragma once
#include "game.h"
#include "gfx.h"
#include "link.h"
#include "world.h"

// What Link does with the ground itself, from the originals' tile tables (data/{game}/tile_properties):
// cutting grass and bushes, lifting and throwing pots and rocks, jumping down ledges, falling into
// holes, drowning or swimming, lava, pushing blocks, reading signs, bombs, digging and burning.
// None of it asks which world it is in, so every item breaks the same things in both.

typedef struct {
  int damage;          // quarter hearts Link lost this frame
  const char *message; // text to show (a sign)
} TerrainEvents;

// Entering an area: what was cut or lifted elsewhere grows back.
void terrain_enter(World *areas, int n, World *w, const Link *l);
// Whether Link is busy (jumping off a ledge, falling): no walking, no items.
bool terrain_busy(void);
// Height above the ground while jumping off a ledge.
float terrain_z(void);
bool terrain_swimming(void);
bool terrain_carrying(void);
// Frames left of falling into a hole or water (0: not falling).
int terrain_falling(void);
bool terrain_drowning(void);
// Draws an item's icon (set by main: the bomb Link puts down).
extern void (*terrain_icon)(SDL_Renderer *ren, Item item, float x, float y, float px);

// Set by the game loop each frame: Link's height (jumping clears holes and water) and the companion
// he rides (-1 none, 0 Ricky, 1 Dimitri, 2 Moosh).
extern float terrain_airborne;
extern int terrain_companion;
// The Cane of Somaria's block: whether it stands at this pixel (Link can't walk through it).
bool terrain_block(float x, float y);
// The Magnetic Gloves: pulls Link to (north) or pushes him off (south) a magnet in front of him.
void terrain_magnet(World *w, Link *l, int polarity);

// The sword's swing cuts what it touches.
void terrain_sword(World *areas, int n, World *w, SDL_FRect box, int level);
// Items that act on tiles; returns true when it did something (so the item's own effect is skipped).
bool terrain_use(Item item, World *areas, int n, World *w, Link *l);
// Something flying or dropped hits the tile at (x, y): breaks it if `source` breaks it there.
bool terrain_hit_tile(World *areas, World *w, float x, float y, int source);
// A pressed in front of a sign: its text, or NULL.
const char *terrain_read(World *w, const Link *l);
// Per frame, after Link moved: ledges, hazards, pushing, things in flight, bombs.
TerrainEvents terrain_update(World *areas, int n, World *w, Link *l, int dx, int dy);
// above: the things drawn over Link (carried and flying), else the ones under him.
void terrain_draw(SDL_Renderer *ren, const Image *atlas, const View *v, const Link *l, bool above);
