#include "game.h"
#include <SDL3/SDL.h>
#include <stdio.h>
#include <string.h>

#define SAVE_VERSION 7

GameState game;

// Icons from data/{seasons,ages}/treasureDisplayData.s (v >= $80: column v-$80 of the game's item
// icon sheets). Items both games have sit on the page of the game they were found in.
const ItemInfo item_info[ITEM_COUNT] = {
  [ITEM_NONE]            = {"",                PAGE_SEASONS, 0,    0,    0, 0, 0},
  [ITEM_SWORD]           = {"SWORD",           PAGE_COUNT,   0x90, 0,    0, 0, 3},
  [ITEM_SHIELD]          = {"SHIELD",          PAGE_COUNT,   0x93, 0,    0, 0, 3},
  [ITEM_BOMBS]           = {"BOMBS",           PAGE_COUNT,   0x9e, 0,    4, 0, 1},
  [ITEM_ROD_OF_SEASONS]  = {"ROD OF SEASONS",  PAGE_SEASONS, 0x98, 0,    2, 0, 1},
  [ITEM_SEED_SATCHEL]    = {"SEED SATCHEL",    PAGE_COUNT,   0x80, 0x83, 5, 2, 3},
  [ITEM_SLINGSHOT]       = {"SLINGSHOT",       PAGE_SEASONS, 0x81, 0x83, 4, 2, 2},
  [ITEM_ROCS_FEATHER]    = {"ROC'S FEATHER",   PAGE_COUNT,   0x96, 0,    4, 0, 1},
  [ITEM_ROCS_CAPE]       = {"ROC'S CAPE",      PAGE_SEASONS, 0x97, 0,    5, 0, 1},
  [ITEM_BOOMERANG]       = {"BOOMERANG",       PAGE_COUNT,   0x9c, 0,    5, 0, 2},
  [ITEM_MAGNETIC_GLOVES] = {"MAGNETIC GLOVES", PAGE_SEASONS, 0x88, 0x89, 1, 0, 1},
  [ITEM_SHOVEL]          = {"SHOVEL",          PAGE_COUNT,   0x9b, 0,    4, 0, 1},
  [ITEM_POWER_BRACELET]  = {"POWER BRACELET",  PAGE_COUNT,   0x99, 0,    5, 0, 1},
  [ITEM_STRANGE_FLUTE]   = {"FLUTE",           PAGE_SEASONS, 0x8b, 0x8c, 0, 0, 1},
  [ITEM_FOOLS_ORE]       = {"FOOL'S ORE",      PAGE_SEASONS, 0x9a, 0,    0, 0, 1},
  [ITEM_CANE_OF_SOMARIA] = {"CANE OF SOMARIA", PAGE_AGES,    0x97, 0,    2, 0, 1},
  [ITEM_SWITCH_HOOK]     = {"SWITCH HOOK",     PAGE_AGES,    0x9f, 0,    4, 0, 2},
  [ITEM_SEED_SHOOTER]    = {"SEED SHOOTER",    PAGE_AGES,    0x81, 0x83, 5, 2, 1},
  [ITEM_HARP_OF_AGES]    = {"HARP OF AGES",    PAGE_AGES,    0xa3, 0xa4, 0, 0, 3},
  [ITEM_POWER_GLOVES]    = {"POWER GLOVES",    PAGE_AGES,    0x98, 0,    5, 0, 1},
  [ITEM_BOMBCHUS]        = {"BOMBCHUS",        PAGE_AGES,    0xa0, 0,    5, 0, 1},
  [ITEM_MERMAID_SUIT]    = {"MERMAID SUIT",    PAGE_AGES,    0,    0,    0, 0, 1},
};

static char save_path[1024];

static const char *path(void) {
  if (!save_path[0]) {
    char *dir = SDL_GetPrefPath("oracles-one", "oracles-one");
    snprintf(save_path, sizeof save_path, "%ssave.bin", dir ? dir : "");
    SDL_free(dir);
  }
  return save_path;
}

void game_new(void) {
  memset(&game, 0, sizeof game);
  game.version = SAVE_VERSION;
  game.linked = false;          // a normal game from the start
  game.bomb_max = 10;
  game.max_hearts = 3;
  game.health = 3 * 4;
  game.world = WORLD_HOLODRUM;
  game.area = 0;                // areas.bin starts with Holodrum
  // Horon Village, just south of the Maku Tree's gate
  game.x = 9 * 160 + 80;
  game.y = 13 * 128 + 96;
  game.dir = 2;
  memset(game.ring_box, 0xff, sizeof game.ring_box);
  game.ring_worn = 0xff;
  // like the originals, Link starts with nothing: the Wooden Sword is in the Hero's Cave
}

bool game_load(void) {
  size_t size;
  void *d = SDL_LoadFile(path(), &size);
  if (!d) return false;
  bool ok = size == sizeof game && ((GameState *)d)->version == SAVE_VERSION;
  if (ok) memcpy(&game, d, sizeof game);
  SDL_free(d);
  return ok;
}

bool game_save(void) {
  return SDL_SaveFile(path(), &game, sizeof game);
}

static void place_on_page(Item item) {
  Page p = item_info[item].page;
  if (p == PAGE_COUNT) p = game.world == WORLD_LABRYNNA ? PAGE_AGES : PAGE_SEASONS;
  for (int q = 0; q < PAGE_COUNT; q++)
    for (int i = 0; i < PAGE_SLOTS; i++)
      if (game.page[q][i] == item) return;
  for (int i = 0; i < PAGE_SLOTS; i++)
    if (!game.page[p][i]) { game.page[p][i] = (uint8_t)item; return; }
}

void game_give_item(Item item) {
  if (item <= ITEM_NONE || item >= ITEM_COUNT) return;
  if (game.item_level[item] < item_info[item].max_level) game.item_level[item]++;
  // the sword and shield ride on A/B like the originals rather than taking a page slot
  if (item != ITEM_SWORD && item != ITEM_SHIELD) place_on_page(item);
}

void game_add_rupees(int n) {
  int r = game.rupees + n;
  game.rupees = (uint16_t)(r < 0 ? 0 : r > MAX_RUPEES ? MAX_RUPEES : r);
}

void game_heal(int quarters) {
  int h = game.health + quarters, max = game.max_hearts * 4;
  game.health = (int16_t)(h < 0 ? 0 : h > max ? max : h);
}

void game_give_heart_container(void) {
  if (game.max_hearts >= MAX_HEARTS) { game_add_rupees(HEART_CONTAINER_RUPEES); return; }
  game.max_hearts++;
  game.health = (int16_t)(game.max_hearts * 4);
}

void game_give_heart_piece(void) {
  if (game.max_hearts >= MAX_HEARTS) { game_add_rupees(HEART_PIECE_RUPEES); return; }
  if (++game.heart_pieces == 4) {
    game.heart_pieces = 0;
    game_give_heart_container();
  }
}

bool game_all_essences(void) {
  return game.essences[PAGE_SEASONS] == 0xff && game.essences[PAGE_AGES] == 0xff;
}
