#include "actors.h"
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
} Kind;
typedef struct { uint8_t game, group, room, kind, id, subid, y, x, count, random, cond; } Placement;

typedef struct {
  const Kind *k;
  int kind;
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

static float frand(void) { rng = rng * 1664525u + 1013904223u; return (float)(rng >> 8) / (float)(1u << 24); }

bool actors_load(SDL_Renderer *ren) {
  if (!image_load(ren, "sprites.rgba", &sheet)) return false;
  size_t size;
  Uint8 *d = asset_load("objects.bin", &size);
  if (!d || size < 10 || memcmp(d, "OOBJ", 4) != 0 || (d[4] | d[5] << 8) != 1) { SDL_free(d); return false; }
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

static const Kind *find_kind(int game, int kind, int id, int subid) {
  for (int i = 0; i < n_kinds; i++)
    if (kinds[i].game == game && kinds[i].kind == kind && kinds[i].id == id && kinds[i].subid == subid) return &kinds[i];
  return NULL;
}

static bool walkable(const World *w, float x, float y) {
  return !world_solid(w, (int)x - 4, (int)y) && !world_solid(w, (int)x + 3, (int)y) && !world_solid(w, (int)x, (int)y + 4);
}

void actors_enter(const World *areas, int n, const World *w) {
  (void)areas; (void)n;
  n_actors = 0;
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
      if (!k || (!k->n_frames && pl->kind != ACTOR_ENEMY)) continue;
      if (pl->kind == ACTOR_INTERACTION && pl->random == 2) continue;   // placed by a script later
      for (int c = 0; c < pl->count && n_actors < MAX_ACTORS; c++) {
        Actor *a = &actors[n_actors];
        memset(a, 0, sizeof *a);
        a->k = k;
        a->kind = pl->kind;
        a->x = ox + pl->x;
        a->y = oy + pl->y;
        if (pl->random) {
          // a random walkable spot in the room, like getRandomPositionForEnemy
          for (int tries = 0; tries < 32; tries++) {
            float rx = ox + 16 + frand() * (float)(w->base.room_w * MT - 32), ry = oy + 16 + frand() * (float)(w->base.room_h * MT - 32);
            if (walkable(w, rx, ry)) { a->x = rx; a->y = ry; break; }
          }
        }
        if (!k->n_frames) continue;       // an enemy the sprite data can't draw yet: left out
        a->hp = k->health ? (k->health == 0x7f ? 0 : k->health) : 1;
        if (a->kind == ACTOR_ENEMY && !a->hp) continue;   // health $7f: invincible scenery (fireballs, traps)
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

static void drop(float x, float y) {
  if (n_actors >= MAX_ACTORS || frand() > 0.5f) return;
  Actor *a = &actors[n_actors++];
  memset(a, 0, sizeof *a);
  a->kind = ACTOR_DROP;
  a->x = x; a->y = y;
  a->hp = frand() < 0.5f ? 1 : 2;    // 1: heart, 2: rupee
  a->timer = 60 * 8;                  // drops fade after a while, like the originals'
  a->alive = true;
}

ActorEvents actors_update(const World *w, const Link *link, SDL_FRect sword, int sword_damage) {
  ActorEvents ev = {0};
  for (int i = 0; i < n_actors; i++) {
    Actor *a = &actors[i];
    if (!a->alive) continue;
    a->anim++;
    float dx = link->x - a->x, dy = link->y - a->y;
    if (a->kind == ACTOR_DROP) {
      if (--a->timer <= 0) a->alive = false;
      else if (SDL_fabsf(dx) < 8 && SDL_fabsf(dy) < 8) {
        if (a->hp == 1) ev.hearts += 4; else ev.rupees += 1;
        a->alive = false;
      }
      continue;
    }
    if (a->kind != ACTOR_ENEMY || SDL_fabsf(dx) > ACTIVE_RANGE || SDL_fabsf(dy) > ACTIVE_RANGE) continue;
    if (a->hurt) {
      a->hurt--;
      float nx = a->x + a->vx, ny = a->y + a->vy;
      if (walkable(w, nx, ny)) { a->x = nx; a->y = ny; }
      a->vx *= 0.85f; a->vy *= 0.85f;
    } else {
      // wander: a new direction now and then, or when the way is blocked
      if (--a->timer <= 0) {
        static const float dirs[5][2] = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}, {0, 0}};
        int d = (int)(frand() * 5) % 5;
        a->vx = dirs[d][0] * 0.5f; a->vy = dirs[d][1] * 0.5f;
        a->timer = 30 + (int)(frand() * 60);
      }
      float nx = a->x + a->vx, ny = a->y + a->vy;
      if (walkable(w, nx, ny)) { a->x = nx; a->y = ny; } else a->timer = 0;
    }
    float ry = a->k->radius_y ? a->k->radius_y : 6, rx = a->k->radius_x ? a->k->radius_x : 6;
    // the sword
    if (sword.w > 0 && !a->hurt && a->x + rx > sword.x && a->x - rx < sword.x + sword.w && a->y + ry > sword.y && a->y - ry < sword.y + sword.h) {
      a->hp -= sword_damage;
      a->hurt = 16;
      float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f;
      a->vx = -dx / len * 2.5f; a->vy = -dy / len * 2.5f;
      if (a->hp <= 0) { a->alive = false; drop(a->x, a->y); continue; }
    }
    // touching Link
    if (!ev.damage && SDL_fabsf(dx) < rx + 4 && SDL_fabsf(dy) < ry + 5) {
      ev.damage = a->k->damage < 0 ? -a->k->damage : 2;
      float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f;
      ev.push_x = dx / len * 3.0f; ev.push_y = dy / len * 3.0f;
    }
  }
  return ev;
}

void actors_draw(SDL_Renderer *ren, const View *v, bool behind_link, float link_y) {
  for (int i = 0; i < n_actors; i++) {
    const Actor *a = &actors[i];
    if (!a->alive || (a->y < link_y) != behind_link) continue;
    if (a->kind == ACTOR_DROP) {
      if (a->timer < 120 && (a->timer / 4) & 1) continue;   // blinking before it goes
      float x0 = view_sx(v, a->x - 3), y0 = view_sy(v, a->y - 4), x1 = view_sx(v, a->x + 3), y1 = view_sy(v, a->y + 3);
      fill_rect(ren, x0, y0, x1 - x0, y1 - y0, a->hp == 1 ? 230 : 40, a->hp == 1 ? 40 : 200, a->hp == 1 ? 40 : 90, 255);
      continue;
    }
    // the animation's frames in turn (each lasts its own count of frames)
    int total = 0;
    for (int f = 0; f < a->k->n_frames; f++) total += a->k->frames[f].dur ? a->k->frames[f].dur : 8;
    int t = total ? a->anim % total : 0, f = 0;
    while (f < a->k->n_frames - 1 && t >= (a->k->frames[f].dur ? a->k->frames[f].dur : 8)) { t -= a->k->frames[f].dur ? a->k->frames[f].dur : 8; f++; }
    const Frame *fr = &a->k->frames[f];
    if (a->hurt && (a->hurt / 2) & 1) continue;               // flicker when hit
    float x0 = view_sx(v, a->x + fr->ox), y0 = view_sy(v, a->y + fr->oy);
    float x1 = view_sx(v, a->x + fr->ox + fr->w), y1 = view_sy(v, a->y + fr->oy + fr->h);
    image_draw(ren, &sheet, fr->x, fr->y, fr->w, fr->h, x0, y0, x1 - x0, y1 - y0, false);
  }
}

int actors_enemies_alive(void) {
  int n = 0;
  for (int i = 0; i < n_actors; i++) n += actors[i].alive && actors[i].kind == ACTOR_ENEMY;
  return n;
}
