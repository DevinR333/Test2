#pragma once
#include "game.h"
#include "gfx.h"

typedef struct {
  Image hud, items;
} HudArt;

// Pixels of the window per HUD pixel: the HUD keeps one size whatever the world zoom.
float hud_scale(int win_w, int win_h);
void draw_item_icon(SDL_Renderer *ren, const HudArt *art, Item item, float x, float y, float px);
// Floating HUD in the style of The Minish Cap: hearts top left, B and A top right, rupees bottom
// right; no status bar. With on-screen buttons the rupees move under the hearts, out of A's way.
void hud_draw(SDL_Renderer *ren, const HudArt *art, int win_w, int win_h, bool touch_ui);

typedef struct {
  bool open;
  int page;                 // PAGE_SEASONS or PAGE_AGES
  float slide;              // drawn page position, eases toward `page`
  int cursor;
} Menu;

// Select flips between the Holodrum and Labrynna pages; A/B put the highlighted item on that button.
void menu_update(Menu *m, Uint32 pressed);
void menu_draw(SDL_Renderer *ren, const Menu *m, const HudArt *art, int win_w, int win_h);
