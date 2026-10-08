#pragma once
#include "gfx.h"
#include "link.h"
#include "world.h"

// The objects the originals place in rooms (objects/{game}/*.s): characters (interactions), enemies
// and parts, as tools/extract_objects.py packs them. Enemies run one shared behaviour for now
// (wander, hurt Link on touch, take sword hits, die and drop something); each enemy's own code from
// object_code/ replaces it as it's ported.

enum { ACTOR_INTERACTION, ACTOR_ENEMY, ACTOR_PART, ACTOR_DROP };
// What kind of boss an enemy is (0: not one).
enum { BOSS_NONE, BOSS_MINI, BOSS_DUNGEON, BOSS_ONOX, BOSS_VERAN, BOSS_TWINROVA, BOSS_GANON };
// Drops: what the pickup is.
enum { DROP_HEART = 1, DROP_RUPEE, DROP_CONTAINER, DROP_ESSENCE };

bool actors_load(SDL_Renderer *ren);
// Spawns everything placed in an area (called on entering it).
void actors_enter(const World *areas, int n, const World *w);

typedef struct {
  int damage;        // quarter hearts Link lost this frame (0: none)
  float push_x, push_y;
  int rupees, hearts;  // pickups collected this frame
  int boss;            // BOSS_* beaten this frame
  float boss_x, boss_y;
  bool container, essence;  // a heart container / essence picked up
  int seeds, seed_kind;     // seeds knocked off a seed tree
} ActorEvents;
// Puts a pickup down (heart container, essence) that stays until taken.
void actors_place_pickup(int what, float x, float y);
// Brings in an enemy kind from objects.bin (Ganon when Twinrova falls).
bool actors_spawn(int game, int id, int subid, float x, float y);

// Moves enemies and checks contact with Link; sword is the swing's hit box (w 0: not swinging).
ActorEvents actors_update(const World *w, const Link *link, SDL_FRect sword, int sword_damage);
// Whether a character stands in the way at this pixel (Link walks around them).
bool actors_block(float px, float py);
void actors_draw(SDL_Renderer *ren, const View *v, bool behind_link, float link_y);
// Draws a heart container or essence pickup centred at (cx, cy) (set by main, from the HUD art).
extern void (*pickup_icon)(SDL_Renderer *ren, int what, float cx, float cy, float px);
// The character Link faces, close enough to talk to: what they say and the secret they tell or take.
typedef struct { const char *text; int tell, take; } ActorTalk;
bool actors_talk(const Link *l, ActorTalk *out);
// Drops a heart or rupee (half the time), as a cut bush or beaten enemy does.
void actors_drop(float x, float y);
// Hits every enemy within r of (x, y) (bombs, thrown pots); returns how many.
int actors_hit_area(float x, float y, float r, int damage);
// The enemy within r of (x, y), or -1; and moving it (the Switch Hook swaps places).
int actors_enemy_at(float x, float y, float r);
void actors_get_pos(int i, float *x, float *y);
void actors_set_pos(int i, float x, float y);
// Enemies still up in one screen of the area (shutters and chests wait for them).
int actors_room_enemies(int room);
// For tests: beats every enemy in one screen.
void actors_kill_room(int room);
// For tests: how many enemies are still up.
int actors_enemies_alive(void);
