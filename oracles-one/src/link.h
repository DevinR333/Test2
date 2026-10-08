#pragma once
#include "gfx.h"
#include "world.h"

enum { DIR_UP, DIR_RIGHT, DIR_DOWN, DIR_LEFT };

typedef struct {
  float x, y;          // world pixels: the middle of Link's 16x16 sprite
  int dir;
  int walk_frames;     // frames spent walking, drives the step animation
  bool moving;
  int pushing;         // frames spent walking into a wall
} Link;

// Something besides walls that Link can't walk through (characters); NULL: nothing.
extern bool (*link_blocker)(float x, float y);

// dx, dy: -1, 0 or 1 from the d-pad
void link_update(Link *l, const World *w, int dx, int dy);
// Whether Link's body would hit a wall standing at x, y.
bool link_blocked_at(const World *w, float x, float y);
// Moves Link without turning him (knockback); walls still stop him.
void link_push(Link *l, const World *w, float dx, float dy);
// z: height above the ground (jumping); a shadow stays on the ground
void link_draw(SDL_Renderer *ren, const Link *l, const Image *sheet, const View *v, float z);
