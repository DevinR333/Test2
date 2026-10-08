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
  int anim_mode, anim_frame, anim_count;   // the original animation playing (LINK_ANIM_MODE_*)
} Link;

// The originals' animation modes used here (constants/common/linkAnimations.s).
enum { LINK_ANIM_DROWN = 0x0a, LINK_ANIM_SWIM = 0x0b, LINK_ANIM_FALLINHOLE = 0x0d, LINK_ANIM_WALK = 0x10,
       LINK_ANIM_LIFT = 0x12, LINK_ANIM_THROW = 0x16, LINK_ANIM_JUMP = 0x18, LINK_ANIM_SWORD = 0x23,
       LINK_ANIM_GETITEM = 0x0f };

bool link_anims_load(void);
// Switches to an animation (from its start, unless it's already playing).
void link_set_anim(Link *l, int mode);
// Steps the animation a frame (advance false: hold, as Link standing still).
void link_anim_update(Link *l, bool advance);

// Walking speed multiplier (Pegasus Seeds).
extern float link_speed;
// Something besides walls that Link can't walk through (characters); NULL: nothing.
extern bool (*link_blocker)(float x, float y);

// dx, dy: -1, 0 or 1 from the d-pad
void link_update(Link *l, const World *w, int dx, int dy);
// Whether Link's body would hit a wall standing at x, y.
// Whether any side of Link is against a wall there; link_can_move: whether he can take a step some way.
bool link_can_move(const World *w, float x, float y);
bool link_blocked_at(const World *w, float x, float y);
// Moves Link without turning him (knockback); walls still stop him.
void link_push(Link *l, const World *w, float dx, float dy);
// z: height above the ground (jumping); a shadow stays on the ground
void link_draw(SDL_Renderer *ren, const Link *l, const Image *sheet, const View *v, float z);
