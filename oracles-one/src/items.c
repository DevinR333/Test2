#include "items.h"
#include <stdio.h>

ItemState items;

#define GRAVITY 0.16f
#define FEATHER_JUMP 2.2f
#define CAPE_BOOST 1.9f

static void toast(const char *s) {
  snprintf(items.toast, sizeof items.toast, "%s", s);
  items.toast_frames = 90;
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

void items_use(Item item, Link *link, World *world) {
  if (item <= ITEM_NONE || item >= ITEM_COUNT || !game.item_level[item]) return;
  switch (item) {
  case ITEM_SWORD:
    if (!items.sword_frames) items.sword_frames = 14;
    break;
  case ITEM_ROCS_FEATHER:
    if (items.z <= 0) { items.vz = FEATHER_JUMP; items.cape_glide = false; }
    break;
  case ITEM_ROCS_CAPE:
    if (items.z <= 0) { items.vz = FEATHER_JUMP; items.cape_glide = false; }
    else if (!items.cape_glide && items.vz < 0) { items.vz = CAPE_BOOST; items.cape_glide = true; }
    break;
  case ITEM_ROD_OF_SEASONS: rod_of_seasons(link, world); break;
  case ITEM_MAGNETIC_GLOVES:
    items.magnet_polarity ^= 1;
    toast(items.magnet_polarity ? "S POLARITY" : "N POLARITY");
    break;
  case ITEM_HARP_OF_AGES: toast("A TUNE ECHOES"); break;
  case ITEM_STRANGE_FLUTE: toast("NO COMPANION ANSWERS"); break;
  default: {
    char s[48];
    snprintf(s, sizeof s, "%s", item_info[item].name);
    toast(s);
    break;
  }
  }
}

bool items_update(void) {
  if (items.z > 0 || items.vz > 0) {
    items.z += items.vz;
    items.vz -= GRAVITY;
    if (items.z <= 0) { items.z = 0; items.vz = 0; }
  }
  if (items.sword_frames) items.sword_frames--;
  if (items.toast_frames) items.toast_frames--;
  return items.z > 0;
}

void items_draw(SDL_Renderer *ren, const Link *link, const View *v, float hud_px) {
  if (items.sword_frames) {
    // a simple slash arc in front of Link until the swing animations are ported
    static const int ox[4] = {0, 12, 0, -12}, oy[4] = {-12, 0, 12, 0};
    float t = 1.0f - items.sword_frames / 14.0f;
    float cx = link->x + ox[link->dir] + (link->dir % 2 == 0 ? (t - 0.5f) * 16 : 0);
    float cy = link->y + oy[link->dir] + (link->dir % 2 == 1 ? (t - 0.5f) * 16 : 0);
    float x0 = view_sx(v, cx - 3), y0 = view_sy(v, cy - 3), x1 = view_sx(v, cx + 3), y1 = view_sy(v, cy + 3);
    fill_rect(ren, x0, y0, x1 - x0, y1 - y0, 230, 240, 255, 220);
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
  for (int s = 0; s < 5; s++) game.seeds[s] = 20;
}
