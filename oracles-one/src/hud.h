#pragma once
#include "game.h"
#include "gfx.h"

typedef struct {
  Image hud, items;
  Image inventory[PAGE_COUNT];   // the originals' item pages (inventory_seasons/ages.rgba)
  Image cursor, font;
} HudArt;

bool hud_art_load(SDL_Renderer *ren, HudArt *art);
// The games' own 8x16 font; px = screen pixels per game pixel.
void draw_game_text(SDL_Renderer *ren, const HudArt *art, const char *s, float x, float y, float px);

// Pixels of the window per HUD pixel: the HUD keeps one size whatever the world zoom.
float hud_scale(int win_w, int win_h);
void draw_item_icon(SDL_Renderer *ren, const HudArt *art, Item item, float x, float y, float px);
// Floating HUD in the style of The Minish Cap: hearts top left, B and A top right, rupees bottom
// right; no status bar. With on-screen buttons the rupees move under the hearts, out of A's way.
void hud_draw(SDL_Renderer *ren, const HudArt *art, int win_w, int win_h, bool touch_ui);

typedef struct {
  bool open;
  Page order[PAGE_COUNT];   // the world Link is in comes first
  int at;                   // index into order
  float slide;              // drawn position, eases toward `at`
  int cursor[PAGE_COUNT];
} Menu;

// Start opens the inventory on the page of the world Link is in; Select slides to the other game's
// page, like the originals' Select moves between subscreens. A/B equip the highlighted item.
void menu_open(Menu *m, WorldId world);
void menu_update(Menu *m, Uint32 pressed);
void menu_draw(SDL_Renderer *ren, const Menu *m, const HudArt *art, int win_w, int win_h, bool touch_hidden);
// The "TOUCH CONTROLS" switch under the page, in window pixels (tap it to toggle).
SDL_FRect menu_touch_switch(int win_w, int win_h);
