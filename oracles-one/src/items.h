#pragma once
#include "game.h"
#include "link.h"

// Using items. Every item is the same code in Holodrum and Labrynna: none of them checks which world
// it is in, so Roc's Cape glides over Labrynna's pits and the Magnetic Gloves switch polarity there
// even where nothing is magnetic.

typedef struct {
  float z, vz;              // height above the ground (Roc's Feather / Cape)
  bool cape_glide;          // the cape's second boost has been used
  int sword_frames;         // >0 while swinging
  int magnet_polarity;      // 0 north, 1 south
  char toast[48];           // short message under Link ("SPRING", "N POLARITY"...)
  int toast_frames;
} ItemState;

extern ItemState items;

void items_use(Item item, Link *link, World *world);
// Per frame: gravity, timers. Returns whether Link is in the air (walls still block, pits don't).
bool items_update(void);
void items_draw(SDL_Renderer *ren, const Link *link, const View *v, float hud_px);
// Debug: owns every item of both games.
void items_give_all(void);
