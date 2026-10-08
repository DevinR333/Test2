#pragma once
#include "gfx.h"
#include "link.h"

// Ricky, Dimitri and Moosh under Link while he rides them (companions.bin / companions.rgba from
// their animation tables in data/seasons/specialObjectAnimationData.s).
bool companion_load(SDL_Renderer *ren);
// Per frame while riding: which one, and whether they're moving.
void companion_update(int which, const Link *l);
// Draws the companion; *rider_dy gets how far above the ground Link sits.
void companion_draw(SDL_Renderer *ren, const View *v, int which, const Link *l, float *rider_dy);
