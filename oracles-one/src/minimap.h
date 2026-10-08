#pragma once
#include "gfx.h"
#include "world.h"

// The map page: the whole area Link is in, drawn small once (a texture per area) with where he is.
void minimap_draw(SDL_Renderer *ren, const World *w, const Image *atlas, float link_x, float link_y, SDL_FRect box, int blink);
