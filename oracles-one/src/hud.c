#include "hud.h"
#include "input.h"
#include <stdio.h>
#include <string.h>

// gfx_hud.png, 8x8 cells: hearts (empty, 1/4, 1/2, 3/4, full) and digits
#define HEART_TILE 11
#define DIGIT_ROW 1
#define RUPEE_TILE 15

float hud_scale(int win_w, int win_h) {
  float s = SDL_floorf((float)(win_w < win_h ? win_w : win_h) / 160.0f);
  return s < 2 ? 2 : s;
}

static void hud_tile(SDL_Renderer *ren, const Image *img, int col, int row, float x, float y, float px) {
  image_draw(ren, img, col * 8, row * 8, 8, 8, x, y, 8 * px, 8 * px, false);
}

// Sword, shield and boomerang show their level with the next icon along (treasureDisplayData_sword...)
static void icon_for(Item item, int level, int *icon, int *pal) {
  static const uint8_t level_pal[3] = {0, 5, 4};
  *icon = item_info[item].icon;
  *pal = item_info[item].pal;
  if (level < 1) level = 1;
  if (item == ITEM_SWORD || item == ITEM_SHIELD) { *icon += level - 1; *pal = level_pal[level - 1]; }
  if (item == ITEM_BOOMERANG && level == 2) { *icon = 0x9d; *pal = 4; }
}

static void icon_column(SDL_Renderer *ren, const HudArt *art, int game_sheet, int v, int pal, float x, float y, float px) {
  if (v < 0x80) return;
  int col = v - 0x80, sheet = col / 16;
  if (sheet > 2) return;
  int row = (game_sheet * 8 + (pal & 7)) * 3 + sheet;
  image_draw(ren, &art->items, (col % 16) * 8, row * 16, 8, 16, x, y, 8 * px, 16 * px, false);
}

void draw_item_icon(SDL_Renderer *ren, const HudArt *art, Item item, float x, float y, float px) {
  if (item <= ITEM_NONE || item >= ITEM_COUNT) return;
  const ItemInfo *it = &item_info[item];
  int sheet = it->page == PAGE_AGES ? 1 : 0, icon, pal;
  icon_for(item, game.item_level[item], &icon, &pal);
  if (!icon) {   // no icon in the sheets: its initials
    char s[3] = {it->name[0], it->name[1], 0};
    draw_text(ren, s, x + px, y + 4 * px, px, 255, 255, 255);
    return;
  }
  if (it->icon2) {
    icon_column(ren, art, sheet, icon, pal, x, y, px);
    icon_column(ren, art, sheet, it->icon2, it->pal2, x + 8 * px, y, px);
  } else {
    icon_column(ren, art, sheet, icon, pal, x + 4 * px, y, px);
  }
}

static void draw_hearts(SDL_Renderer *ren, const HudArt *art, float x, float y, float px) {
  for (int i = 0; i < game.max_hearts; i++) {
    int q = game.health - i * 4;
    q = q < 0 ? 0 : q > 4 ? 4 : q;
    hud_tile(ren, &art->hud, HEART_TILE + q, 0, x + (i % 8) * 8 * px, y + (i / 8) * 8 * px, px);
  }
}

static void draw_number(SDL_Renderer *ren, const HudArt *art, int n, int digits, float x, float y, float px) {
  char s[8];
  snprintf(s, sizeof s, "%0*d", digits, n);
  for (int i = 0; s[i]; i++) hud_tile(ren, &art->hud, s[i] - '0', DIGIT_ROW, x + i * 8 * px, y, px);
}

static void button_badge(SDL_Renderer *ren, const HudArt *art, const char *label, Item item, float cx, float cy, float px) {
  float r = 12 * px;
  SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
  for (float dy = -r; dy <= r; dy += 1) {
    float half = SDL_sqrtf(r * r - dy * dy);
    SDL_SetRenderDrawColor(ren, 20, 30, 60, 150);
    SDL_RenderLine(ren, cx - half, cy + dy, cx + half, cy + dy);
  }
  draw_item_icon(ren, art, item, cx - 8 * px, cy - 8 * px, px);
  draw_text(ren, label, cx + r - 5 * px, cy + r - 6 * px, px, 255, 230, 120);
}

void hud_draw(SDL_Renderer *ren, const HudArt *art, int win_w, int win_h, bool touch_ui, int keys) {
  float px = hud_scale(win_w, win_h);
  float m = 4 * px;
  draw_hearts(ren, art, m, m, px);
  // B then A, top right, as in The Minish Cap (no R button)
  button_badge(ren, art, "B", (Item)game.equip_b, (float)win_w - m - 40 * px, m + 12 * px, px);
  button_badge(ren, art, "A", (Item)game.equip_a, (float)win_w - m - 12 * px, m + 12 * px, px);
  float ry = (float)win_h - m - 8 * px, rx = (float)win_w - m - 4 * 8 * px;
  if (touch_ui) { rx = m; ry = m + ((game.max_hearts + 7) / 8) * 8 * px + 2 * px; }
  hud_tile(ren, &art->hud, RUPEE_TILE, DIGIT_ROW, rx, ry, px);
  draw_number(ren, art, game.rupees, 3, rx + 8 * px, ry, px);
  if (keys >= 0) {
    // small keys, to the left of the rupees (above them when the touch buttons are up)
    float kx = touch_ui ? rx : rx - 6 * 8 * px, ky = touch_ui ? ry + 10 * px : ry;
    draw_text(ren, "KEY", kx, ky + px, px, 255, 255, 255);
    draw_number(ren, art, SDL_min(keys, 9), 1, kx + text_width("KEY", px) + 2 * px, ky, px);
  }
}

// ---- pause menu ----------------------------------------------------------------------------
// The originals' item page (code/bank2.s inventorySubscreen0): 16 slots in a 4x4 grid, items drawn
// at w4TileMap+$63 + 4 columns / 3 rows apart, a bracket either side of the selected one, and the
// selected item's name in the text bar. Coordinates below are in the page image (screen row 2 on).

#define COLS 4
#define PAGE_W 160
#define PAGE_H 128
#define SLOT_X(i) (24 + ((i) % COLS) * 32)
#define SLOT_Y(i) (8 + ((i) / COLS) * 24)
#define TEXTBAR_Y 102          // glyphs sit in rows 6-14 of their 16-pixel cell

bool hud_art_load(SDL_Renderer *ren, HudArt *art) {
  return image_load(ren, "hud.rgba", &art->hud) && image_load(ren, "items.rgba", &art->items) &&
         image_load(ren, "inventory_seasons.rgba", &art->inventory[PAGE_SEASONS]) &&
         image_load(ren, "inventory_ages.rgba", &art->inventory[PAGE_AGES]) &&
         image_load(ren, "inventory_cursor.rgba", &art->cursor) && image_load(ren, "font.rgba", &art->font);
}

void draw_game_text(SDL_Renderer *ren, const HudArt *art, const char *s, float x, float y, float px) {
  for (; *s; s++, x += 8 * px) {
    unsigned char c = (unsigned char)*s;
    image_draw(ren, &art->font, (c % 16) * 8, (c / 16) * 16, 8, 16, x, y, 8 * px, 16 * px, false);
  }
}

void menu_open(Menu *m, WorldId world) {
  m->open = true;
  m->order[0] = world == WORLD_LABRYNNA ? PAGE_AGES : PAGE_SEASONS;
  m->order[1] = world == WORLD_LABRYNNA ? PAGE_SEASONS : PAGE_AGES;
  m->at = 0;
  m->slide = 0;
}

void menu_update(Menu *m, Uint32 pressed) {
  if (pressed & BTN_SELECT) m->at = (m->at + 1) % PAGE_COUNT;
  Page page = m->order[m->at];
  int *c = &m->cursor[page];
  if (pressed & BTN_LEFT) *c = (*c + PAGE_SLOTS - 1) % PAGE_SLOTS;
  if (pressed & BTN_RIGHT) *c = (*c + 1) % PAGE_SLOTS;
  if (pressed & BTN_UP) *c = (*c + PAGE_SLOTS - COLS) % PAGE_SLOTS;
  if (pressed & BTN_DOWN) *c = (*c + COLS) % PAGE_SLOTS;
  Item item = (Item)game.page[page][*c];
  if (item && (pressed & (BTN_A | BTN_B))) {
    uint8_t *mine = (pressed & BTN_A) ? &game.equip_a : &game.equip_b;
    uint8_t *other = (pressed & BTN_A) ? &game.equip_b : &game.equip_a;
    if (*other == item) *other = *mine;   // swap, like the originals
    *mine = (uint8_t)item;
  }
  float target = (float)m->at;
  m->slide += (target - m->slide) * 0.25f;
  if (SDL_fabsf(target - m->slide) < 0.001f) m->slide = target;
}

static void draw_page(SDL_Renderer *ren, const Menu *m, const HudArt *art, Page page, float ox, float oy, float px) {
  image_draw(ren, &art->inventory[page], 0, 0, PAGE_W, PAGE_H, ox, oy, PAGE_W * px, PAGE_H * px, false);
  for (int i = 0; i < PAGE_SLOTS; i++)
    draw_item_icon(ren, art, (Item)game.page[page][i], ox + SLOT_X(i) * px, oy + SLOT_Y(i) * px, px);
  int c = m->cursor[page];
  image_draw(ren, &art->cursor, 0, 0, 8, 16, ox + (SLOT_X(c) - 8) * px, oy + SLOT_Y(c) * px, 8 * px, 16 * px, false);
  image_draw(ren, &art->cursor, 0, 0, 8, 16, ox + (SLOT_X(c) + 16) * px, oy + SLOT_Y(c) * px, 8 * px, 16 * px, true);
  Item sel = (Item)game.page[page][c];
  if (sel) {
    const char *name = item_info[sel].name;
    draw_game_text(ren, art, name, ox + (PAGE_W - 8 * (float)strlen(name)) / 2 * px, oy + TEXTBAR_Y * px, px);
  }
}

// The page at a whole-pixel scale, with room above it for the touch-controls switch (between the
// hearts and the A/B badges) and below it for the on-screen Select/Start.
static float page_scale(int win_w, int win_h) {
  float px = SDL_floorf(SDL_min((float)win_w / PAGE_W, (float)win_h / (PAGE_H + 44)));
  return px < 1 ? 1 : px;
}
static float page_top(int win_h, float px) { return ((float)win_h - (PAGE_H + 44) * px) / 2 + 20 * px; }

SDL_FRect menu_touch_switch(int win_w, int win_h) {
  float px = page_scale(win_w, win_h);
  float w = 21 * 8 * px * 0.75f, h = 16 * px * 0.75f;
  return (SDL_FRect){((float)win_w - w) / 2, page_top(win_h, px) - h - 4 * px, w, h};
}

void menu_draw(SDL_Renderer *ren, const Menu *m, const HudArt *art, int win_w, int win_h, bool touch_hidden) {
  if (!m->open) return;
  float px = page_scale(win_w, win_h);
  float pw = PAGE_W * px, ox = ((float)win_w - pw) / 2, oy = page_top(win_h, px);
  fill_rect(ren, 0, 0, (float)win_w, (float)win_h, 0, 0, 0, 200);
  SDL_Rect clip = {(int)ox, 0, (int)pw, win_h};
  SDL_SetRenderClipRect(ren, &clip);
  // the two games' pages sit side by side and slide like the originals' subscreens
  for (int i = 0; i < PAGE_COUNT; i++) draw_page(ren, m, art, m->order[i], ox + (i - m->slide) * pw, oy, px);
  SDL_SetRenderClipRect(ren, NULL);
  SDL_FRect sw = menu_touch_switch(win_w, win_h);
  const char *label = touch_hidden ? "TOUCH CONTROLS: OFF" : "TOUCH CONTROLS: ON ";
  fill_rect(ren, sw.x, sw.y, sw.w, sw.h, 0, 0, 0, 160);
  float tp = px * 0.75f;
  draw_game_text(ren, art, label, sw.x + (sw.w - 8 * tp * (float)strlen(label)) / 2, sw.y - 5 * tp, tp);
}
