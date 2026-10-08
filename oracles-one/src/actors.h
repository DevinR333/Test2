#pragma once
#include "gfx.h"
#include "link.h"
#include "world.h"

// The objects the originals place in rooms (objects/{game}/*.s): characters (interactions), enemies
// and parts, as tools/extract_objects.py packs them. Enemies run one shared behaviour for now
// (wander, hurt Link on touch, take sword hits, die and drop something); each enemy's own code from
// object_code/ replaces it as it's ported.

enum { ACTOR_INTERACTION, ACTOR_ENEMY, ACTOR_PART, ACTOR_DROP };

bool actors_load(SDL_Renderer *ren);
// Spawns everything placed in an area (called on entering it).
void actors_enter(const World *areas, int n, const World *w);

typedef struct {
  int damage;        // quarter hearts Link lost this frame (0: none)
  float push_x, push_y;
  int rupees, hearts;  // pickups collected this frame
} ActorEvents;

// Moves enemies and checks contact with Link; sword is the swing's hit box (w 0: not swinging).
ActorEvents actors_update(const World *w, const Link *link, SDL_FRect sword, int sword_damage);
// Whether a character stands in the way at this pixel (Link walks around them).
bool actors_block(float px, float py);
void actors_draw(SDL_Renderer *ren, const View *v, bool behind_link, float link_y);
// The character Link faces, close enough to talk to: what they say and the secret they tell or take.
typedef struct { const char *text; int tell, take; } ActorTalk;
bool actors_talk(const Link *l, ActorTalk *out);
// Drops a heart or rupee (half the time), as a cut bush or beaten enemy does.
void actors_drop(float x, float y);
// Hits every enemy within r of (x, y) (bombs, thrown pots); returns how many.
int actors_hit_area(float x, float y, float r, int damage);
// For tests: how many enemies are still up.
int actors_enemies_alive(void);
