#include "items.h"
#include "actors.h"
#include "terrain.h"
#include "rings.h"
#include "audio.h"
#include "rings.h"
#include <stdio.h>
#include <string.h>

ItemState items;
void (*items_icon)(SDL_Renderer *ren, Item item, float cx, float cy, float px, double angle);

#define GRAVITY 0.16f
#define FEATHER_JUMP 2.2f
#define CAPE_BOOST 1.9f
#define SWORD_FRAMES 14

static const int dir_x[4] = {0, 1, 0, -1}, dir_y[4] = {-1, 0, 1, 0};

static void toast(const char *s) {
  snprintf(items.toast, sizeof items.toast, "%s", s);
  items.toast_frames = 90;
}

const char *seed_name(int seed) {
  static const char *names[SEED_KINDS] = {"EMBER SEEDS", "SCENT SEEDS", "PEGASUS SEEDS", "GALE SEEDS", "MYSTERY SEEDS"};
  return seed >= 0 && seed < SEED_KINDS ? names[seed] : "";
}

const char *companion_name(int c) {
  static const char *names[3] = {"RICKY", "DIMITRI", "MOOSH"};
  return c >= 0 && c < 3 ? names[c] : "";
}

void items_next_companion(void) {
  game.companion = (uint8_t)((game.companion + 1) % 3);
  toast(companion_name(game.companion));
}

void items_next_seed(void) {
  for (int i = 1; i <= SEED_KINDS; i++) {
    int s = (game.seed_selected + i) % SEED_KINDS;
    if (game.seeds[s]) { game.seed_selected = (uint8_t)s; break; }
  }
  toast(seed_name(game.seed_selected));
}

// The screen Link stands on, as an index into World.room_season.
static int room_at(const World *w, float x, float y) {
  int rx = (int)x / (w->base.room_w * MT), ry = (int)y / (w->base.room_h * MT);
  return ry * (w->base.w / w->base.room_w) + rx;
}

static void rod_of_seasons(Link *link, World *world) {
  static const char *names[4] = {"SPRING", "SUMMER", "AUTUMN", "WINTER"};
  if (!world->room_season) { toast("THE ROD HUMS"); return; }   // Labrynna has no seasons to change
  int room = room_at(world, link->x, link->y);
  int s = world->room_season[room];
  // the original changes to the next season in order; here starting from the area's own season
  s = s == SEASON_DEFAULT ? SPRING : (s + 1) % 4;
  world->room_season[room] = (int8_t)s;
  toast(names[s]);
}

static Shot *new_shot(int kind, const Link *l, float speed, float range) {
  for (int i = 0; i < (int)SDL_arraysize(items.shots); i++) {
    Shot *s = &items.shots[i];
    if (s->live) continue;
    memset(s, 0, sizeof *s);
    s->live = true;
    s->kind = kind;
    s->x = l->x + (float)dir_x[l->dir] * 8;
    s->y = l->y + 2 + (float)dir_y[l->dir] * 8;
    s->vx = (float)dir_x[l->dir] * speed;
    s->vy = (float)dir_y[l->dir] * speed;
    s->range = range;
    return s;
  }
  return NULL;
}

static bool shot_of(int kind) {
  for (int i = 0; i < (int)SDL_arraysize(items.shots); i++)
    if (items.shots[i].live && items.shots[i].kind == kind) return true;
  return false;
}

// A seed takes effect where it lands (or, for the satchel, right in front of Link).
static void seed_effect(World *areas, World *w, int seed, float x, float y) {
  switch (seed) {
  case SEED_EMBER:
    terrain_hit_tile(areas, w, x, y, BREAK_EMBER);
    actors_hit_area(x, y, 10, 2);
    break;
  case SEED_SCENT: actors_hit_area(x, y, 10, 2); break;
  case SEED_MYSTERY: actors_hit_area(x, y, 10, 1); break;
  case SEED_GALE: items.request = REQ_GALE; sfx("SND_GALE_SEED"); break;
  default: break;
  }
}

static void use_seed(Item item, Link *link) {
  int seed = game.seed_selected;
  if (!game.seeds[seed]) {
    items_next_seed();
    seed = game.seed_selected;
    if (!game.seeds[seed]) { toast("NO SEEDS"); return; }
  }
  if (item == ITEM_SEED_SATCHEL) {
    game.seeds[seed]--;
    if (seed == SEED_PEGASUS) { items.pegasus_frames = game.ring_worn == RING_PEGASUS ? 60 * 9 : 60 * 6; toast("PEGASUS!"); return; }
    if (seed == SEED_GALE) { items.request = REQ_GALE; return; }
    Shot *s = new_shot(ITEM_NONE, link, 0, 0);       // dropped at Link's feet in front, then takes effect
    if (s) { s->seed = seed; s->t = 30; }
    return;
  }
  if (shot_of(item)) return;
  float speed = item == ITEM_SEED_SHOOTER ? 3.5f : 3.0f;
  Shot *s = new_shot(item, link, speed, 140);
  if (!s) return;
  game.seeds[seed]--;
  s->seed = seed;
  sfx("SND_THROW");
  // the hyper slingshot fires three
  if (item == ITEM_SLINGSHOT && game.item_level[ITEM_SLINGSHOT] >= 2) {
    for (int k = -1; k <= 1; k += 2) {
      Shot *e = new_shot(item, link, speed, 140);
      if (!e) break;
      e->seed = seed;
      e->vx += (float)(dir_y[link->dir] * k) * 0.8f;
      e->vy += (float)(dir_x[link->dir] * k) * 0.8f;
    }
  }
}

int items_sword_damage(void) {
  static const int by_level[4] = {0, 2, 3, 5};      // wooden, noble, master (the originals' -2, -3, -5)
  int lvl = game.item_level[ITEM_SWORD] > 3 ? 3 : game.item_level[ITEM_SWORD];
  if (!lvl && game.item_level[ITEM_FOOLS_ORE]) return ring_sword_damage(4);
  return ring_sword_damage(by_level[lvl]);
}

void items_sword_hold(bool held, Link *link) {
  (void)link;
  if (items.spin_frames || items.sword_frames > 2) return;
  if (held && (game.item_level[ITEM_SWORD] || game.item_level[ITEM_FOOLS_ORE])) {
    if (items.charge < 1000) items.charge++;
    int need = game.ring_worn == RING_CHARGE ? 20 : 40;
    if (items.charge == need) sfx("SND_CHARGE_SWORD");
    return;
  }
  int need = game.ring_worn == RING_CHARGE ? 20 : 40;
  if (items.charge >= need) {
    items.spin_frames = game.ring_worn == RING_SPIN ? 40 : 20;     // the Spin Ring spins twice
    sfx("SND_SWORDSPIN");
  }
  items.charge = 0;
}

void items_use(Item item, Link *link, World *world) {
  if (item <= ITEM_NONE || item >= ITEM_COUNT || !game.item_level[item]) return;
  switch (item) {
  case ITEM_SWORD:
  case ITEM_FOOLS_ORE:
    if (!items.sword_frames && !items.spin_frames) {
      items.sword_frames = SWORD_FRAMES;
      items.charge = 0;
      sfx("SND_SWORDSLASH");
      // the Noble and Master Swords throw a beam at full health (Light Rings: with hearts missing)
      int missing = game.max_hearts * 4 - game.health;
      int allowed = game.ring_worn == RING_LIGHT_L1 ? 8 : game.ring_worn == RING_LIGHT_L2 ? 12 : 0;
      if (item == ITEM_SWORD && game.item_level[ITEM_SWORD] >= 2 && missing <= allowed && !shot_of(ITEM_SWORD)) {
        Shot *b = new_shot(ITEM_SWORD, link, 3.2f, 150);
        if (b) sfx("SND_SWORDBEAM");
      }
    }
    break;
  case ITEM_ROCS_FEATHER:
    if (items.z <= 0) { items.vz = FEATHER_JUMP; items.cape_glide = false; sfx("SND_JUMP"); }
    break;
  case ITEM_ROCS_CAPE:
    if (items.z <= 0) { items.vz = FEATHER_JUMP; items.cape_glide = false; }
    else if (!items.cape_glide && items.vz < 0) { items.vz = CAPE_BOOST; items.cape_glide = true; }
    break;
  case ITEM_ROD_OF_SEASONS: rod_of_seasons(link, world); break;
  case ITEM_MAGNETIC_GLOVES:
    items.magnet_polarity ^= 1;
    toast(items.magnet_polarity ? "S POLARITY" : "N POLARITY");
    items.request = REQ_MAGNET;
    break;
  case ITEM_HARP_OF_AGES: items.request = REQ_TIME_TRAVEL; break;
  case ITEM_BOOMERANG:
    if (!shot_of(ITEM_BOOMERANG)) sfx("SND_BOOMERANG");
    if (!shot_of(ITEM_BOOMERANG)) new_shot(ITEM_BOOMERANG, link, 2.6f, game.item_level[ITEM_BOOMERANG] >= 2 ? 112 : 72);
    break;
  case ITEM_SWITCH_HOOK:
    if (!shot_of(ITEM_SWITCH_HOOK)) new_shot(ITEM_SWITCH_HOOK, link, 3.5f, game.item_level[ITEM_SWITCH_HOOK] >= 2 ? 112 : 72);
    break;
  case ITEM_SEED_SATCHEL:
  case ITEM_SLINGSHOT:
  case ITEM_SEED_SHOOTER:
    use_seed(item, link);
    break;
  case ITEM_SHIELD:
    break;                                  // held up while the button is down; nothing to toast
  case ITEM_STRANGE_FLUTE: items.request = REQ_FLUTE; break;
  default: {
    char s[48];
    snprintf(s, sizeof s, "%s", item_info[item].name);
    toast(s);
    break;
  }
  }
}

static void update_shots(World *areas, World *w, Link *link) {
  for (int i = 0; i < (int)SDL_arraysize(items.shots); i++) {
    Shot *s = &items.shots[i];
    if (!s->live) continue;
    s->t++;
    if (s->kind == ITEM_NONE) {             // a seed dropped from the satchel
      if (s->t >= 30) {
        seed_effect(areas, w, s->seed, s->x + s->vx, s->y);
        s->live = false;
      }
      continue;
    }
    if (s->kind == ITEM_BOOMERANG && s->returning) {
      float dx = link->x - s->x, dy = link->y - s->y, d = SDL_sqrtf(dx * dx + dy * dy);
      if (d < 6) { s->live = false; continue; }
      s->x += dx / d * 2.8f;
      s->y += dy / d * 2.8f;
    } else {
      s->x += s->vx;
      s->y += s->vy;
      s->travelled += SDL_fabsf(s->vx) + SDL_fabsf(s->vy);
    }
    bool wall = world_solid(w, (int)s->x, (int)s->y) && !(s->kind == ITEM_BOOMERANG && s->returning);
    int dmg = s->kind == ITEM_BOOMERANG ? ring_boomerang_damage(game.item_level[ITEM_BOOMERANG]) : s->kind == ITEM_SWITCH_HOOK ? 1 : 0;
    if (s->kind == ITEM_SWITCH_HOOK && !s->returning) {
      int e = actors_enemy_at(s->x, s->y, 8);
      if (e >= 0) {
        // swap places with what the hook caught
        float ex, ey;
        actors_get_pos(e, &ex, &ey);
        actors_set_pos(e, link->x, link->y);
        actors_hit_area(link->x, link->y, 4, dmg);
        link->x = ex;
        link->y = ey;
        s->live = false;
        continue;
      }
    }
    if (s->kind == ITEM_SWORD) {                 // a sword beam
      if ((wall && s->t > 6) || s->travelled >= s->range || actors_hit_area(s->x, s->y, 8, items_sword_damage())) s->live = false;
      continue;
    }
    if (s->kind == ITEM_SLINGSHOT || s->kind == ITEM_SEED_SHOOTER) {
      if (wall || actors_enemy_at(s->x, s->y, 7) >= 0 || s->travelled >= s->range) {
        seed_effect(areas, w, s->seed, s->x, s->y);
        s->live = false;
      }
      continue;
    }
    if (dmg && !s->returning && actors_hit_area(s->x, s->y, 8, dmg)) s->returning = true;
    if (s->kind == ITEM_BOOMERANG && game.item_level[ITEM_BOOMERANG] >= 2) terrain_hit_tile(areas, w, s->x, s->y, BREAK_SWORD_L1);
    if (wall || s->travelled >= s->range) {
      if (s->kind == ITEM_BOOMERANG) s->returning = true;
      else s->live = false;
    }
  }
}

bool items_update(World *areas, World *world, Link *link) {
  if (items.z > 0 || items.vz > 0) {
    items.z += items.vz;
    items.vz -= GRAVITY;
    if (items.z <= 0) { items.z = 0; items.vz = 0; }
  }
  if (items.sword_frames) items.sword_frames--;
  if (items.spin_frames) items.spin_frames--;
  if (items.toast_frames) items.toast_frames--;
  if (items.pegasus_frames) items.pegasus_frames--;
  link_speed = items.pegasus_frames ? 1.6f : 1.0f;
  update_shots(areas, world, link);
  return items.z > 0;
}

void items_draw(SDL_Renderer *ren, const Link *link, const View *v, float hud_px) {
  if ((items.spin_frames || items.charge > 0) && !items.sword_frames && items_icon) {
    // charging: the blade held out in front; spinning: it sweeps all the way round
    double angle = items.spin_frames ? link->dir * 90.0 - (double)items.spin_frames * 18.0 : link->dir * 90.0;
    float rad = (float)(angle * SDL_PI_D / 180.0);
    float cx = link->x + SDL_sinf(rad) * 13, cy = link->y + 2 - SDL_cosf(rad) * 13;
    Item blade = game.item_level[ITEM_SWORD] ? ITEM_SWORD : ITEM_FOOLS_ORE;
    int need = game.ring_worn == RING_CHARGE ? 20 : 40;
    if (items.spin_frames || items.charge < need || (items.charge / 3) & 1)   // a charged blade flashes
      items_icon(ren, blade, view_sx(v, cx), view_sy(v, cy), v->scale, angle);
  }
  if (items.sword_frames && items_icon) {
    // the blade sweeps a quarter turn into the direction Link faces, then holds there
    float t = 1.0f - (float)items.sword_frames / SWORD_FRAMES;
    float sweep = t < 0.45f ? 1.0f - t / 0.45f : 0.0f;
    double angle = link->dir * 90.0 + 90.0 * sweep;
    float rad = (float)(angle * SDL_PI_D / 180.0);
    float reach = 10 + (sweep ? 0 : 3);
    float cx = link->x + SDL_sinf(rad) * reach, cy = link->y + 2 - SDL_cosf(rad) * reach;
    Item blade = game.item_level[ITEM_SWORD] ? ITEM_SWORD : ITEM_FOOLS_ORE;
    items_icon(ren, blade, view_sx(v, cx), view_sy(v, cy), v->scale, angle);
  }
  static const Uint8 seed_rgb[SEED_KINDS][3] = {{230, 70, 40}, {200, 120, 60}, {80, 200, 90}, {80, 140, 240}, {170, 80, 200}};
  for (int i = 0; i < (int)SDL_arraysize(items.shots); i++) {
    const Shot *s = &items.shots[i];
    if (!s->live) continue;
    if (s->kind == ITEM_SWORD && items_icon) {
      double ang = SDL_fabsf(s->vx) > SDL_fabsf(s->vy) ? (s->vx > 0 ? 90 : 270) : (s->vy > 0 ? 180 : 0);
      items_icon(ren, ITEM_SWORD, view_sx(v, s->x), view_sy(v, s->y), v->scale, ang);
      continue;
    }
    if ((s->kind == ITEM_BOOMERANG || s->kind == ITEM_SWITCH_HOOK) && items_icon) {
      double spin = s->kind == ITEM_BOOMERANG ? s->t * 40.0 : link->dir * 90.0;
      items_icon(ren, (Item)s->kind, view_sx(v, s->x), view_sy(v, s->y), v->scale, spin);
      if (s->kind == ITEM_SWITCH_HOOK)     // the chain back to Link
        for (int k = 1; k < 6; k++) {
          float cx = link->x + (s->x - link->x) * (float)k / 6, cy = link->y + (s->y - link->y) * (float)k / 6;
          float x0 = view_sx(v, cx - 1), y0 = view_sy(v, cy - 1), x1 = view_sx(v, cx + 1), y1 = view_sy(v, cy + 1);
          fill_rect(ren, x0, y0, x1 - x0, y1 - y0, 200, 200, 210, 255);
        }
      continue;
    }
    const Uint8 *c = seed_rgb[s->seed % SEED_KINDS];
    float x0 = view_sx(v, s->x - 2), y0 = view_sy(v, s->y - 2), x1 = view_sx(v, s->x + 2), y1 = view_sy(v, s->y + 2);
    fill_rect(ren, x0, y0, x1 - x0, y1 - y0, c[0], c[1], c[2], 255);
  }
  if (items.toast_frames) {
    float px = hud_px;
    float x = view_sx(v, link->x) - text_width(items.toast, px) / 2, y = view_sy(v, link->y + 12);
    draw_text(ren, items.toast, x, y, px, 255, 255, 255);
  }
}

void items_give_all(void) {
  for (int i = ITEM_NONE + 1; i < ITEM_COUNT; i++) {
    while (game.item_level[i] < item_info[i].max_level) game_give_item((Item)i);
  }
  if (!game.equip_b) game.equip_b = ITEM_SWORD;
  if (game.bomb_max < 10) game.bomb_max = 10;
  game.bombs = game.bomb_max;
  game.bombchus = 20;
  game.rings_owned = ~0ull;
  game.ring_box_size = 5;
  game.gasha_seeds = 5;
  for (int s = 0; s < 5; s++) game.seeds[s] = 20;
}
