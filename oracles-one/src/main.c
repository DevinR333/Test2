// Oracles One: Oracle of Seasons and Oracle of Ages as one seamless game, built from the
// oracles-disasm data (see tools/).
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "actors.h"
#include "audio.h"
#include "game.h"
#include "gfx.h"
#include "hud.h"
#include "input.h"
#include "items.h"
#include "link.h"
#include "treasure.h"
#include "secrets.h"
#include "terrain.h"
#include "world.h"

#define TICK_NS (SDL_NS_PER_SECOND / 60)
#define BASE_VIEW_H 144.0f        // world pixels shown top to bottom at zoom 1, like the originals
#define ZOOM_MIN 0.35f
#define ZOOM_MAX 3.0f
#define FADE_FRAMES 24

typedef enum { ASPECT_FILL, ASPECT_16_9, ASPECT_4_3, ASPECT_COUNT } Aspect;
static const char *aspect_names[ASPECT_COUNT] = {"FILL SCREEN", "16:9", "4:3"};

// A new house in each world's main town whose door opens into the other world. Each is a copy of a
// small house already in that town (metatiles and collision, so it looks and blocks like the
// original): src_x, src_y its top-left, w x h metatiles, built at dst_x, dst_y; the door is the middle
// of its bottom row. Horon Village: the green-roofed house in the fenced yard, built on the lawn inside
// the path loop to the east. Lynna City: the blue-roofed house by the steps, built on the lawn south of
// the square.
typedef struct { int src_x, src_y, w, h, dst_x, dst_y; } House;
static const House houses[WORLD_COUNT] = {
  [WORLD_HOLODRUM] = {84, 114, 3, 3, 93, 114},
  [WORLD_LABRYNNA] = {67, 40, 3, 3, 72, 45},
};
static float door_x(WorldId id) { return (float)((houses[id].dst_x + houses[id].w / 2) * MT + MT / 2); }
static float door_y(WorldId id) { return (float)((houses[id].dst_y + houses[id].h) * MT); }   // its bottom edge

static void build_house(World *w) {
  const House *h = &houses[w->id];
  Map *layers[5] = {&w->base, &w->seasons[0], &w->seasons[1], &w->seasons[2], &w->seasons[3]};
  for (int m = 0; m < 5; m++) {
    Map *l = layers[m];
    if (!l->cells) continue;
    for (int y = 0; y < h->h; y++)
      for (int x = 0; x < h->w; x++) {
        int src = (h->src_y + y) * l->w + h->src_x + x, dst = (h->dst_y + y) * l->w + h->dst_x + x;
        l->cells[dst] = l->cells[src];   // within the same layer, so each season's house matches
        l->coll[dst] = l->coll[src];
        w->mt[dst] = 0;                  // its doorway leads to the other world, not the copied house
      }
  }
}

typedef struct {
  SDL_Window *win;
  SDL_Renderer *ren;
  Image atlas, link_sheet;
  HudArt art;
  World *worlds;                  // every area of both games
  int n_worlds;
  int overworld[WORLD_COUNT];     // the area index of Holodrum and Labrynna's present
  World *world;                   // the area Link is in
  Link link;
  Input in;
  Menu menu;
  Aspect aspect;
  float zoom;
  float cam_x, cam_y;             // camera centre, eases after Link
  int fade;                       // >0: door transition in progress (counts down)
  WarpTarget fade_to;
  bool door_armed;                // false right after arriving, until Link leaves the doorway
  bool warp_armed;                // false while Link still stands on the warp tile he arrived on
  char banner[32];
  int banner_frames;              // counts down while the place name shows
  char area[32];                  // the area Link is in, to notice when he enters another
  WarpTarget respawn;             // where Link came into this area: back there if he falls
  int hurt_frames;                // >0: just hit, flickers and can't be hit again
  float knock_x, knock_y;
  char message[800];              // a text box (chest contents, locked doors); play waits for A or B
  int message_frames;
  int message_line;               // the first line shown (long texts go on a box at a time)
  bool gale_open;                 // the Gale Seed's list of trees to fly to
  int gale_sel;
  bool riding;                    // on the Flute's companion
} App;

// Shows a text box. The originals' texts already break their lines; anything longer than a line of
// the box (16 characters, like theirs) is wrapped at spaces.
static void show_message(App *a, const char *text) {
  size_t o = 0;
  int col = 0;
  for (const char *p = text; *p && o + 1 < sizeof a->message; p++) {
    if (*p == '\n') { a->message[o++] = '\n'; col = 0; continue; }
    if (*p == ' ') {
      int word = 0;
      while (p[1 + word] && p[1 + word] != ' ' && p[1 + word] != '\n') word++;
      if (col + 1 + word > 16) { a->message[o++] = '\n'; col = 0; continue; }
    }
    a->message[o++] = *p;
    col++;
  }
  a->message[o] = 0;
  a->message_frames = 1;
  a->message_line = 0;
}

#define BANNER_FRAMES 150
#define BANNER_FADE 25

static void set_banner(App *a, const char *s) {
  snprintf(a->banner, sizeof a->banner, "%s", s);
  a->banner_frames = BANNER_FRAMES;
}

// Entering a new area shows its name, fading in and out.
static void check_area(App *a) {
  // each room's own music, from the game whose world it is
  audio_set_game(a->world->id);
  int r = world_room_index(a->world, a->link.x, a->link.y);
  if (r >= 0 && a->world->room_ids[r] != 0xffff) audio_room(a->world->group, a->world->room_ids[r]);
  const char *name = world_area_name(a->world, a->link.x, a->link.y);
  if (!name[0] || !strcmp(name, a->area)) return;
  snprintf(a->area, sizeof a->area, "%s", name);
  set_banner(a, name);
}

static SDL_Rect viewport_for(Aspect aspect, int w, int h) {
  float want = aspect == ASPECT_16_9 ? 16.0f / 9.0f : aspect == ASPECT_4_3 ? 4.0f / 3.0f : 0;
  if (want == 0) return (SDL_Rect){0, 0, w, h};
  int vw = w, vh = (int)((float)w / want);
  if (vh > h) { vh = h; vw = (int)((float)h * want); }
  return (SDL_Rect){(w - vw) / 2, (h - vh) / 2, vw, vh};
}

static View make_view(const App *a, int w, int h) {
  View v;
  v.viewport = viewport_for(a->aspect, w, h);
  // whole-pixel scales keep the pixel art sharp; zooming steps through them smoothly in between
  v.scale = (float)v.viewport.h / BASE_VIEW_H * a->zoom;
  float vw = (float)v.viewport.w / v.scale, vh = (float)v.viewport.h / v.scale;
  float ww = (float)world_px_w(a->world), wh = (float)world_px_h(a->world);
  v.x = a->cam_x - vw / 2;
  v.y = a->cam_y - vh / 2;
  // stay inside the world; centre it when it is smaller than the view
  v.x = vw >= ww ? (ww - vw) / 2 : SDL_clamp(v.x, 0, ww - vw);
  v.y = vh >= wh ? (wh - vh) / 2 : SDL_clamp(v.y, 0, wh - vh);
  // round to the screen pixel grid so scrolling doesn't shimmer
  v.x = SDL_floorf(v.x * v.scale) / v.scale;
  v.y = SDL_floorf(v.y * v.scale) / v.scale;
  return v;
}

// Settings that belong to the device rather than the save: settings.txt in the app's folder.
static char settings_path[1024];
static void settings_load(App *a) {
  char *dir = SDL_GetPrefPath("oracles-one", "oracles-one");
  snprintf(settings_path, sizeof settings_path, "%ssettings.txt", dir ? dir : "");
  SDL_free(dir);
  size_t n;
  char *d = SDL_LoadFile(settings_path, &n);
  if (!d) return;
  if (SDL_strstr(d, "touch_controls=off")) a->in.touch_hidden = true;
  SDL_free(d);
}
static void settings_save(const App *a) {
  char buf[64];
  int n = snprintf(buf, sizeof buf, "touch_controls=%s\n", a->in.touch_hidden ? "off" : "on");
  SDL_SaveFile(settings_path, buf, (size_t)n);
}

static void toggle_touch(App *a) {
  a->in.touch_hidden = !a->in.touch_hidden;
  settings_save(a);
}

static void enter_area(App *a, WarpTarget t) {
  a->world = &a->worlds[t.area];
  game.world = (uint8_t)a->world->id;
  game.area = (uint16_t)t.area;
  a->link.x = t.x;
  a->link.y = t.y;
  a->door_armed = false;
  a->warp_armed = false;          // arriving on stairs or a doorway doesn't send Link straight back
  a->cam_x = a->link.x;
  a->cam_y = a->link.y;
  a->area[0] = 0;
  a->respawn = t;
  actors_enter(a->worlds, a->n_worlds, a->world);
  terrain_enter(a->worlds, a->n_worlds, a->world, &a->link);
  room_events_enter(a->worlds, a->world);
  check_area(a);
}

static void start_fade(App *a, WarpTarget t) {
  a->fade = FADE_FRAMES;
  a->fade_to = t;
}

// The town house door to the other world (only on the two main overworlds).
static bool at_door(App *a) {
  WorldId id = a->world->id;
  if (a->world != &a->worlds[a->overworld[id]]) return false;
  float dx = SDL_fabsf(a->link.x - door_x(id)), dy = a->link.y - door_y(id);
  bool in_doorway = dx < 6 && dy < 6 && dy > -14;
  bool near = dx < 16 && dy < 20 && dy > -14;      // where Link arrives, just below the door
  if (!near || !(a->in.held & BTN_UP)) a->door_armed = true;
  return a->door_armed && in_doorway && a->link.dir == DIR_UP && (a->in.held & BTN_UP);
}

// The sword's reach during a swing: the 16x16 square in front of Link, sweeping across as he swings.
static SDL_FRect sword_box(const Link *l) {
  if (!items.sword_frames || items.sword_frames > 12) return (SDL_FRect){0, 0, 0, 0};
  // the blade reaches about 20 pixels from Link's middle, across his width
  static const float ox[4] = {-8, 4, -8, -22}, oy[4] = {-22, -4, 6, -4}, w[4] = {16, 18, 16, 18}, h[4] = {18, 16, 18, 16};
  return (SDL_FRect){l->x + ox[l->dir], l->y + oy[l->dir], w[l->dir], h[l->dir]};
}

static const char *essence_names[WORLD_COUNT][8] = {
  {"Fertile Soil", "Gift of Time", "Bright Sun", "Soothing Rain", "Nurturing Warmth", "Blowing Wind", "Seed of Life", "Changing Seasons"},
  {"Eternal Spirit", "Ancient Wood", "Echoing Howl", "Burning Flame", "Sacred Soil", "Lonely Peak", "Rolling Sea", "Falling Star"},
};

static int essence_count(void) {
  int n = 0;
  for (int g = 0; g < WORLD_COUNT; g++) for (int i = 0; i < 8; i++) n += game.essences[g] >> i & 1;
  return n;
}

// Bosses beaten and what they leave: each dungeon's boss a heart container and its essence; Onox and
// Veran end their games; Twinrova, once both are beaten, brings Ganon.
static void boss_events(App *a, const ActorEvents *ev) {
  static char buf[300];
  int r = world_room_index(a->world, a->link.x, a->link.y);
  int dungeon = r >= 0 ? a->world->dungeon[r] & 15 : 0;
  WorldId g = a->world->id;
  switch (ev->boss) {
  case BOSS_DUNGEON:
    actors_place_pickup(DROP_CONTAINER, ev->boss_x - 10, ev->boss_y);
    if (dungeon >= 1 && dungeon <= 8) actors_place_pickup(DROP_ESSENCE, ev->boss_x + 10, ev->boss_y);
    break;
  case BOSS_ONOX:
  case BOSS_VERAN:
    if (ev->boss == BOSS_ONOX) game.onox_beaten = true; else game.veran_beaten = true;
    snprintf(buf, sizeof buf, "%s is defeated! %s", ev->boss == BOSS_ONOX ? "General Onox" : "Veran",
             game.onox_beaten && game.veran_beaten ? "Both lands are saved... but Twinrova waits in the Room of Rites."
             : ev->boss == BOSS_ONOX ? "Holodrum is saved. Labrynna still needs you." : "Labrynna is saved. Holodrum still needs you.");
    show_message(a, buf);
    break;
  case BOSS_TWINROVA:
    actors_spawn(g, 0x04, 0, ev->boss_x, ev->boss_y);
    show_message(a, "Twinrova falls... and from the flames, Ganon rises!");
    break;
  case BOSS_GANON:
    game.ganon_beaten = true;
    show_message(a, "Ganon is defeated! Holodrum and Labrynna are at peace. THE END. Thank you for playing!");
    break;
  default:
    break;
  }
  if (ev->container) { sfx("SND_GETITEM"); treasure_give(g, dungeon, TREASURE_HEART_CONTAINER, 0); show_message(a, "You got a Heart Container!"); }
  if (ev->essence && dungeon >= 1 && dungeon <= 8) {
    game.essences[g] |= (uint8_t)(1 << (dungeon - 1));
    audio_music_named("MUS_GET_ESSENCE");
    snprintf(buf, sizeof buf, "You got the Essence: %s! (%d of 16)", essence_names[g][dungeon - 1], essence_count());
    show_message(a, buf);
  }
}

// The Room of Rites, where Twinrova and Ganon wait: only once both games' last bosses are beaten.
static bool final_room_locked(App *a) {
  int r = world_room_index(a->world, a->link.x, a->link.y);
  if (r < 0 || a->world->group != 5) return false;
  int room = a->world->room_ids[r];
  bool rites = (a->world->id == WORLD_HOLODRUM && room == 0x9e) || (a->world->id == WORLD_LABRYNNA && room == 0xf5);
  return rites && !(game.onox_beaten && game.veran_beaten);
}

// Enemies, their hits on Link, his sword on them, and what they drop.
static void combat(App *a) {
  static const int sword_damage[4] = {0, 2, 3, 5};    // wooden, noble, master (the originals' -2, -3, -5)
  int lvl = SDL_min(game.item_level[ITEM_SWORD], 3);
  ActorEvents ev = actors_update(a->world, &a->link, sword_box(&a->link), sword_damage[lvl]);
  boss_events(a, &ev);
  if (ev.hearts) game.health = (int16_t)SDL_min(game.health + ev.hearts, game.max_hearts * 4);
  if (ev.rupees) game.rupees = (uint16_t)SDL_min(game.rupees + ev.rupees, 999);
  if (a->hurt_frames) {
    a->hurt_frames--;
    if (a->hurt_frames > 30) link_push(&a->link, a->world, a->knock_x, a->knock_y);
    return;
  }
  if (!ev.damage || items.z > 0) return;
  game.health = (int16_t)(game.health - ev.damage);
  a->hurt_frames = 40;
  sfx("SND_DAMAGE_LINK");
  a->knock_x = ev.push_x * 0.6f;
  a->knock_y = ev.push_y * 0.6f;
  if (game.health <= 0) {
    // like the originals' continue: back where Link came in, with three hearts
    game.health = (int16_t)SDL_min(12, game.max_hearts * 4);
    a->hurt_frames = 0;
    start_fade(a, a->respawn);
    show_message(a, "You fell... but\nyou get up again.");
  }
}

// A facing a character: what they say. Those who tell a linked secret teach it to Link; those who
// take one recognise it and give their reward, no typing (secrets.h).
static const char *talk(App *a) {
  static char buf[800];
  ActorTalk t;
  if (!actors_talk(&a->link, &t)) return NULL;
  if (t.take >= 0 && secret_redeem((Secret)t.take)) {
    snprintf(buf, sizeof buf, "%s%sYou know the %s! Here is your reward.", t.text, t.text[0] ? "\n" : "", secret_info[t.take].name);
    return buf;
  }
  if (t.tell >= 0) {
    bool had = secret_known((Secret)t.tell);
    secret_hear((Secret)t.tell);
    snprintf(buf, sizeof buf, "%s%s%s %s - %s %s.", t.text, t.text[0] ? "\n" : "", had ? "Remember the" : "You learned the",
             secret_info[t.tell].name, had ? "take it to" : "the one to tell is", secret_info[t.tell].taker);
    return buf;
  }
  return t.text[0] ? t.text : NULL;
}

static int gale_trees(const App *a, int *out);

// What items ask for that moves Link elsewhere: the Harp of Ages between Labrynna's present and
// past (where he stands, if there's ground there), Gale Seeds back to the main town.
static void item_request(App *a) {
  ItemRequest r = items.request;
  items.request = REQ_NONE;
  if (r == REQ_TIME_TRAVEL) {
    const World *w = a->world;
    if (w->kind != AREA_OVERWORLD || w->id != WORLD_LABRYNNA) {
      snprintf(items.toast, sizeof items.toast, "THE TUNE ECHOES");
      items.toast_frames = 90;
      return;
    }
    int to = world_overworld(a->worlds, a->n_worlds, WORLD_LABRYNNA, w->group ? 0 : 1);
    if (to < 0 || link_blocked_at(&a->worlds[to], a->link.x, a->link.y)) {
      snprintf(items.toast, sizeof items.toast, "THE TUNE FADES");
      items.toast_frames = 90;
      return;
    }
    start_fade(a, (WarpTarget){to, a->link.x, a->link.y});
  } else if (r == REQ_GALE) {
    // the originals' list of seed trees of this game (treeWarps.s), to pick where to fly
    if (gale_trees(a, NULL) > 0) { a->gale_open = true; a->gale_sel = 0; }
    else start_fade(a, (WarpTarget){a->overworld[a->world->id], door_x(a->world->id), door_y(a->world->id) + 24});
  } else if (r == REQ_MAGNET) {
    terrain_magnet(a->world, &a->link, items.magnet_polarity);
  } else if (r == REQ_FLUTE) {
    static const char *calls[3] = {"SND_FLUTE_RICKY", "SND_FLUTE_DIMITRI", "SND_FLUTE_MOOSH"};
    a->riding = !a->riding;
    sfx(calls[game.companion % 3]);
    snprintf(items.toast, sizeof items.toast, "%s %s", a->riding ? "RIDING" : "BYE,", companion_name(game.companion));
    items.toast_frames = 90;
  }
}

// ---- Gale Seeds: trees.bin lists each game's seed trees --------------------------------------
typedef struct { uint8_t game, group, room, yx; } Tree;
static Tree trees[32];
static int n_trees;

static void trees_load(void) {
  size_t size;
  Uint8 *d = asset_load("trees.bin", &size);
  if (!d) return;
  n_trees = SDL_min((int)(d[0] | d[1] << 8), 32);
  for (int i = 0; i < n_trees && (size_t)(2 + i * 4 + 4) <= size; i++) memcpy(&trees[i], d + 2 + i * 4, 4);
  SDL_free(d);
}

// The trees Link can fly to from here (this game's); fills out[] with their indices.
static int gale_trees(const App *a, int *out) {
  int n = 0;
  for (int i = 0; i < n_trees; i++)
    if (trees[i].game == a->world->id) { if (out) out[n] = i; n++; }
  return n;
}

static void gale_update(App *a, Uint32 pressed) {
  int list[32], n = gale_trees(a, list);
  if (!n || (pressed & BTN_B)) { a->gale_open = false; return; }
  if (pressed & (BTN_DOWN | BTN_RIGHT)) { a->gale_sel = (a->gale_sel + 1) % n; sfx("SND_MENU_MOVE"); }
  if (pressed & (BTN_UP | BTN_LEFT)) { a->gale_sel = (a->gale_sel + n - 1) % n; sfx("SND_MENU_MOVE"); }
  if (pressed & BTN_A) {
    const Tree *t = &trees[list[a->gale_sel]];
    int area;
    float ox, oy;
    if (world_find_room(a->worlds, a->n_worlds, t->game, t->group, t->room, &area, &ox, &oy)) {
      sfx("SND_GALE_SEED");
      start_fade(a, (WarpTarget){area, ox + (float)((t->yx & 15) * MT + 8), oy + (float)((t->yx >> 4) * MT + 8)});
    }
    a->gale_open = false;
  }
}

static void gale_draw(App *a, int w, int h) {
  int list[32], n = gale_trees(a, list);
  float px = SDL_floorf(SDL_max(1.0f, hud_scale(w, h) * 0.5f));
  float bw = 20 * 8 * px, bh = (float)(n + 1) * 16 * px + 8 * px, bx = ((float)w - bw) / 2, by = ((float)h - bh) / 2;
  fill_rect(a->ren, bx, by, bw, bh, 8, 8, 24, 235);
  draw_game_text(a->ren, &a->art, "Fly to which tree?", bx + 4 * px, by + 4 * px, px);
  for (int i = 0; i < n; i++) {
    const Tree *t = &trees[list[i]];
    int area;
    float ox, oy;
    const char *name = "";
    if (world_find_room(a->worlds, a->n_worlds, t->game, t->group, t->room, &area, &ox, &oy))
      name = world_area_name(&a->worlds[area], ox + 8, oy + 8);
    char line[40];
    snprintf(line, sizeof line, "%c%s%s", i == a->gale_sel ? '>' : ' ', name, t->group ? " (past)" : "");
    draw_game_text(a->ren, &a->art, line, bx + 4 * px, by + 4 * px + (float)(i + 1) * 16 * px, px);
  }
}

// Which of the originals' animations Link plays this frame.
static void link_pose(App *a) {
  Link *l = &a->link;
  int mode = LINK_ANIM_WALK, steps = l->moving ? 1 : 0;
  if (terrain_falling()) { mode = terrain_drowning() ? LINK_ANIM_DROWN : LINK_ANIM_FALLINHOLE; steps = 1; }
  else if (items.sword_frames) { mode = LINK_ANIM_SWORD; steps = 2; }       // the swing, at our sword's pace
  else if (items.z > 0 || terrain_z() > 0) { mode = LINK_ANIM_JUMP; steps = 1; }
  else if (terrain_swimming()) { mode = LINK_ANIM_SWIM; steps = 1; }
  else if (terrain_carrying()) { mode = LINK_ANIM_LIFT; steps = 1; }
  link_set_anim(l, mode);
  if (mode == LINK_ANIM_WALK && !l->moving) { l->anim_frame = 0; l->anim_count = 0; }
  for (int i = 0; i < steps; i++) link_anim_update(l, true);
}

static void update(App *a) {
  Input *in = &a->in;
  if (a->fade) {
    if (--a->fade == FADE_FRAMES / 2) enter_area(a, a->fade_to);
    return;
  }
  if (in->pressed & BTN_ASPECT) { a->aspect = (a->aspect + 1) % ASPECT_COUNT; set_banner(a, aspect_names[a->aspect]); }
  if (in->held & BTN_ZOOM_IN) a->zoom *= 1.02f;
  if (in->held & BTN_ZOOM_OUT) a->zoom /= 1.02f;
  a->zoom = SDL_clamp(a->zoom * in->zoom_factor, ZOOM_MIN, ZOOM_MAX);
  in->zoom_factor = 1;

  if (in->pressed & BTN_START) {
    if (a->menu.open) { a->menu.open = false; sfx("SND_CLOSEMENU"); }
    else { menu_open(&a->menu, a->world->id); sfx("SND_OPENMENU"); }
    return;
  }
  if (a->menu.open) {
    if (in->pressed & BTN_TOUCH_TOGGLE) toggle_touch(a);
    menu_update(&a->menu, in->pressed);
    return;
  }
  if (a->gale_open) { gale_update(a, in->pressed); return; }
  if (a->message[0]) {
    // the text box stays at least a moment, then A or B closes it
    if (++a->message_frames > 15 && (in->pressed & (BTN_A | BTN_B))) {
      int lines = 1;
      for (const char *p = a->message; *p; p++) lines += *p == '\n';
      if (a->message_line + 4 < lines) { a->message_line += 4; a->message_frames = 1; }
      else a->message[0] = 0;
    }
    return;
  }
  // the tile just in front of Link, for chests and locked doors
  static const int fx[4] = {0, 9, 0, -9}, fy[4] = {-6, 4, 14, 4};
  float front_x = a->link.x + fx[a->link.dir], front_y = a->link.y + fy[a->link.dir];
  if (in->pressed & BTN_A && !terrain_carrying()) {
    const char *got = chest_open_at(a->worlds, a->n_worlds, a->world, front_x, front_y);
    if (got) { sfx("SND_OPENCHEST"); sfx("SND_GETITEM"); }
    if (!got) got = terrain_read(a->world, &a->link);
    if (!got) got = talk(a);
    if (got) { show_message(a, got); in->pressed &= ~(Uint32)BTN_A; }
  }

  bool busy = terrain_busy();
  for (int b = 0; b < 2 && !busy; b++) {
    if (!(in->pressed & (b ? BTN_B : BTN_A))) continue;
    Item item = (Item)(b ? game.equip_b : game.equip_a);
    if (!terrain_use(item, a->worlds, a->n_worlds, a->world, &a->link) && game.item_level[item]) items_use(item, &a->link, a->world);
  }
  items_update(a->worlds, a->world, &a->link);
  if (a->riding && game.companion == 0) link_speed *= 1.4f;      // Ricky bounds along
  terrain_airborne = items.z;
  terrain_companion = a->riding ? game.companion : -1;
  item_request(a);
  int dx = !!(in->held & BTN_RIGHT) - !!(in->held & BTN_LEFT);
  int dy = !!(in->held & BTN_DOWN) - !!(in->held & BTN_UP);
  if (items.sword_frames || busy) dx = dy = 0;
  if (a->hurt_frames > 30) dx = dy = 0;      // knocked back
  if (!busy) link_update(&a->link, a->world, dx, dy);
  if (items.sword_frames) terrain_sword(a->worlds, a->n_worlds, a->world, sword_box(&a->link), game.item_level[ITEM_SWORD]);
  TerrainEvents te = terrain_update(a->worlds, a->n_worlds, a->world, &a->link, dx, dy);
  if (te.damage) {
    game.health = (int16_t)(game.health - te.damage);
    if (!a->hurt_frames) a->hurt_frames = 30;
    if (game.health <= 0) { game.health = (int16_t)SDL_min(12, game.max_hearts * 4); start_fade(a, a->respawn); }
  }
  combat(a);
  room_events_update(a->worlds, a->n_worlds, a->world, a->link.x, a->link.y);
  link_pose(a);
  if (a->link.pushing == 20) {
    const char *msg = keydoor_push(a->worlds, a->n_worlds, a->world, front_x, front_y, a->link.dir);
    if (msg) show_message(a, msg);
  }
  if (at_door(a)) {
    WorldId other = a->world->id == WORLD_HOLODRUM ? WORLD_LABRYNNA : WORLD_HOLODRUM;
    a->link.dir = DIR_DOWN;
    start_fade(a, (WarpTarget){a->overworld[other], door_x(other), door_y(other) + 8});
  } else {
    // the originals' warps: doors, stairs and cave mouths are warp tiles; houses and caves are left
    // by walking off the edge of their screen
    float fx = a->link.x, fy = a->link.y + 4;      // Link's feet
    bool on_tile = world_on_warp_tile(a->world, fx, fy);
    if (!on_tile) a->warp_armed = true;
    WarpTarget t;
    float wh = (float)world_px_h(a->world);
    if (on_tile && a->warp_armed && warp_from_tile(a->worlds, a->n_worlds, a->world, fx, fy, &t)) start_fade(a, t);
    else if (a->world->kind != AREA_OVERWORLD &&
             ((dy > 0 && a->link.y >= wh - 9) || (dy < 0 && a->link.y <= 3)) &&
             warp_from_edge(a->worlds, a->n_worlds, a->world, a->link.x, a->link.y + dy * 12, &t))
      start_fade(a, t);
  }
  if (!a->fade && final_room_locked(a)) {
    start_fade(a, a->respawn);
    show_message(a, "A dark power seals the Room of Rites. Defeat both General Onox and Veran first.");
  }
  game.x = a->link.x;
  game.y = a->link.y;
  game.dir = (int8_t)a->link.dir;
  // the camera eases after Link instead of jumping a screen at a time
  a->cam_x += (a->link.x - a->cam_x) * 0.15f;
  a->cam_y += (a->link.y - a->cam_y) * 0.15f;
  check_area(a);
  if (a->banner_frames) a->banner_frames--;
}

static void render(App *a) {
  int w, h;
  SDL_GetRenderOutputSize(a->ren, &w, &h);
  SDL_SetRenderDrawColor(a->ren, 0, 0, 0, 255);
  SDL_RenderClear(a->ren);
  View v = make_view(a, w, h);
  SDL_SetRenderClipRect(a->ren, &v.viewport);
  world_draw(a->ren, a->world, &a->atlas, &v);
  terrain_draw(a->ren, &a->atlas, &v, &a->link, false);
  actors_draw(a->ren, &v, true, a->link.y);
  if (!(a->hurt_frames & 2))
    link_draw(a->ren, &a->link, &a->link_sheet, &v, items.z + terrain_z());
  actors_draw(a->ren, &v, false, a->link.y);
  terrain_draw(a->ren, &a->atlas, &v, &a->link, true);
  float px = hud_scale(w, h);
  items_draw(a->ren, &a->link, &v, px);
  SDL_SetRenderClipRect(a->ren, NULL);
  if (a->menu.open) menu_draw(a->ren, &a->menu, &a->art, w, h, a->in.touch_hidden);
  int keys = -1;
  if (a->world->kind == AREA_DUNGEON) {
    int r = world_room_index(a->world, a->link.x, a->link.y);
    if (r >= 0) keys = game.small_keys[a->world->id][a->world->dungeon[r] & 15];
  }
  hud_draw(a->ren, &a->art, w, h, a->in.touch_ui && !a->in.touch_hidden && !a->menu.open, keys);
  if (a->banner_frames && !a->menu.open) {
    int t = a->banner_frames, since = BANNER_FRAMES - t;
    float alpha = since < BANNER_FADE ? (float)since / BANNER_FADE : t < BANNER_FADE ? (float)t / BANNER_FADE : 1.0f;
    float bp = px * 1.5f;
    draw_text_alpha(a->ren, a->banner, (float)(w - text_width(a->banner, bp)) / 2, (float)h * 0.22f, bp, 255, 240, 200, (Uint8)(alpha * 255));
  }
  if (a->gale_open) gale_draw(a, w, h);
  input_draw_touch(a->ren, &a->in, w, h);
  if (a->message[0]) {
    // a text box along the bottom in the games' font, like theirs
    float tp = SDL_floorf(SDL_max(1.0f, px * 0.5f));
    float bw = SDL_min((float)w - 8 * tp, 18 * 8 * tp), bh = 4 * 16 * tp + 8 * tp;
    float bx = ((float)w - bw) / 2, by = (float)h - bh - 6 * tp;
    fill_rect(a->ren, bx, by, bw, bh, 8, 8, 24, 235);
    char line[64];
    const char *p = a->message;
    for (int skip = a->message_line; skip > 0 && *p; p++) skip -= *p == '\n';
    for (int row = 0; *p && row < 4; row++) {
      int n = 0;
      while (*p && *p != '\n' && n < 63) line[n++] = *p++;
      line[n] = 0;
      if (*p == '\n') p++;
      draw_game_text(a->ren, &a->art, line, bx + 4 * tp, by + 4 * tp + row * 16 * tp, tp);
    }
  }
  if (a->fade) {
    float t = 1.0f - SDL_fabsf((float)a->fade - FADE_FRAMES / 2.0f) / (FADE_FRAMES / 2.0f);
    fill_rect(a->ren, 0, 0, (float)w, (float)h, 255, 255, 255, (Uint8)(t * 255));
  }
}

static bool block_at(float x, float y) { return actors_block(x, y) || terrain_block(x, y); }
static HudArt *g_art;
static void draw_icon(SDL_Renderer *ren, Item item, float x, float y, float px) { draw_item_icon(ren, g_art, item, x, y, px); }
static void draw_pickup(SDL_Renderer *ren, int what, float cx, float cy, float px) {
  if (what == DROP_CONTAINER) {           // a big heart: the HUD's full heart at twice the size
    image_draw(ren, &g_art->hud, 15 * 8, 0, 8, 8, cx - 8 * px, cy - 8 * px, 16 * px, 16 * px, false);
    return;
  }
  // an essence: a glowing orb
  for (int r = 7; r > 0; r -= 2) {
    Uint8 c = (Uint8)(255 - r * 18);
    fill_rect(ren, cx - (float)r * px, cy - (float)r * px * 0.8f, (float)(2 * r) * px, (float)(2 * r) * px * 0.8f, 255, c, (Uint8)(c / 2), (Uint8)(120 + (7 - r) * 20));
  }
}
static void draw_icon_rotated(SDL_Renderer *ren, Item item, float cx, float cy, float px, double angle) {
  draw_item_icon_rotated(ren, g_art, item, cx, cy, px, angle);
}

// ---- headless checks: --shot FILE renders after --frames N with buttons --hold held --------
static Uint32 parse_buttons(const char *s) {
  Uint32 b = 0;
  for (; s && *s; s++) {
    switch (*s) {
    case 'U': b |= BTN_UP; break; case 'D': b |= BTN_DOWN; break;
    case 'L': b |= BTN_LEFT; break; case 'R': b |= BTN_RIGHT; break;
    case 'A': b |= BTN_A; break; case 'B': b |= BTN_B; break;
    default: break;
    }
  }
  return b;
}

int main(int argc, char **argv) {
  const char *shot = NULL, *hold = NULL;
  int frames = 0, win_w = 1280, win_h = 720, start_world = -1, menu_page = -1;
  float start_x = -1, start_y = -1, zoom = 1;
  bool all_items = false, touch = false, touch_hidden = false;
  int keys = 0, equip_a = -1, equip_b = -1, room_game = -1, room_group = 0, room_id = 0;
  int aspect = ASPECT_FILL;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "--shot") && i + 1 < argc) shot = argv[++i];
    else if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--hold") && i + 1 < argc) hold = argv[++i];
    else if (!strcmp(argv[i], "--size") && i + 1 < argc) sscanf(argv[++i], "%dx%d", &win_w, &win_h);
    else if (!strcmp(argv[i], "--world") && i + 1 < argc) {
      i++;   // holodrum, labrynna, or an area number from areas.bin
      start_world = !strcmp(argv[i], "labrynna") ? WORLD_LABRYNNA : !strcmp(argv[i], "holodrum") ? WORLD_HOLODRUM : WORLD_COUNT + atoi(argv[i]);
    }
    else if (!strcmp(argv[i], "--pos") && i + 1 < argc) sscanf(argv[++i], "%f,%f", &start_x, &start_y);
    else if (!strcmp(argv[i], "--zoom") && i + 1 < argc) zoom = (float)atof(argv[++i]);
    else if (!strcmp(argv[i], "--aspect") && i + 1 < argc) { i++; aspect = !strcmp(argv[i], "4:3") ? ASPECT_4_3 : !strcmp(argv[i], "16:9") ? ASPECT_16_9 : ASPECT_FILL; }
    else if (!strcmp(argv[i], "--menu") && i + 1 < argc) menu_page = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--all-items")) all_items = true;
    else if (!strcmp(argv[i], "--touch")) touch = true;
    else if (!strcmp(argv[i], "--touch-hidden")) touch_hidden = true;
    else if (!strcmp(argv[i], "--room") && i + 1 < argc) sscanf(argv[++i], "%d,%x,%x", &room_game, &room_group, &room_id);   // testing: game,group,room (hex)
    else if (!strcmp(argv[i], "--equip") && i + 1 < argc) sscanf(argv[++i], "%d,%d", &equip_a, &equip_b);   // testing: item numbers on A, B
    else if (!strcmp(argv[i], "--keys") && i + 1 < argc) keys = atoi(argv[++i]);   // testing: small keys in every dungeon
  }
  SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight Portrait");
  SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");   // back opens the menu instead of quitting
  if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMEPAD)) { SDL_Log("SDL_Init: %s", SDL_GetError()); return 1; }
  static App a;
  a.zoom = zoom;
  a.aspect = (Aspect)aspect;
  a.in.zoom_factor = 1;
  a.in.touch_ui = touch;
#ifdef SDL_PLATFORM_ANDROID
  a.in.touch_ui = true;
#endif
  SDL_WindowFlags flags = SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY;
  if (!SDL_CreateWindowAndRenderer("Oracles One", win_w, win_h, flags, &a.win, &a.ren)) { SDL_Log("window: %s", SDL_GetError()); return 1; }
  if (!shot) SDL_SetRenderVSync(a.ren, 1);
  input_init();
  if (!shot) audio_init();
  settings_load(&a);
  if (touch_hidden) a.in.touch_hidden = true;
  SDL_DisableScreenSaver();   // keeps a phone or handheld awake while playing with a controller
  if (!image_load(a.ren, "metatiles.rgba", &a.atlas) || !image_load(a.ren, "link.rgba", &a.link_sheet) ||
      !hud_art_load(a.ren, &a.art) || !actors_load(a.ren) || !link_anims_load() || !(a.n_worlds = world_load_all(&a.worlds)) || !warps_load() ||
      (a.overworld[WORLD_HOLODRUM] = world_overworld(a.worlds, a.n_worlds, WORLD_HOLODRUM, 0)) < 0 ||
      (a.overworld[WORLD_LABRYNNA] = world_overworld(a.worlds, a.n_worlds, WORLD_LABRYNNA, 0)) < 0) {
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Oracles One", "Game data is missing. Run tools/build_assets.sh first (see README).", a.win);
    return 1;
  }
  for (int i = 0; i < WORLD_COUNT; i++) build_house(&a.worlds[a.overworld[i]]);
  link_blocker = block_at;
  trees_load();
  g_art = &a.art;
  terrain_icon = draw_icon;
  items_icon = draw_icon_rotated;
  pickup_icon = draw_pickup;
  if (!tiles_load(a.worlds, a.n_worlds)) { SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Oracles One", "Game data is out of date. Run tools/build_assets.sh again.", a.win); return 1; }
  if (!chests_load(a.worlds, a.n_worlds)) { SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Oracles One", "Game data is out of date. Run tools/build_assets.sh again.", a.win); return 1; }

  if (shot || !game_load()) game_new();
  if (all_items) items_give_all();
  if (equip_a >= 0) game.equip_a = (uint8_t)equip_a;
  if (getenv("ORACLES_SEED")) game.seed_selected = (uint8_t)atoi(getenv("ORACLES_SEED"));   // testing
  if (equip_b >= 0) game.equip_b = (uint8_t)equip_b;
  chests_restore(a.worlds, a.n_worlds);
  for (int g = 0; keys && g < WORLD_COUNT; g++) for (int d = 0; d < 16; d++) game.small_keys[g][d] = (uint8_t)keys;
  if (start_world >= 0) game.area = (uint16_t)(start_world < WORLD_COUNT ? a.overworld[start_world] : start_world - WORLD_COUNT);
  if (start_x >= 0) { game.x = start_x; game.y = start_y; }
  if (room_game >= 0) {
    int area;
    float ox, oy;
    if (world_find_room(a.worlds, a.n_worlds, room_game, room_group, room_id, &area, &ox, &oy)) {
      const World *w = &a.worlds[area];
      game.area = (uint16_t)area;
      game.x = ox + (float)(w->base.room_w * MT) / 2 + (start_x >= 0 ? start_x : 0);
      game.y = oy + (float)(w->base.room_h * MT) - 24 + (start_x >= 0 ? start_y : 0);
    }
  }
  if (game.area >= a.n_worlds) game.area = (uint16_t)a.overworld[WORLD_HOLODRUM];
  a.link.dir = game.dir;
  enter_area(&a, (WarpTarget){game.area, game.x, game.y});
  a.door_armed = a.warp_armed = true;
  if (menu_page >= 0) { menu_open(&a.menu, a.world->id); a.menu.at = a.menu.slide = menu_page; }

  Uint64 last = SDL_GetTicksNS(), acc = 0, autosave = 0;
  int frame = 0;
  while (!a.in.quit) {
    SDL_Event ev;
    int w, h;
    SDL_GetWindowSizeInPixels(a.win, &w, &h);
    while (SDL_PollEvent(&ev)) {
      input_event(&a.in, &ev, w, h);
      if (a.menu.open && ev.type == SDL_EVENT_FINGER_DOWN) {
        SDL_FRect sw = menu_touch_switch(w, h);
        SDL_FPoint p = {ev.tfinger.x * (float)w, ev.tfinger.y * (float)h};
        if (SDL_PointInRectFloat(&p, &sw)) toggle_touch(&a);
      }
      if (ev.type == SDL_EVENT_WILL_ENTER_BACKGROUND || ev.type == SDL_EVENT_TERMINATING) game_save();
    }
    Uint64 now = SDL_GetTicksNS();
    acc += shot ? TICK_NS : now - last;
    last = now;
    if (acc > TICK_NS * 5) acc = TICK_NS * 5;
    while (acc >= TICK_NS) {
      input_frame(&a.in, w, h);
      if (shot) { a.in.held |= parse_buttons(hold); a.in.pressed = frame % 16 == 1 ? a.in.held : 0; }
      update(&a);
      acc -= TICK_NS;
      frame++;
      if (++autosave % (60 * 30) == 0) game_save();
    }
    render(&a);
    if (shot && frame >= frames) {
      SDL_Surface *s = SDL_RenderReadPixels(a.ren, NULL);
      if (!s || !SDL_SaveBMP(s, shot)) { SDL_Log("screenshot: %s", SDL_GetError()); return 1; }
      SDL_DestroySurface(s);
      int room = world_room_index(a.world, a.link.x, a.link.y);
      SDL_Log("saved %s after %d frames: link at %.0f,%.0f in area %d (%s, group %d, room %02x), health %d, rupees %d, enemies %d", shot, frame,
              a.link.x, a.link.y, (int)(a.world - a.worlds), a.world->name, a.world->group, room >= 0 ? a.world->room_ids[room] : 0xff,
              game.health, game.rupees, actors_enemies_alive());
      break;
    }
    SDL_RenderPresent(a.ren);
  }
  if (!shot) game_save();
  for (int i = 0; i < a.n_worlds; i++) world_free(&a.worlds[i]);
  free(a.worlds);
  SDL_Quit();
  return 0;
}
