#include "actors.h"
#include "game.h"
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
} Kind;
typedef struct { uint8_t game, group, room, kind, id, subid, y, x, count, random, cond; } Placement;

typedef struct {
  const Kind *k;
  int kind, boss;
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
static int pending_boss;                // a boss beaten by a bomb or seed, reported on the next update
static float pending_x, pending_y;

static float frand(void) { rng = rng * 1664525u + 1013904223u; return (float)(rng >> 8) / (float)(1u << 24); }

bool actors_load(SDL_Renderer *ren) {
  if (!image_load(ren, "sprites.rgba", &sheet)) return false;
  size_t size;
  Uint8 *d = asset_load("objects.bin", &size);
  if (!d || size < 10 || memcmp(d, "OOBJ", 4) != 0 || (d[4] | d[5] << 8) != 2) { SDL_free(d); return false; }
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

static void setup_enemy(Actor *a, const Kind *k, int game) {
  a->boss = boss_kind(game, k->id);
  a->hp = k->health ? (k->health == 0x7f ? 0 : k->health) : 1;
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
  a->x = x; a->y = y;
  setup_enemy(a, k, game);
  a->alive = true;
  return true;
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
        if (a->hp == DROP_HEART) ev.hearts += 4;
        else if (a->hp == DROP_RUPEE) ev.rupees += 1;
        else if (a->hp == DROP_CONTAINER) ev.container = true;
        else if (a->hp == DROP_ESSENCE) ev.essence = true;
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
      // bosses come after Link; others wander, a new direction now and then or when blocked
      if (a->boss && SDL_fabsf(dx) < 160 && SDL_fabsf(dy) < 160) {
        float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f, sp = a->boss == BOSS_MINI ? 0.55f : 0.7f;
        a->vx = dx / len * sp; a->vy = dy / len * sp;
        a->timer = 20;
      } else if (--a->timer <= 0) {
        static const float dirs[5][2] = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}, {0, 0}};
        int d = (int)(frand() * 5) % 5;
        a->vx = dirs[d][0] * 0.5f; a->vy = dirs[d][1] * 0.5f;
        a->timer = 30 + (int)(frand() * 60);
      }
      float nx = a->x + a->vx, ny = a->y + a->vy;
      if (walkable(w, nx, ny)) { a->x = nx; a->y = ny; } else a->timer = 0;
    }
    float bx, by, rx, ry;
    body(a, &bx, &by, &rx, &ry);
    float bdx = link->x - bx, bdy = link->y - by;
    // the sword
    if (sword.w > 0 && !a->hurt && bx + rx > sword.x && bx - rx < sword.x + sword.w && by + ry > sword.y && by - ry < sword.y + sword.h) {
      a->hp -= sword_damage;
      a->hurt = 16;
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
    if (!ev.damage && SDL_fabsf(bdx) < rx + 4 && SDL_fabsf(bdy) < ry + 5) {
      ev.damage = a->k->damage < 0 ? -a->k->damage : 2;
      float len = SDL_sqrtf(dx * dx + dy * dy) + 0.01f;
      ev.push_x = dx / len * 3.0f; ev.push_y = dy / len * 3.0f;
    }
  }
  return ev;
}

void (*pickup_icon)(SDL_Renderer *ren, int what, float cx, float cy, float px);

void actors_draw(SDL_Renderer *ren, const View *v, bool behind_link, float link_y) {
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
    const Frame *fr = &a->k->frames[f];
    if (a->hurt && (a->hurt / 2) & 1) continue;               // flicker when hit
    float x0 = view_sx(v, a->x + fr->ox), y0 = view_sy(v, a->y + fr->oy);
    float x1 = view_sx(v, a->x + fr->ox + fr->w), y1 = view_sy(v, a->y + fr->oy + fr->h);
    image_draw(ren, &sheet, fr->x, fr->y, fr->w, fr->h, x0, y0, x1 - x0, y1 - y0, false);
  }
}

bool actors_talk(const Link *l, ActorTalk *out) {
  static const float fx[4] = {0, 12, 0, -12}, fy[4] = {-10, 2, 14, 2};
  float px = l->x + fx[l->dir], py = l->y + fy[l->dir];
  const Actor *best = NULL;
  float best_d = 15 * 15;
  for (int i = 0; i < n_actors; i++) {
    const Actor *a = &actors[i];
    if (!a->alive || a->kind != ACTOR_INTERACTION || !a->k) continue;
    if (!(a->k->text && a->k->text[0]) && a->k->tell == 0xff && a->k->take == 0xff) continue;
    float dx = a->x - px, dy = a->y - py, d = dx * dx + dy * dy;
    if (d < best_d) { best_d = d; best = a; }
  }
  if (!best) return false;
  out->text = best->k->text ? best->k->text : "";
  out->tell = best->k->tell == 0xff ? -1 : best->k->tell;
  out->take = best->k->take == 0xff ? -1 : best->k->take;
  return true;
}

int actors_hit_area(float x, float y, float r, int damage) {
  int hits = 0;
  for (int i = 0; i < n_actors; i++) {
    Actor *a = &actors[i];
    if (!a->alive || a->kind != ACTOR_ENEMY || a->hurt) continue;
    float bx, by, rx, ry;
    body(a, &bx, &by, &rx, &ry);
    float dx = bx - x, dy = by - y;
    float rr = r + SDL_max(0.0f, SDL_max(rx, ry) - 6);
    if (dx * dx + dy * dy > rr * rr) continue;
    a->hp -= damage;
    a->hurt = 16;
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
    if (!a->alive || a->kind != ACTOR_ENEMY) continue;
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

int actors_enemies_alive(void) {
  int n = 0;
  for (int i = 0; i < n_actors; i++) n += actors[i].alive && actors[i].kind == ACTOR_ENEMY;
  return n;
}
