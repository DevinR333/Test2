// Oracles One: Oracle of Seasons and Oracle of Ages as one seamless game, built from the
// oracles-disasm data (see tools/).
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "game.h"
#include "gfx.h"
#include "hud.h"
#include "input.h"
#include "items.h"
#include "link.h"
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
      }
  }
}

typedef struct {
  SDL_Window *win;
  SDL_Renderer *ren;
  Image atlas, link_sheet;
  HudArt art;
  World worlds[WORLD_COUNT];
  World *world;
  Link link;
  Input in;
  Menu menu;
  Aspect aspect;
  float zoom;
  float cam_x, cam_y;             // camera centre, eases after Link
  int fade;                       // >0: door transition in progress (counts down)
  WorldId fade_to;
  bool door_armed;                // false right after arriving, until Link leaves the doorway
  char banner[32];
  int banner_frames;              // counts down while the place name shows
  char area[32];                  // the area Link is in, to notice when he enters another
} App;

#define BANNER_FRAMES 150
#define BANNER_FADE 25

static void set_banner(App *a, const char *s) {
  snprintf(a->banner, sizeof a->banner, "%s", s);
  a->banner_frames = BANNER_FRAMES;
}

// Entering a new area shows its name, fading in and out.
static void check_area(App *a) {
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

static void enter_world(App *a, WorldId id, bool via_door) {
  a->world = &a->worlds[id];
  game.world = (uint8_t)id;
  if (via_door) {
    a->link.x = door_x(id);
    a->link.y = door_y(id) + 8;
    a->link.dir = DIR_DOWN;
    a->door_armed = false;
  }
  a->cam_x = a->link.x;
  a->cam_y = a->link.y;
  a->area[0] = 0;
  check_area(a);
}

static bool at_door(App *a) {
  WorldId id = a->world->id;
  float dx = SDL_fabsf(a->link.x - door_x(id)), dy = a->link.y - door_y(id);
  bool in_doorway = dx < 6 && dy < 6 && dy > -14;
  bool near = dx < 16 && dy < 20 && dy > -14;      // where Link arrives, just below the door
  if (!near || !(a->in.held & BTN_UP)) a->door_armed = true;
  return a->door_armed && in_doorway && a->link.dir == DIR_UP && (a->in.held & BTN_UP);
}

static void update(App *a) {
  Input *in = &a->in;
  if (a->fade) {
    if (--a->fade == FADE_FRAMES / 2) enter_world(a, a->fade_to, true);
    return;
  }
  if (in->pressed & BTN_ASPECT) { a->aspect = (a->aspect + 1) % ASPECT_COUNT; set_banner(a, aspect_names[a->aspect]); }
  if (in->held & BTN_ZOOM_IN) a->zoom *= 1.02f;
  if (in->held & BTN_ZOOM_OUT) a->zoom /= 1.02f;
  a->zoom = SDL_clamp(a->zoom * in->zoom_factor, ZOOM_MIN, ZOOM_MAX);
  in->zoom_factor = 1;

  if (in->pressed & BTN_START) {
    if (a->menu.open) a->menu.open = false;
    else menu_open(&a->menu, a->world->id);
    return;
  }
  if (a->menu.open) { menu_update(&a->menu, in->pressed); return; }

  if (in->pressed & BTN_A) items_use((Item)game.equip_a, &a->link, a->world);
  if (in->pressed & BTN_B) items_use((Item)game.equip_b, &a->link, a->world);
  items_update();
  int dx = !!(in->held & BTN_RIGHT) - !!(in->held & BTN_LEFT);
  int dy = !!(in->held & BTN_DOWN) - !!(in->held & BTN_UP);
  if (items.sword_frames) dx = dy = 0;
  link_update(&a->link, a->world, dx, dy);
  if (at_door(a)) {
    a->fade = FADE_FRAMES;
    a->fade_to = a->world->id == WORLD_HOLODRUM ? WORLD_LABRYNNA : WORLD_HOLODRUM;
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
  link_draw(a->ren, &a->link, &a->link_sheet, &v, items.z);
  float px = hud_scale(w, h);
  items_draw(a->ren, &a->link, &v, px);
  SDL_SetRenderClipRect(a->ren, NULL);
  if (a->menu.open) menu_draw(a->ren, &a->menu, &a->art, w, h);
  hud_draw(a->ren, &a->art, w, h, a->in.touch_ui && !a->menu.open);
  if (a->banner_frames && !a->menu.open) {
    int t = a->banner_frames, since = BANNER_FRAMES - t;
    float alpha = since < BANNER_FADE ? (float)since / BANNER_FADE : t < BANNER_FADE ? (float)t / BANNER_FADE : 1.0f;
    float bp = px * 1.5f;
    draw_text_alpha(a->ren, a->banner, (float)(w - text_width(a->banner, bp)) / 2, (float)h * 0.22f, bp, 255, 240, 200, (Uint8)(alpha * 255));
  }
  input_draw_touch(a->ren, &a->in, w, h);
  if (a->fade) {
    float t = 1.0f - SDL_fabsf((float)a->fade - FADE_FRAMES / 2.0f) / (FADE_FRAMES / 2.0f);
    fill_rect(a->ren, 0, 0, (float)w, (float)h, 255, 255, 255, (Uint8)(t * 255));
  }
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
  bool all_items = false, touch = false;
  int aspect = ASPECT_FILL;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "--shot") && i + 1 < argc) shot = argv[++i];
    else if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--hold") && i + 1 < argc) hold = argv[++i];
    else if (!strcmp(argv[i], "--size") && i + 1 < argc) sscanf(argv[++i], "%dx%d", &win_w, &win_h);
    else if (!strcmp(argv[i], "--world") && i + 1 < argc) start_world = !strcmp(argv[++i], "labrynna") ? WORLD_LABRYNNA : WORLD_HOLODRUM;
    else if (!strcmp(argv[i], "--pos") && i + 1 < argc) sscanf(argv[++i], "%f,%f", &start_x, &start_y);
    else if (!strcmp(argv[i], "--zoom") && i + 1 < argc) zoom = (float)atof(argv[++i]);
    else if (!strcmp(argv[i], "--aspect") && i + 1 < argc) { i++; aspect = !strcmp(argv[i], "4:3") ? ASPECT_4_3 : !strcmp(argv[i], "16:9") ? ASPECT_16_9 : ASPECT_FILL; }
    else if (!strcmp(argv[i], "--menu") && i + 1 < argc) menu_page = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--all-items")) all_items = true;
    else if (!strcmp(argv[i], "--touch")) touch = true;
  }
  if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMEPAD)) { SDL_Log("SDL_Init: %s", SDL_GetError()); return 1; }
  SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight Portrait");
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
  if (!image_load(a.ren, "metatiles.rgba", &a.atlas) || !image_load(a.ren, "link.rgba", &a.link_sheet) ||
      !hud_art_load(a.ren, &a.art) ||
      !world_load(&a.worlds[WORLD_HOLODRUM], WORLD_HOLODRUM) || !world_load(&a.worlds[WORLD_LABRYNNA], WORLD_LABRYNNA)) {
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Oracles One", "Game data is missing. Run tools/build_assets.sh first (see README).", a.win);
    return 1;
  }
  for (int i = 0; i < WORLD_COUNT; i++) build_house(&a.worlds[i]);

  if (shot || !game_load()) game_new();
  if (all_items) items_give_all();
  if (start_world >= 0) game.world = (uint8_t)start_world;
  if (start_x >= 0) { game.x = start_x; game.y = start_y; }
  a.link.x = game.x;
  a.link.y = game.y;
  a.link.dir = game.dir;
  enter_world(&a, (WorldId)game.world, false);
  a.door_armed = true;
  if (menu_page >= 0) { menu_open(&a.menu, a.world->id); a.menu.at = a.menu.slide = menu_page; }

  Uint64 last = SDL_GetTicksNS(), acc = 0, autosave = 0;
  int frame = 0;
  while (!a.in.quit) {
    SDL_Event ev;
    int w, h;
    SDL_GetWindowSizeInPixels(a.win, &w, &h);
    while (SDL_PollEvent(&ev)) {
      input_event(&a.in, &ev, w, h);
      if (ev.type == SDL_EVENT_WILL_ENTER_BACKGROUND || ev.type == SDL_EVENT_TERMINATING) game_save();
    }
    Uint64 now = SDL_GetTicksNS();
    acc += shot ? TICK_NS : now - last;
    last = now;
    if (acc > TICK_NS * 5) acc = TICK_NS * 5;
    while (acc >= TICK_NS) {
      input_frame(&a.in, w, h);
      if (shot) { a.in.held |= parse_buttons(hold); a.in.pressed = frame == 1 ? a.in.held : 0; }
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
      SDL_Log("saved %s after %d frames: link at %.0f,%.0f in %s", shot, frame, a.link.x, a.link.y, a.world->name);
      break;
    }
    SDL_RenderPresent(a.ren);
  }
  if (!shot) game_save();
  for (int i = 0; i < WORLD_COUNT; i++) world_free(&a.worlds[i]);
  SDL_Quit();
  return 0;
}
