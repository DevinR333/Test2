#pragma once
#include "game.h"
#include "link.h"

// Using items. Every item is the same code in Holodrum and Labrynna: none of them checks which world
// it is in, so Roc's Cape glides over Labrynna's pits and the Magnetic Gloves switch polarity there
// even where nothing is magnetic.

enum { SEED_EMBER, SEED_SCENT, SEED_PEGASUS, SEED_GALE, SEED_MYSTERY, SEED_KINDS };

// What an item asks of the game loop (things items.c can't do itself).
typedef enum { REQ_NONE, REQ_TIME_TRAVEL, REQ_GALE } ItemRequest;

typedef struct {
  float x, y, vx, vy;
  int t, kind;              // kind: an Item (boomerang, slingshot...) or ITEM_NONE for a dropped seed
  int seed;                 // SEED_* for seeds
  bool returning;           // the boomerang on its way back
  float travelled, range;
  bool live;
} Shot;

typedef struct {
  float z, vz;              // height above the ground (Roc's Feather / Cape)
  bool cape_glide;          // the cape's second boost has been used
  int sword_frames;         // >0 while swinging
  int magnet_polarity;      // 0 north, 1 south
  char toast[48];           // short message under Link ("SPRING", "N POLARITY"...)
  int toast_frames;
  int pegasus_frames;       // >0: running fast (Pegasus Seeds)
  Shot shots[6];
  ItemRequest request;
  int hook_swap;            // >0: the Switch Hook caught something (index+1 into shots)
} ItemState;

extern ItemState items;

void items_use(Item item, Link *link, World *world);
// Per frame: gravity, timers, things in flight. Returns whether Link is in the air.
bool items_update(World *areas, World *world, Link *link);
void items_draw(SDL_Renderer *ren, const Link *link, const View *v, float hud_px);
// Debug: owns every item of both games.
void items_give_all(void);
// Draws an item's icon turned by `angle` degrees around its centre (set by main: the sword).
extern void (*items_icon)(SDL_Renderer *ren, Item item, float cx, float cy, float px, double angle);
// Seed satchel and shooters: which seed they use (A/B on the item in the menu cycles it).
void items_next_seed(void);
const char *seed_name(int seed);
