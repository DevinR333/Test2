#include "actors.h"
#include "game.h"
#include "audio.h"
#include <stdlib.h>
#include <string.h>

#define MAX_FRAMES 4
#define MAX_ACTORS 768
#define ACTIVE_RANGE 260.0f      // enemies further than this from Link wait (about a screen and a half)

typedef struct { uint16_t x, y; uint8_t w, h; int16_t ox, oy; uint8_t dur; } Frame;
typedef struct {
  uint8_t game, kind, id, subid;
  uint8_t radius_y, radius_x;
  int8_t damage;
  uint8_t health;
  int n_frames;
  Frame frames[MAX_FRAMES];
  uint8_t tell, take;            // linked secret told / taken ($ff none)
  char *text;                    // what the character says
  uint8_t vis_type;              // when shown: 0 always, 1 Horon stages (mask), 2 a Sunken City stage, 3 from an Ages progress
  uint16_t vis;
  uint8_t prog_fn, prog_off, n_ptexts;   // Ages: what they say at each progress (getGameProgress_1/_2)
  char *ptexts[8];
  int n_gifts;
  ActorGift gifts[4];
} Kind;
typedef struct { uint8_t game, group, room, kind, id, subid, y, x, count, random, cond; } Placement;

typedef struct {
  const Kind *k;
  int kind, boss, room, behave, state, st;
  float z, vz;
  bool hidden, invincible;
  float x, y, vx, vy;
  int hp, timer, hurt, anim;
  bool alive;
} Actor;

static Image sheet;
static Kind *kinds;
static Placement *places;
static int n_kinds, n_places;
static Actor actors[MAX_ACTORS];
static int n_actors;
static Uint32 rng = 12345;
enum { SHOT_ROCK, SHOT_ARROW, SHOT_FIRE };
#define MAX_SHOTS 24
typedef struct { bool live; float x, y, vx, vy; int t, kind; } EShot;
static EShot eshots[MAX_SHOTS];
enum { B_WALK, B_SHOOT, B_TURRET, B_FLY, B_HOP, B_CHARGE, B_BURROW, B_HARMLESS, B_SEEDTREE, B_BOSS };
static int pending_boss;                // a boss beaten by a bomb or seed, reported on the next update
static float pending_x, pending_y;

static float frand(void) { rng = rng * 1664525u + 1013904223u; return (float)(rng >> 8) / (float)(1u << 24); }

bool actors_load(SDL_Renderer *ren) {
  if (!image_load(ren, "sprites.rgba", &sheet)) return false;
  size_t size;
  Uint8 *d = asset_load("objects.bin", &size);
  if (!d || size < 10 || memcmp(d, "OOBJ", 4) != 0 || (d[4] | d[5] << 8) != 4) { SDL_free(d); return false; }
  n_kinds = d[6] | d[7] << 8;
  n_places = d[8] | d[9] << 8;
  kinds = calloc((size_t)n_kinds, sizeof *kinds);
  places = calloc((size_t)n_places, sizeof *places);
  const Uint8 *p = d + 10, *end = d + size;
  for (int i = 0; i < n_kinds && p + 9 <= end; i++) {
    Kind *k = &kinds[i];
    k->game = p[0]; k->kind = p[1]; k->id = p[2]; k->subid = p[3];
    k->radius_y = p[4]; k->radius_x = p[5]; k->damage = (int8_t)p[6]; k->health = p[7];
    k->n_frames = p[8];
    p += 9;
    if (p + 4 > end) break;
    k->tell = p[0]; k->take = p[1];
    int tl = p[2] | p[3] << 8;
    p += 4;
    if (p + tl > end) break;
    k->text = calloc((size_t)tl + 1, 1);
    memcpy(k->text, p, (size_t)tl);
    p += tl;
    if (p + 6 > end) break;
    k->vis_type = p[0];
    k->vis = (uint16_t)(p[1] | p[2] << 8);
    k->prog_fn = p[3]; k->prog_off = p[4]; k->n_ptexts = p[5];
    p += 6;
    for (int t = 0; t < k->n_ptexts && t < 8; t++) {
      if (p + 2 > end) break;
      int l2 = p[0] | p[1] << 8;
      p += 2;
      if (p + l2 > end) break;
      k->ptexts[t] = calloc((size_t)l2 + 1, 1);
      memcpy(k->ptexts[t], p, (size_t)l2);
      p += l2;
    }
    if (p >= end) break;
    int ng = *p++;
    for (int gi = 0; gi < ng && p + 4 <= end; gi++) {
      int t = p[0], prm = p[1], l3 = p[2] | p[3] << 8;
      p += 4;
      if (p + l3 > end) break;
      if (gi < 4) {
        ActorGift *g = &k->gifts[k->n_gifts++];
        g->treasure = t; g->param = prm;
        g->text = calloc((size_t)l3 + 1, 1);
        memcpy(g->text, p, (size_t)l3);
      }
      p += l3;
    }
    for (int f = 0; f < k->n_frames && p + 11 <= end; f++, p += 11) {
      Frame *fr = &k->frames[f];
      fr->x = (uint16_t)(p[0] | p[1] << 8); fr->y = (uint16_t)(p[2] | p[3] << 8);
      fr->w = p[4]; fr->h = p[5];
      fr->ox = (int16_t)(p[6] | p[7] << 8); fr->oy = (int16_t)(p[8] | p[9] << 8);
      fr->dur = p[10];
    }
  }
  for (int i = 0; i < n_places && p + 11 <= end; i++, p += 11) memcpy(&places[i], p, 11);
  SDL_free(d);
  return true;
}

// Where an enemy's body is: the middle of its first frame, and half its size (at least the data's
// collision radius), so big bosses can be hit where they're drawn.
static void body(const Actor *a, float *cx, float *cy, float *rx, float *ry) {
  *rx = a->k && a->k->radius_x ? a->k->radius_x : 6;
  *ry = a->k && a->k->radius_y ? a->k->radius_y : 6;
  *cx = a->x; *cy = a->y;
  if (!a->k || !a->k->n_frames) return;
  const Frame *f = &a->k->frames[0];
  if (f->w > 20 || f->h > 20) {
    *cx = a->x + f->ox + f->w / 2.0f;
    *cy = a->y + f->oy + f->h / 2.0f;
    *rx = SDL_max(*rx, f->w / 2.0f - 4);
    *ry = SDL_max(*ry, f->h / 2.0f - 4);
  }
}

static const Kind *find_kind(int game, int kind, int id, int subid) {
  for (int i = 0; i < n_kinds; i++)
    if (kinds[i].game == game && kinds[i].kind == kind && kinds[i].id == id && kinds[i].subid == subid) return &kinds[i];
  return NULL;
}

static bool walkable(const World *w, float x, float y) {
  return !world_solid(w, (int)x - 4, (int)y) && !world_solid(w, (int)x + 3, (int)y) && !world_solid(w, (int)x, (int)y + 4);
}

// The originals' bosses by enemy id: each dungeon's boss gives its essence and a heart container.
static int boss_kind(int game, int id) {
  if (id == 0x01 || id == 0x03) return BOSS_TWINROVA;
  if (id == 0x04) return BOSS_GANON;
  if (game == WORLD_HOLODRUM && (id == 0x02 || id == 0x05)) return BOSS_ONOX;
  if (game == WORLD_LABRYNNA && id == 0x02) return BOSS_VERAN;
  if (game == WORLD_HOLODRUM && (id == 0x06 || (id >= 0x78 && id <= 0x7d) || id == 0x7f)) return BOSS_DUNGEON;   // Gleeok, Aquamentus..Manhandla, Medusa Head
  if (game == WORLD_LABRYNNA && (id == 0x07 || (id >= 0x78 && id <= 0x7e))) return BOSS_DUNGEON;   // Ramrock, Pumpkin Head..Plasmarine
  if (id >= 0x70 && id <= 0x7f) return BOSS_MINI;
  return BOSS_NONE;
}

static int behaviour_of(int id);

static void setup_enemy(Actor *a, const Kind *k, int game) {
  a->boss = boss_kind(game, k->id);
  a->behave = behaviour_of(k->id);
  a->hp = k->health ? (k->health == 0x7f ? 0 : k->health) : 1;
  // traps, turrets and seed trees the sword can't beat
  if (!a->hp && (a->behave == B_CHARGE || a->behave == B_TURRET || a->behave == B_SEEDTREE)) { a->hp = 999; a->invincible = true; }
  if (a->behave == B_SEEDTREE) a->hp = 999;
  if (a->behave == B_BURROW) { a->hidden = true; a->timer = 30; }
  // bosses whose health the originals keep in their own code get a fight's worth here
  if (a->boss && a->hp < 12) a->hp = a->boss == BOSS_MINI ? 16 : 32;
  if (a->boss >= BOSS_ONOX && a->hp < 48) a->hp = 48;
}

bool actors_spawn(int game, int id, int subid, float x, float y) {
  const Kind *k = NULL;
  for (int i = 0; i < n_kinds; i++)
    if (kinds[i].game == game && kinds[i].kind == ACTOR_ENEMY && kinds[i].id == id && kinds[i].subid == subid) { k = &kinds[i]; break; }
  if (!k || n_actors >= MAX_ACTORS) return false;
  Actor *a = &actors[n_actors++];
  memset(a, 0, sizeof *a);
  a->k = k;
  a->kind = ACTOR_ENEMY;
  a->room = -1;
  a->x = x; a->y = y;
  setup_enemy(a, k, game);
  a->alive = true;
  return true;
}

// ---- the story so far, as the originals' NPCs judge it ---------------------------------------
static int essence_count(int g) { int n = 0; for (int i = 0; i < 8; i++) n += game.essences[g] >> i & 1; return n; }
static int essence_highest(int g) { for (int i = 7; i >= 0; i--) if (game.essences[g] >> i & 1) return i; return -1; }

// miscNpcs.s checkNPCStage (a normal game): Horon Village's people come and go by it.
static int seasons_stage(void) {
  if (game.onox_beaten) return 10;
  int h = essence_highest(WORLD_HOLODRUM);
  if (h < 0) return game.item_level[ITEM_SWORD] ? 1 : 0;   // met the Maku Tree (who gives the Gnarled Key)
  if (h >= 7) return 5;
  if (essence_count(WORLD_HOLODRUM) >= 5) return 4;
  return h >= 1 ? 3 : 2;
}

// getSunkenCityNPCVisibleSubId: the Sunken City's divers' son and treasure hunter.
static int sunken_stage(void) {
  if (game.onox_beaten) return 4;
  int h = essence_highest(WORLD_HOLODRUM);
  if (h < 0) return 0;
  return h >= 7 ? 2 : h >= 3 ? 1 : 0;
}

// Ages' getGameProgress_1 / _2 (miscMan2.s): beaten dungeons, Nayru saved, the Maku Seed, the end.
static int ages_progress(int fn) {
  int h = essence_highest(WORLD_LABRYNNA);
  bool done = game.veran_beaten, seed = essence_count(WORLD_LABRYNNA) == 8, nayru = h >= 3;
  if (fn == 1) {
    if (done) return 5;
    if (seed) return 4;
    if (h >= 6) return 3;
    if (nayru) return 2;
    return h >= 2 ? 1 : 0;
  }
  if (done) return 7;
  if (seed) return 5;
  if (h >= 6) return 4;
  if (nayru) return 3;
  if (h >= 3) return 2;
  return h >= 1 ? 1 : 0;
}

static bool shown_now(const Kind *k) {
  switch (k->vis_type) {
  case 1: return k->vis >> seasons_stage() & 1;
  case 2: return sunken_stage() == k->vis;
  case 3: return ages_progress(k->prog_fn ? k->prog_fn : 1) >= k->vis;
  default: return true;
  }
}

static const char *say(const Kind *k) {
  if (k->prog_fn && k->n_ptexts) {
    int i = ages_progress(k->prog_fn) - k->prog_off;
    i = i < 0 ? 0 : i >= k->n_ptexts ? k->n_ptexts - 1 : i;
    if (k->ptexts[i] && k->ptexts[i][0]) return k->ptexts[i];
  }
  return k->text ? k->text : "";
}

void actors_enter(const World *areas, int n, const World *w) {
  (void)areas; (void)n;
  n_actors = 0;
  memset(eshots, 0, sizeof eshots);
  for (int r = 0; r < w->rooms_w * w->rooms_h; r++) {
    int room = w->room_ids[r];
    if (room == 0xffff) continue;
    float ox = (float)((r % w->rooms_w) * w->base.room_w * MT), oy = (float)((r / w->rooms_w) * w->base.room_h * MT);
    // the state conditions test: Holodrum's season there (spring if the rod hasn't changed it)
    int state = w->room_season && w->room_season[r] != SEASON_DEFAULT ? w->room_season[r] : w->state[r];
    for (int i = 0; i < n_places && n_actors < MAX_ACTORS; i++) {
      const Placement *pl = &places[i];
      if (pl->game != w->id || pl->group != w->group || pl->room != room) continue;
      if (pl->cond != 0xff && !(pl->cond >> state & 1)) continue;
      const Kind *k = find_kind(pl->game, pl->kind, pl->id, pl->subid);
      bool gives = k && (k->n_gifts || k->take != 0xff || k->tell != 0xff);   // story characters may have no sprite here
      if (!k || (!k->n_frames && pl->kind != ACTOR_ENEMY && !gives)) continue;
      if (pl->kind == ACTOR_INTERACTION && pl->random == 2 && !gives) continue;   // placed by a script later
      if (pl->kind == ACTOR_INTERACTION && !shown_now(k)) continue;     // not at this point in the story
      if (pl->kind == ACTOR_INTERACTION && actors_gone && actors_gone(pl->game, pl->id, pl->subid)) continue;
      for (int c = 0; c < pl->count && n_actors < MAX_ACTORS; c++) {
        Actor *a = &actors[n_actors];
        memset(a, 0, sizeof *a);
        a->k = k;
        a->kind = pl->kind;
        a->room = r;
        a->x = ox + pl->x;
        a->y = oy + pl->y;
        if (pl->random == 2) { a->x = ox + (float)(w->base.room_w * MT) / 2; a->y = oy + (float)(w->base.room_h * MT) / 2 - 16; }
        if (pl->random) {
          // a random walkable spot in the room, like getRandomPositionForEnemy
          for (int tries = 0; tries < 32; tries++) {
            float rx = ox + 16 + frand() * (float)(w->base.room_w * MT - 32), ry = oy + 16 + frand() * (float)(w->base.room_h * MT - 32);
            if (walkable(w, rx, ry)) { a->x = rx; a->y = ry; break; }
          }
        }
        if (!k->n_frames && !gives) continue;   // an enemy the sprite data can't draw yet: left out
        if (a->kind == ACTOR_ENEMY) {
          setup_enemy(a, k, pl->game);
          if (!a->hp) continue;                 // health $7f: invincible scenery (fireballs, traps)
          if ((a->boss == BOSS_DUNGEON && game.essences[pl->game] >> ((w->dungeon[r] & 15) - 1) & 1) ||
              (a->boss == BOSS_ONOX && game.onox_beaten) || (a->boss == BOSS_VERAN && game.veran_beaten))
            continue;                           // beaten already
        } else a->hp = 1;
        a->alive = true;
        a->timer = (int)(frand() * 60);
        n_actors++;
      }
    }
  }
}

bool actors_block(float px, float py) {
  for (int i = 0; i < n_actors; i++) {
    const Actor *a = &actors[i];
    if (!a->alive || a->kind != ACTOR_INTERACTION) continue;
    if (SDL_fabsf(px - a->x) < 7 && py > a->y - 6 && py < a->y + 7) return true;
  }
  return false;
}

void actors_drop(float x, float y);
static void drop(float x, float y) { actors_drop(x, y); }
void actors_drop(float x, float y) {
  if (n_actors >= MAX_ACTORS || frand() > 0.5f) return;
  Actor *a = &actors[n_actors++];
  memset(a, 0, sizeof *a);
  a->kind = ACTOR_DROP;
  a->x = x; a->y = y;
  a->hp = frand() < 0.5f ? DROP_HEART : DROP_RUPEE;
  a->timer = 60 * 8;                  // drops fade after a while, like the originals'
  a->alive = true;
}

void actors_place_pickup(int what, float x, float y) {
  if (n_actors >= MAX_ACTORS) return;
  Actor *a = &actors[n_actors++];
  memset(a, 0, sizeof *a);
  a->kind = ACTOR_DROP;
  a->x = x; a->y = y;
  a->hp = what;
  a->timer = -1;                      // stays until Link takes it
  a->alive = true;
}

// ---- what each kind of enemy does --------------------------------------------------------------
// Families of the originals' enemies by their ids (constants/*/enemies.s), each moving and attacking
// in its own way; bosses come after Link and throw fire.

static int behaviour_of(int id) {
  switch (id) {
  case 0x09: case 0x0a: case 0x0c: case 0x0d: case 0x20: case 0x21: case 0x22: case 0x40: case 0x3d: case 0x48: case 0x4a:
    return B_SHOOT;
  case 0x08: case 0x16: case 0x25: case 0x27: case 0x50: case 0x5c: return B_TURRET;
  case 0x13: case 0x15: case 0x17: case 0x19: case 0x32: case 0x39: case 0x3e: case 0x41: case 0x4c: case 0x53: return B_FLY;
  case 0x23: case 0x30: case 0x31: case 0x34: case 0x3a: case 0x43: case 0x47: return B_HOP;
  case 0x0e: case 0x0f: case 0x10: case 0x14: case 0x1b: case 0x2a: case 0x2e: case 0x45: return B_CHARGE;
  case 0x0b: case 0x1a: return B_BURROW;
  case 0x33: case 0x36: case 0x37: case 0x3b: return B_HARMLESS;
  case 0x5a: return B_SEEDTREE;
  default: return B_WALK;
  }
}


static void shoot(float x, float y, float vx, float vy, int kind) {
  for (int i = 0; i < MAX_SHOTS; i++)
    if (!eshots[i].live) { eshots[i] = (EShot){true, x, y, vx, vy, 120, kind}; return; }
}

// Link lined up with the enemy along a row or column, close enough to see: the way to him (or -1).
static int aligned(float dx, float dy, float range) {
  if (SDL_fabsf(dx) < 8 && SDL_fabsf(dy) < range) return dy < 0 ? DIR_UP : DIR_DOWN;
  if (SDL_fabsf(dy) < 8 && SDL_fabsf(dx) < range) return dx < 0 ? DIR_LEFT : DIR_RIGHT;
  return -1;
}

static const float step_x[4] = {0, 1, 0, -1}, step_y[4] = {-1, 0, 1, 0};

static void wander(Actor *a, const World *w, float speed) {
  if (--a->timer <= 0) {
    int d = (int)(frand() * 5) % 5;
    a->vx = d < 4 ? step_x[d] * speed : 0;
    a->vy = d < 4 ? step_y[d] * speed : 0;
    a->timer = 30 + (int)(frand() * 60);
  }
  float nx = a->x + a->vx, ny = a->y + a->vy;
  if (walkable(w, nx, ny)) { a->x = nx; a->y = ny; } else a->timer = 0;
}

static void behave(Actor *a, const World *w, const Link *link, float dx, float dy) {
  int kind = a->boss ? B_BOSS : a->behave;
  switch (kind) {
  case B_BOSS: {
    float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f, sp = a->boss == BOSS_MINI ? 0.55f : 0.7f;
    if (len < 160) {
      a->vx = dx / len * sp; a->vy = dy / len * sp;
      float nx = a->x + a->vx, ny = a->y + a->vy;
      if (walkable(w, nx, ny)) { a->x = nx; a->y = ny; }
      if (a->boss != BOSS_MINI && ++a->st >= 110) {   // a fireball now and then
        a->st = (int)(frand() * 30);
        shoot(a->x, a->y, dx / len * 1.6f, dy / len * 1.6f, SHOT_FIRE);
      }
    } else wander(a, w, 0.4f);
    break;
  }
  case B_SHOOT:
  case B_TURRET: {
    if (a->state > 0) { a->state--; break; }           // standing to shoot
    int d = aligned(dx, dy, 120);
    if (d >= 0 && ++a->st >= 70) {
      a->st = (int)(frand() * 40);
      a->state = 20;
      int id = a->k->id;
      int sk = id == 0x09 || id == 0x08 ? SHOT_ROCK : id == 0x16 || id == 0x25 || id == 0x50 || id == 0x5c || id == 0x40 ? SHOT_FIRE : SHOT_ARROW;
      shoot(a->x + step_x[d] * 6, a->y + step_y[d] * 6, step_x[d] * 2.0f, step_y[d] * 2.0f, sk);
      break;
    }
    if (kind == B_SHOOT) wander(a, w, 0.5f);
    break;
  }
  case B_FLY: {
    // flutters about, over walls and holes, drifting toward Link
    if (--a->timer <= 0) {
      float ang = frand() * 6.283f;
      float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f;
      a->vx = SDL_cosf(ang) * 0.6f + dx / len * 0.35f;
      a->vy = SDL_sinf(ang) * 0.6f + dy / len * 0.35f;
      a->timer = 20 + (int)(frand() * 30);
    }
    float nx = a->x + a->vx, ny = a->y + a->vy;
    if (nx > 8 && ny > 8 && nx < (float)world_px_w(w) - 8 && ny < (float)world_px_h(w) - 8) { a->x = nx; a->y = ny; }
    else a->timer = 0;
    break;
  }
  case B_HOP: {
    if (a->z > 0 || a->vz > 0) {
      a->z += a->vz; a->vz -= 0.18f;
      float nx = a->x + a->vx, ny = a->y + a->vy;
      if (walkable(w, nx, ny)) { a->x = nx; a->y = ny; }
      if (a->z <= 0) { a->z = 0; a->vz = 0; a->timer = 20 + (int)(frand() * 40); }
    } else if (--a->timer <= 0) {
      float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f;
      bool toward = len < 100 && frand() < 0.7f;
      float ang = frand() * 6.283f;
      a->vx = toward ? dx / len * 1.1f : SDL_cosf(ang) * 1.1f;
      a->vy = toward ? dy / len * 1.1f : SDL_sinf(ang) * 1.1f;
      a->vz = 2.2f;
    }
    break;
  }
  case B_CHARGE: {
    if (a->state > 0) {                      // dashing
      float nx = a->x + a->vx, ny = a->y + a->vy;
      if (walkable(w, nx, ny) && --a->state > 0) { a->x = nx; a->y = ny; }
      else { a->state = 0; a->timer = 40; }
      break;
    }
    int d = aligned(dx, dy, 100);
    if (d >= 0 && a->timer <= 0) {
      a->vx = step_x[d] * 2.2f; a->vy = step_y[d] * 2.2f;
      a->state = 70;
      break;
    }
    if (a->invincible) { if (a->timer > 0) a->timer--; break; }   // traps wait where they are
    wander(a, w, 0.4f);
    break;
  }
  case B_BURROW: {
    // under the sand until Link comes near, then up beside him for a while
    if (a->hidden) {
      if (SDL_fabsf(dx) < 64 && SDL_fabsf(dy) < 64 && --a->timer <= 0) {
        float nx = link->x + (frand() - 0.5f) * 48, ny = link->y + (frand() - 0.5f) * 48;
        if (walkable(w, nx, ny)) { a->x = nx; a->y = ny; a->hidden = false; a->state = 240; }
      }
      break;
    }
    if (--a->state <= 0) { a->hidden = true; a->timer = 60; break; }
    float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f;
    float nx = a->x + dx / len * 0.45f, ny = a->y + dy / len * 0.45f;
    if (walkable(w, nx, ny)) { a->x = nx; a->y = ny; }
    break;
  }
  case B_SEEDTREE:
    break;
  case B_HARMLESS:
    wander(a, w, 0.35f);
    break;
  default:
    wander(a, w, 0.5f);
    break;
  }
}

ActorEvents actors_update(const World *w, const Link *link, SDL_FRect sword, int sword_damage) {
  ActorEvents ev = {0};
  if (pending_boss) { ev.boss = pending_boss; ev.boss_x = pending_x; ev.boss_y = pending_y; pending_boss = 0; }
  for (int i = 0; i < n_actors; i++) {
    Actor *a = &actors[i];
    if (!a->alive) continue;
    a->anim++;
    float dx = link->x - a->x, dy = link->y - a->y;
    if (a->kind == ACTOR_DROP) {
      if (a->timer > 0 && --a->timer == 0) a->alive = false;
      else if (SDL_fabsf(dx) < 9 && SDL_fabsf(dy) < 9) {
        if (a->hp == DROP_HEART) { ev.hearts += 4; sfx("SND_GAINHEART"); }
        else if (a->hp == DROP_RUPEE) { ev.rupees += 1; sfx("SND_RUPEE"); }
        else if (a->hp == DROP_CONTAINER) ev.container = true;
        else if (a->hp == DROP_ESSENCE) ev.essence = true;
        a->alive = false;
      }
      continue;
    }
    if (a->kind != ACTOR_ENEMY || SDL_fabsf(dx) > ACTIVE_RANGE || SDL_fabsf(dy) > ACTIVE_RANGE) continue;
    if (a->hurt && a->behave != B_SEEDTREE) {
      a->hurt--;
      float nx = a->x + a->vx, ny = a->y + a->vy;
      if (walkable(w, nx, ny)) { a->x = nx; a->y = ny; }
      a->vx *= 0.85f; a->vy *= 0.85f;
    } else {
      if (a->hurt) a->hurt--;
      behave(a, w, link, dx, dy);
    }
    if (a->hidden) continue;                 // a Leever under the sand
    float bx, by, rx, ry;
    body(a, &bx, &by, &rx, &ry);
    float bdx = link->x - bx, bdy = link->y - by;
    // the sword
    if (sword.w > 0 && !a->hurt && bx + rx > sword.x && bx - rx < sword.x + sword.w && by + ry > sword.y && by - ry < sword.y + sword.h) {
      if (a->behave == B_SEEDTREE) {          // a seed tree: its seeds fall
        ev.seed_kind = a->k->subid % 5;
        ev.seeds = 5;
        a->hurt = 600;
        sfx("SND_GETSEED");
        continue;
      }
      if (a->invincible || a->behave == B_HARMLESS) { a->hurt = 16; sfx("SND_CLINK"); continue; }
      a->hp -= sword_damage;
      a->hurt = 16;
      sfx(a->hp <= 0 ? (a->boss ? "SND_BOSS_DEAD" : "SND_KILLENEMY") : a->boss ? "SND_BOSS_DAMAGE" : "SND_DAMAGE_ENEMY");
      float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f;
      a->vx = -dx / len * 2.5f; a->vy = -dy / len * 2.5f;
      if (a->hp <= 0) {
        a->alive = false;
        if (a->boss) { ev.boss = a->boss; ev.boss_x = a->x; ev.boss_y = a->y; }
        else drop(a->x, a->y);
        continue;
      }
    }
    // touching Link
    if (a->behave == B_HARMLESS || a->behave == B_SEEDTREE || a->z > 6) continue;
    if (!ev.damage && SDL_fabsf(bdx) < rx + 4 && SDL_fabsf(bdy) < ry + 5) {
      ev.damage = a->k->damage < 0 ? -a->k->damage : 2;
      float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f;
      ev.push_x = dx / len * 3.0f; ev.push_y = dy / len * 3.0f;
    }
  }
  // shots: rocks, arrows and fire
  for (int i = 0; i < MAX_SHOTS; i++) {
    EShot *e = &eshots[i];
    if (!e->live) continue;
    e->x += e->vx; e->y += e->vy;
    if (--e->t <= 0 || world_solid(w, (int)e->x, (int)e->y)) { e->live = false; continue; }
    if (SDL_fabsf(e->x - link->x) < 6 && SDL_fabsf(e->y - (link->y + 2)) < 7) {
      e->live = false;
      // the shield turns away what comes at Link's front
      int from = SDL_fabsf(e->vx) > SDL_fabsf(e->vy) ? (e->vx > 0 ? DIR_LEFT : DIR_RIGHT) : (e->vy > 0 ? DIR_UP : DIR_DOWN);
      if (game.item_level[ITEM_SHIELD] && link->dir == from && e->kind != SHOT_FIRE) { sfx("SND_CLINK"); continue; }
      if (!ev.damage) {
        ev.damage = 2;
        float len = SDL_sqrtf(e->vx * e->vx + e->vy * e->vy) + 0.01f;
        ev.push_x = e->vx / len * 3.0f; ev.push_y = e->vy / len * 3.0f;
      }
    }
  }
  return ev;
}

void (*pickup_icon)(SDL_Renderer *ren, int what, float cx, float cy, float px);

void actors_draw(SDL_Renderer *ren, const View *v, bool behind_link, float link_y) {
  if (!behind_link)
    for (int i = 0; i < MAX_SHOTS; i++) {
      const EShot *e = &eshots[i];
      if (!e->live) continue;
      float r = e->kind == SHOT_ROCK ? 3 : e->kind == SHOT_FIRE ? 3.5f : 2;
      float w = e->kind == SHOT_ARROW && SDL_fabsf(e->vx) > SDL_fabsf(e->vy) ? 6 : r, h = e->kind == SHOT_ARROW && SDL_fabsf(e->vy) >= SDL_fabsf(e->vx) ? 6 : r;
      float x0 = view_sx(v, e->x - w), y0 = view_sy(v, e->y - h), x1 = view_sx(v, e->x + w), y1 = view_sy(v, e->y + h);
      if (e->kind == SHOT_ROCK) fill_rect(ren, x0, y0, x1 - x0, y1 - y0, 120, 80, 40, 255);
      else if (e->kind == SHOT_ARROW) fill_rect(ren, x0, y0, x1 - x0, y1 - y0, 230, 220, 180, 255);
      else { fill_rect(ren, x0, y0, x1 - x0, y1 - y0, 250, 120, 30, 255); fill_rect(ren, x0 + (x1 - x0) / 4, y0 + (y1 - y0) / 4, (x1 - x0) / 2, (y1 - y0) / 2, 255, 230, 80, 255); }
    }
  for (int i = 0; i < n_actors; i++) {
    const Actor *a = &actors[i];
    if (!a->alive || (a->y < link_y) != behind_link) continue;
    if (a->kind == ACTOR_DROP) {
      if (a->timer < 120 && (a->timer / 4) & 1) continue;   // blinking before it goes
      if ((a->hp == DROP_CONTAINER || a->hp == DROP_ESSENCE) && pickup_icon) {
        pickup_icon(ren, a->hp, view_sx(v, a->x), view_sy(v, a->y - 4 + SDL_sinf((float)a->anim * 0.08f) * 2), v->scale);
        continue;
      }
      float x0 = view_sx(v, a->x - 3), y0 = view_sy(v, a->y - 4), x1 = view_sx(v, a->x + 3), y1 = view_sy(v, a->y + 3);
      bool heart = a->hp == DROP_HEART;
      fill_rect(ren, x0, y0, x1 - x0, y1 - y0, heart ? 230 : 40, heart ? 40 : 200, heart ? 40 : 90, 255);
      continue;
    }
    // the animation's frames in turn (each lasts its own count of frames)
    int total = 0;
    for (int f = 0; f < a->k->n_frames; f++) total += a->k->frames[f].dur ? a->k->frames[f].dur : 8;
    int t = total ? a->anim % total : 0, f = 0;
    while (f < a->k->n_frames - 1 && t >= (a->k->frames[f].dur ? a->k->frames[f].dur : 8)) { t -= a->k->frames[f].dur ? a->k->frames[f].dur : 8; f++; }
    if (!a->k->n_frames) continue;
    const Frame *fr = &a->k->frames[f];
    if (a->hidden) continue;
    if (a->hurt && a->behave != B_SEEDTREE && (a->hurt / 2) & 1) continue;   // flicker when hit
    float x0 = view_sx(v, a->x + fr->ox), y0 = view_sy(v, a->y - a->z + fr->oy);
    float x1 = view_sx(v, a->x + fr->ox + fr->w), y1 = view_sy(v, a->y - a->z + fr->oy + fr->h);
    image_draw(ren, &sheet, fr->x, fr->y, fr->w, fr->h, x0, y0, x1 - x0, y1 - y0, false);
  }
}

bool (*actors_gone)(int game, int id, int subid);
void actors_remove(int index) { if (index >= 0 && index < n_actors) actors[index].alive = false; }

bool actors_talk(const Link *l, ActorTalk *out) {
  static const float fx[4] = {0, 12, 0, -12}, fy[4] = {-10, 2, 14, 2};
  float px = l->x + fx[l->dir], py = l->y + fy[l->dir];
  const Actor *best = NULL;
  float best_d = 15 * 15;
  for (int i = 0; i < n_actors; i++) {
    const Actor *a = &actors[i];
    if (!a->alive || a->kind != ACTOR_INTERACTION || !a->k) continue;
    bool shop = a->k->id == 0x47 || (a->k->game == WORLD_HOLODRUM && a->k->id == 0x81);
    if (!shop && !say(a->k)[0] && a->k->tell == 0xff && a->k->take == 0xff && !a->k->n_gifts) continue;
    float dx = a->x - px, dy = a->y - py, d = dx * dx + dy * dy;
    if (d < best_d) { best_d = d; best = a; }
  }
  if (!best) return false;
  out->text = say(best->k);
  out->game = best->k->game; out->id = best->k->id; out->subid = best->k->subid;
  out->index = (int)(best - actors);
  out->n_gifts = best->k->n_gifts;
  out->gifts = best->k->gifts;
  out->tell = best->k->tell == 0xff ? -1 : best->k->tell;
  out->take = best->k->take == 0xff ? -1 : best->k->take;
  return true;
}

int actors_hit_area(float x, float y, float r, int damage) {
  int hits = 0;
  for (int i = 0; i < n_actors; i++) {
    Actor *a = &actors[i];
    if (!a->alive || a->kind != ACTOR_ENEMY || a->hurt || a->hidden || a->invincible || a->behave >= B_HARMLESS) continue;
    float bx, by, rx, ry;
    body(a, &bx, &by, &rx, &ry);
    float dx = bx - x, dy = by - y;
    float rr = r + SDL_max(0.0f, SDL_max(rx, ry) - 6);
    if (dx * dx + dy * dy > rr * rr) continue;
    a->hp -= damage;
    a->hurt = 16;
    sfx(a->hp <= 0 ? (a->boss ? "SND_BOSS_DEAD" : "SND_KILLENEMY") : a->boss ? "SND_BOSS_DAMAGE" : "SND_DAMAGE_ENEMY");
    float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f;
    a->vx = dx / len * 2.5f; a->vy = dy / len * 2.5f;
    if (a->hp <= 0) {
      a->alive = false;
      if (a->boss) { pending_boss = a->boss; pending_x = a->x; pending_y = a->y; }
      else drop(a->x, a->y);
    }
    hits++;
  }
  return hits;
}

int actors_enemy_at(float x, float y, float r) {
  for (int i = 0; i < n_actors; i++) {
    const Actor *a = &actors[i];
    if (!a->alive || a->kind != ACTOR_ENEMY || a->hidden) continue;
    float bx, by, rx, ry;
    body(a, &bx, &by, &rx, &ry);
    float dx = bx - x, dy = by - y;
    float rr = r + SDL_max(0.0f, SDL_max(rx, ry) - 6);
    if (dx * dx + dy * dy <= rr * rr) return i;
  }
  return -1;
}
void actors_get_pos(int i, float *x, float *y) { *x = actors[i].x; *y = actors[i].y; }
void actors_set_pos(int i, float x, float y) { actors[i].x = x; actors[i].y = y; }

void actors_kill_room(int room) {
  for (int i = 0; i < n_actors; i++)
    if (actors[i].kind == ACTOR_ENEMY && actors[i].room == room && !actors[i].invincible) actors[i].alive = false;
}

int actors_room_enemies(int room) {
  int n = 0;
  for (int i = 0; i < n_actors; i++) {
    const Actor *e = &actors[i];
    n += e->alive && e->kind == ACTOR_ENEMY && e->room == room && !e->invincible && e->behave != B_HARMLESS && e->behave != B_SEEDTREE;
  }
  return n;
}

int actors_enemies_alive(void) {
  int n = 0;
  for (int i = 0; i < n_actors; i++) n += actors[i].alive && actors[i].kind == ACTOR_ENEMY;
  return n;
}
