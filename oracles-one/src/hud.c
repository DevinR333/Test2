#include "hud.h"
#include "input.h"
#include <stdio.h>

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

void hud_draw(SDL_Renderer *ren, const HudArt *art, int win_w, int win_h, bool touch_ui) {
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
}

// ---- pause menu ----------------------------------------------------------------------------

#define COLS 4

void menu_update(Menu *m, Uint32 pressed) {
  if (pressed & BTN_SELECT) { m->page = (m->page + 1) % PAGE_COUNT; m->cursor = 0; }
  if (pressed & BTN_LEFT) m->cursor = (m->cursor + PAGE_SLOTS - 1) % PAGE_SLOTS;
  if (pressed & BTN_RIGHT) m->cursor = (m->cursor + 1) % PAGE_SLOTS;
  if (pressed & BTN_UP) m->cursor = (m->cursor + PAGE_SLOTS - COLS) % PAGE_SLOTS;
  if (pressed & BTN_DOWN) m->cursor = (m->cursor + COLS) % PAGE_SLOTS;
  Item item = (Item)game.page[m->page][m->cursor];
  if (item && (pressed & (BTN_A | BTN_B))) {
    uint8_t *mine = (pressed & BTN_A) ? &game.equip_a : &game.equip_b;
    uint8_t *other = (pressed & BTN_A) ? &game.equip_b : &game.equip_a;
    if (*other == item) *other = *mine;   // swap, like the originals
    *mine = (uint8_t)item;
  }
  float target = (float)m->page;
  m->slide += (target - m->slide) * 0.25f;
  if (SDL_fabsf(target - m->slide) < 0.001f) m->slide = target;
}

static void draw_page(SDL_Renderer *ren, const Menu *m, const HudArt *art, int page, float ox, float w, float h, float px) {
  static const char *titles[PAGE_COUNT] = {"HOLODRUM", "LABRYNNA"};
  static const Uint8 tint[PAGE_COUNT][3] = {{96, 64, 32}, {40, 64, 112}};
  fill_rect(ren, ox, 0, w, h, tint[page][0], tint[page][1], tint[page][2], 235);
  draw_text(ren, titles[page], ox + (w - text_width(titles[page], 2 * px)) / 2, 6 * px, 2 * px, 255, 240, 200);
  float cell = 26 * px, gx = ox + (w - COLS * cell) / 2, gy = 34 * px;
  for (int i = 0; i < PAGE_SLOTS; i++) {
    float x = gx + (i % COLS) * cell, y = gy + (i / COLS) * cell;
    fill_rect(ren, x + px, y + px, cell - 2 * px, cell - 2 * px, 0, 0, 0, 70);
    Item item = (Item)game.page[page][i];
    draw_item_icon(ren, art, item, x + (cell - 16 * px) / 2, y + (cell - 16 * px) / 2, px);
    if (page == m->page && i == m->cursor) {
      SDL_SetRenderDrawColor(ren, 255, 230, 120, 255);
      for (int t = 0; t < (int)px; t++) {
        SDL_FRect rc = {x + t, y + t, cell - 2 * t, cell - 2 * t};
        SDL_RenderRect(ren, &rc);
      }
    }
  }
  Item sel = (Item)game.page[page][m->cursor];
  if (page == m->page && sel) draw_text(ren, item_info[sel].name, ox + (w - text_width(item_info[sel].name, px)) / 2, gy - 10 * px, px, 255, 255, 255);
  char line[64];
  snprintf(line, sizeof line, "ESSENCES %d/8", __builtin_popcount(game.essences[page]));
  draw_text(ren, line, ox + (w - text_width(line, px)) / 2, gy + 4 * cell + 6 * px, px, 255, 240, 200);
  const char *hint = "SELECT: OTHER WORLD   A/B: EQUIP";
  draw_text(ren, hint, ox + (w - text_width(hint, px)) / 2, h - 16 * px, px, 200, 200, 200);
}

void menu_draw(SDL_Renderer *ren, const Menu *m, const HudArt *art, int win_w, int win_h) {
  if (!m->open) return;
  float px = hud_scale(win_w, win_h);
  float w = (float)win_w, h = (float)win_h;
  // the pages sit side by side; the view slides between them
  for (int p = 0; p < PAGE_COUNT; p++) draw_page(ren, m, art, p, (p - m->slide) * w, w, h, px);
}
