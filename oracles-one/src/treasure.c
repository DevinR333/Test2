#include "treasure.h"
#include "roomflags.h"
#include "actors.h"
#include "audio.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// code/bank0.s getRupeeValue: BCD amounts by parameter
int rupee_value(int param) {
  static const int values[] = {0, 1, 2, 5, 10, 20, 40, 30, 60, 70, 25, 50, 100, 200, 400, 150, 300, 500, 900, 80, 999};
  return param >= 0 && param < (int)SDL_arraysize(values) ? values[param] : 0;
}

static void set_level(Item item, int level) {
  if (level < 1) level = 1;
  while (game.item_level[item] < level && game.item_level[item] < item_info[item].max_level) game_give_item(item);
  if (!game.item_level[item]) game_give_item(item);
}

bool treasure_owned(WorldId g, int t, int param) {
  if (t == TREASURE_SWORD) return game.item_level[ITEM_SWORD] >= SDL_min(param, 3);
  if (t == TREASURE_SHIELD) return game.item_level[ITEM_SHIELD] >= SDL_min(param, 3);
  if (g == WORLD_LABRYNNA && t >= 0x25 && t <= 0x27) return game.item_level[ITEM_HARP_OF_AGES] >= t - 0x24;
  if (t == TREASURE_ROD) return game.item_level[ITEM_ROD_OF_SEASONS] > 0;
  return t >= 0 && t < 256 && (game.treasures[g][t >> 3] >> (t & 7) & 1);
}

void treasure_give(WorldId g, int dungeon, int t, int param) {
  bool had_sword = game.item_level[ITEM_SWORD] > 0;
  if (t >= 0 && t < 256) game.treasures[g][t >> 3] |= (uint8_t)(1 << (t & 7));
  dungeon &= 15;
  switch (t) {
  // the parameter is the item's level (1: Wooden Sword, 2: Noble Sword...)
  case TREASURE_SHIELD: set_level(ITEM_SHIELD, param); break;
  case TREASURE_SWORD: set_level(ITEM_SWORD, param); break;
  case TREASURE_BOMBS:
    game_give_item(ITEM_BOMBS);
    if (!game.bomb_max) game.bomb_max = 10;
    game.bombs = (uint8_t)SDL_min(game.bomb_max, game.bombs + (param ? param : 10));
    break;
  case TREASURE_CANE: game_give_item(ITEM_CANE_OF_SOMARIA); break;
  case TREASURE_BOOMERANG: set_level(ITEM_BOOMERANG, param); break;
  case TREASURE_ROD: game_give_item(ITEM_ROD_OF_SEASONS); break;
  case TREASURE_MAGNET_GLOVES: game_give_item(ITEM_MAGNETIC_GLOVES); break;
  case TREASURE_SWITCH_HOOK: set_level(ITEM_SWITCH_HOOK, param); break;
  case TREASURE_BIGGORON_SWORD: game.biggoron_sword = true; break;
  case TREASURE_BOMBCHUS: game_give_item(ITEM_BOMBCHUS); game.bombchus = (uint8_t)SDL_min(99, game.bombchus + (param ? param : 10)); break;
  case TREASURE_FLUTE: game_give_item(ITEM_STRANGE_FLUTE); break;
  case TREASURE_SHOOTER: game_give_item(ITEM_SEED_SHOOTER); break;
  case TREASURE_HARP: game_give_item(ITEM_HARP_OF_AGES); break;
  case TREASURE_SLINGSHOT: set_level(ITEM_SLINGSHOT, param); break;
  case TREASURE_SHOVEL: game_give_item(ITEM_SHOVEL); break;
  case TREASURE_BRACELET:
    // Ages's second bracelet is the Power Glove
    if (g == WORLD_LABRYNNA && param >= 2) game_give_item(ITEM_POWER_GLOVES);
    else game_give_item(ITEM_POWER_BRACELET);
    break;
  case TREASURE_FEATHER:
    // Seasons's second feather is Roc's Cape
    game_give_item(param >= 2 && g == WORLD_HOLODRUM ? ITEM_ROCS_CAPE : ITEM_ROCS_FEATHER);
    break;
  case TREASURE_SATCHEL: case TREASURE_SATCHEL_UPGRADE: game_give_item(ITEM_SEED_SATCHEL); break;
  case TREASURE_FOOLS_ORE: game_give_item(ITEM_FOOLS_ORE); break;
  case TREASURE_RUPEES: game_add_rupees(rupee_value(param)); break;
  case TREASURE_HEART_REFILL: game_heal(param ? param : 4 * MAX_HEARTS); break;
  case TREASURE_HEART_CONTAINER: game_give_heart_container(); break;
  case TREASURE_HEART_PIECE: game_give_heart_piece(); break;
  case TREASURE_RING_BOX: game.ring_box_size = (uint8_t)SDL_max(game.ring_box_size, param <= 1 ? 1 : param == 2 ? 3 : 5); break;
  case TREASURE_RING: if (param < 64) game.rings_owned |= 1ull << param; break;
  case TREASURE_FLIPPERS: game_give_item(ITEM_MERMAID_SUIT); break;
  case TREASURE_SMALL_KEY: if (game.small_keys[g][dungeon] < 99) game.small_keys[g][dungeon]++; break;
  case TREASURE_BOSS_KEY: game.boss_keys[g] |= (uint16_t)(1 << dungeon); break;
  case TREASURE_COMPASS: game.compasses[g] |= (uint16_t)(1 << dungeon); break;
  case TREASURE_MAP: game.dungeon_maps[g] |= (uint16_t)(1 << dungeon); break;
  case TREASURE_GASHA_SEED: if (game.gasha_seeds < 99) game.gasha_seeds++; break;
  case TREASURE_ORE_CHUNKS: game.ore_chunks = (uint16_t)SDL_min(999, game.ore_chunks + rupee_value(param)); break;
  case TREASURE_BOMB_UPGRADE: game.bomb_max = (uint8_t)(game.bomb_max < 20 ? 20 : game.bomb_max + 10); break;
  default:
    // Ages's tunes are the Harp of Ages and its levels (Echoes, Currents, Ages)
    if (g == WORLD_LABRYNNA && t >= 0x25 && t <= 0x27) { set_level(ITEM_HARP_OF_AGES, t - 0x24); break; }
    if (t >= TREASURE_EMBER_SEEDS && t <= TREASURE_MYSTERY_SEEDS)
      game.seeds[t - TREASURE_EMBER_SEEDS] = (uint8_t)SDL_min(99, game.seeds[t - TREASURE_EMBER_SEEDS] + (param ? param : 20));
    break;   // quest items: remembered in game.treasures
  }
  // the first sword goes on B, like the originals
  if (!had_sword && game.item_level[ITEM_SWORD] && !game.equip_a && !game.equip_b) game.equip_b = ITEM_SWORD;
}

// ---- chests and unlocked tiles ------------------------------------------------------------------

typedef struct {
  int area, tx, ty;            // where the chest is (-1: in a room the game data doesn't place)
  uint8_t game, group, room, yx, treasure, param;
  bool event, shown;           // a chest the originals make appear (room cleared, puzzle solved)
  uint8_t under;               // the tile there before it appears
  char text[160];
} Chest;

static Chest *chests;
static int n_chests;

bool chests_load(World *areas, int n) {
  size_t size;
  Uint8 *d = asset_load("chests.bin", &size);
  if (!d) { SDL_Log("missing chests.bin"); return false; }
  if (size < 8 || memcmp(d, "OCHS", 4) != 0 || (d[4] | d[5] << 8) != 1) { SDL_free(d); return false; }
  n_chests = d[6] | d[7] << 8;
  if (size < 8 + (size_t)n_chests * 166) { SDL_free(d); return false; }
  chests = calloc((size_t)n_chests, sizeof *chests);
  for (int i = 0; i < n_chests; i++) {
    const Uint8 *e = d + 8 + i * 166;
    Chest *c = &chests[i];
    c->game = e[0]; c->group = e[1]; c->room = e[2]; c->yx = e[3]; c->treasure = e[4]; c->param = e[5];
    memcpy(c->text, e + 6, 159);
    float ox, oy;
    c->area = -1;
    if (world_find_room(areas, n, c->game, c->group, c->room, &c->area, &ox, &oy)) {
      c->tx = (int)ox / MT + (c->yx & 15);
      c->ty = (int)oy / MT + (c->yx >> 4);
    }
  }
  SDL_free(d);
  return true;
}

static int room_of(const World *w, int tx, int ty) {
  return world_room_index(w, (float)(tx * MT), (float)(ty * MT));
}

static void open_chest_tile(World *w, int tx, int ty) {
  int r = room_of(w, tx, ty);
  if (r >= 0 && w->chest_cell[r] != CELL_VOID) world_set_tile(w, tx, ty, w->chest_cell[r], w->chest_coll[r], 0xf0);
}

static void unlock_tile(World *w, int tx, int ty) {
  int r = room_of(w, tx, ty);
  if (r >= 0 && w->floor_cell[r] != CELL_VOID) world_set_tile(w, tx, ty, w->floor_cell[r], w->floor_coll[r], 0xa0);
}

typedef struct { int area, tx, ty, room; uint8_t mt; bool closed; } Shutter;
static Shutter *shutters;
static int n_shutters;

void chests_restore(World *areas, int n) {
  // shutters ($78-$7b): open, until Link walks into a room whose enemies are still up; they close
  // behind him and open again when the last one falls (doorController.s). Key doors stay locked.
  if (!shutters) {
    for (int pass = 0; pass < 2; pass++) {
      n_shutters = 0;
      for (int a = 0; a < n; a++) {
        World *w = &areas[a];
        if (w->kind == AREA_OVERWORLD) continue;
        for (int ty = 0; ty < w->base.h; ty++)
          for (int tx = 0; tx < w->base.w; tx++) {
            uint8_t mt = w->mt[ty * w->base.w + tx];
            if (mt < 0x78 || mt > 0x7b) continue;
            if (pass) shutters[n_shutters] = (Shutter){a, tx, ty, room_of(w, tx, ty), mt, false};
            n_shutters++;
          }
      }
      if (!pass) shutters = calloc((size_t)n_shutters + 1, sizeof *shutters);
    }
  }
  for (int i = 0; i < n_shutters; i++) { unlock_tile(&areas[shutters[i].area], shutters[i].tx, shutters[i].ty); shutters[i].closed = false; }
  for (int i = 0; i < n_chests; i++) {
    if (chests[i].area < 0) continue;
    World *w = &areas[chests[i].area];
    if (game.chests_opened[i >> 3] >> (i & 7) & 1) { open_chest_tile(w, chests[i].tx, chests[i].ty); continue; }
    // chests the originals make appear show up once their room's enemies are beaten (at once in a
    // room without any), so nothing a dungeon needs is out of reach
    uint8_t mt = world_metatile(w, (float)(chests[i].tx * MT), (float)(chests[i].ty * MT));
    if (mt != 0xf1 && !chests[i].event) { chests[i].event = true; chests[i].under = mt; }
  }
  for (int i = 0; i < game.n_unlocked; i++)
    if (game.unlocked_area[i] < n) {
      World *w = &areas[game.unlocked_area[i]];
      world_put(w, game.unlocked_tile[i] % w->base.w, game.unlocked_tile[i] / w->base.w, game.unlocked_mt[i]);
    }
}

const char *chest_open_at(World *areas, int n, World *w, float px, float py) {
  if (world_metatile(w, px, py) != 0xf1) return NULL;     // TILEINDEX_CHEST
  int tx = (int)px / MT, ty = (int)py / MT, area = (int)(w - areas);
  for (int i = 0; i < n_chests; i++) {
    Chest *c = &chests[i];
    if (c->area != area || c->tx != tx || c->ty != ty) continue;
    if (game.chests_opened[i >> 3] >> (i & 7) & 1) return NULL;
    game.chests_opened[i >> 3] |= (uint8_t)(1 << (i & 7));
    int r = room_of(w, tx, ty);
    treasure_give(w->id, r >= 0 ? w->dungeon[r] : 0, c->treasure, c->param);
    open_chest_tile(w, tx, ty);
    return c->text[0] ? c->text : "You got something!";
  }
  // a chest the data gives no contents for: open it anyway
  open_chest_tile(w, tx, ty);
  return "It's empty.";
}

static void remember_unlocked(int area, int tile, uint8_t mt) {
  if (game.n_unlocked < SDL_arraysize(game.unlocked_area)) {
    game.unlocked_area[game.n_unlocked] = (uint16_t)area;
    game.unlocked_tile[game.n_unlocked] = (uint16_t)tile;
    game.unlocked_mt[game.n_unlocked] = mt;
    game.n_unlocked++;
  }
}

void tile_change_for_good(World *areas, World *w, int tx, int ty, uint8_t mt) {
  world_put(w, tx, ty, mt);
  remember_unlocked((int)(w - areas), ty * w->base.w + tx, mt);
}

const char *keydoor_push(World *areas, int n, World *w, float px, float py, int dir) {
  if (w->kind == AREA_OVERWORLD) return NULL;
  uint8_t mt = world_metatile(w, px, py);
  // code/interactableTiles.s: $1e keyblock; $70-$73 small key doors, $74-$77 boss key doors
  bool block = mt == 0x1e, small = mt >= 0x70 && mt <= 0x73, boss = mt >= 0x74 && mt <= 0x77;
  if (!block && !small && !boss) return NULL;
  int tx = (int)px / MT, ty = (int)py / MT, r = room_of(w, tx, ty);
  int dungeon = r >= 0 ? w->dungeon[r] & 15 : 0;
  if (boss) {
    if (!(game.boss_keys[w->id] >> dungeon & 1)) return "This door is locked. It needs the Boss Key.";
  } else {
    if (!game.small_keys[w->id][dungeon]) return "This door is locked. It needs a Small Key.";
    game.small_keys[w->id][dungeon]--;
  }
  unlock_tile(w, tx, ty);
  remember_unlocked((int)(w - areas), ty * w->base.w + tx, 0xa0);
  if (!block) roomflags_key_door(w, tx, ty, mt & 3);   // the door's side: $70/$74 up, then right, down, left
  // a key door is two tiles, one each side of the room edge: open the other half too
  static const int ox[4] = {0, 1, 0, -1}, oy[4] = {-1, 0, 1, 0};
  int tx2 = tx + ox[dir], ty2 = ty + oy[dir];
  uint8_t mt2 = world_metatile(w, (float)(tx2 * MT + 1), (float)(ty2 * MT + 1));
  if (!block && mt2 >= 0x70 && mt2 <= 0x77) {
    unlock_tile(w, tx2, ty2);
    remember_unlocked((int)(w - areas), ty2 * w->base.w + tx2, 0xa0);
  }
  (void)n;
  return NULL;
}

void room_events_update(World *areas, int n, World *w, float lx, float ly) {
  (void)n;
  int area = (int)(w - areas), r = world_room_index(w, lx, ly);
  float rx = lx - (float)((r % (w->rooms_w ? w->rooms_w : 1)) * w->base.room_w * MT);
  float ry = ly - (float)((r / (w->rooms_w ? w->rooms_w : 1)) * w->base.room_h * MT);
  // well inside the screen: past the doorway he came in by
  bool inside = r >= 0 && rx > 20 && ry > 20 && rx < (float)(w->base.room_w * MT - 20) && ry < (float)(w->base.room_h * MT - 20);
  int alive = r >= 0 ? actors_room_enemies(r) : 0;
  bool changed = false, opened = false;
  for (int i = 0; i < n_shutters; i++) {
    Shutter *s = &shutters[i];
    if (s->area != area) continue;
    bool close = s->room == r && inside && alive > 0;
    if (close == s->closed) continue;
    s->closed = close;
    if (close) world_put(w, s->tx, s->ty, s->mt);
    else { unlock_tile(w, s->tx, s->ty); opened = true; }
    changed = true;
  }
  for (int i = 0; i < n_chests; i++) {
    Chest *c = &chests[i];
    if (c->area != area || !c->event || c->shown || (game.chests_opened[i >> 3] >> (i & 7) & 1)) continue;
    int cr = room_of(w, c->tx, c->ty);
    if (cr < 0 || actors_room_enemies(cr) > 0) continue;
    if (w->closed_chest_cell[cr] == CELL_VOID) continue;
    world_set_tile(w, c->tx, c->ty, w->closed_chest_cell[cr], w->closed_chest_coll[cr], 0xf1);
    c->shown = true;
    if (cr == r) sfx("SND_SOLVEPUZZLE");
  }
  if (changed) sfx(opened ? "SND_SOLVEPUZZLE" : "SND_DOORCLOSE");
}

void room_events_enter(World *areas, World *w) {
  int area = (int)(w - areas);
  for (int i = 0; i < n_chests; i++) {
    Chest *c = &chests[i];
    if (c->area != area || !c->event || !c->shown || (game.chests_opened[i >> 3] >> (i & 7) & 1)) continue;
    // not taken: it goes back to hiding until the room is beaten again
    world_put(w, c->tx, c->ty, c->under);
    c->shown = false;
  }
}

// ---- shops ---------------------------------------------------------------------------------------

typedef struct { uint8_t game, id, subid, currency; uint16_t price; uint8_t treasure, param; } ShopItem;
static ShopItem *shop_items;
static int n_shop_items;

bool shops_load(void) {
  size_t size;
  Uint8 *d = asset_load("shops.bin", &size);
  if (!d) return false;
  n_shop_items = d[0] | d[1] << 8;
  shop_items = calloc((size_t)n_shop_items + 1, sizeof *shop_items);
  for (int i = 0; i < n_shop_items && (size_t)(2 + i * 8 + 8) <= size; i++) {
    const Uint8 *p = d + 2 + i * 8;
    shop_items[i] = (ShopItem){p[0], p[1], p[2], p[3], (uint16_t)(p[4] | p[5] << 8), p[6], p[7]};
  }
  SDL_free(d);
  return true;
}

static const ShopItem *shop_find(int g, int id, int subid) {
  for (int i = 0; i < n_shop_items; i++)
    if (shop_items[i].game == g && shop_items[i].id == id && shop_items[i].subid == subid) return &shop_items[i];
  return NULL;
}

// Goods sold once: everything but ammunition, refills and gasha seeds.
static bool one_time(int t) {
  return !(t == TREASURE_BOMBS || t == TREASURE_BOMBCHUS || t == TREASURE_HEART_REFILL || t == TREASURE_GASHA_SEED ||
           t == 0x2f /* potion */ || t == TREASURE_ORE_CHUNKS || (t >= TREASURE_EMBER_SEEDS && t <= TREASURE_MYSTERY_SEEDS));
}

static int bought_bit(int id, int subid) { return (id == 0x81 ? 32 : 0) + (subid & 31); }

bool shop_sold_out(int g, int id, int subid) {
  const ShopItem *s = shop_find(g, id, subid);
  if (!s || !one_time(s->treasure)) return false;
  int b = bought_bit(id, subid);
  return game.shop_bought[g & 1][b >> 3] >> (b & 7) & 1;
}

const char *shop_offer(int g, int id, int subid, bool confirm, bool *bought) {
  static char msg[160];
  static const char *money[6] = {"Rupees", "Ore Chunks", "Bombs", "Ember Seeds", "Scent Seeds", "Gale Seeds"};
  *bought = false;
  const ShopItem *s = shop_find(g, id, subid);
  if (!s) return NULL;
  if (shop_sold_out(g, id, subid)) return "Sold out!";
  int have = s->currency == 0 ? game.rupees : s->currency == 1 ? game.ore_chunks : s->currency == 2 ? game.bombs
           : s->currency == 3 ? game.seeds[0] : s->currency == 4 ? game.seeds[1] : game.seeds[3];
  if (!confirm) {
    snprintf(msg, sizeof msg, "%d %s. Press A again to buy it.", s->price, money[s->currency % 6]);
    return msg;
  }
  if (have < s->price) { snprintf(msg, sizeof msg, "You need %d %s.", s->price, money[s->currency % 6]); return msg; }
  switch (s->currency) {
  case 0: game.rupees = (uint16_t)(game.rupees - s->price); break;
  case 1: game.ore_chunks = (uint16_t)(game.ore_chunks - s->price); break;
  case 2: game.bombs = (uint8_t)(game.bombs - s->price); break;
  case 3: game.seeds[0] = (uint8_t)(game.seeds[0] - s->price); break;
  case 4: game.seeds[1] = (uint8_t)(game.seeds[1] - s->price); break;
  default: game.seeds[3] = (uint8_t)(game.seeds[3] - s->price); break;
  }
  treasure_give((WorldId)g, 0, s->treasure, s->param);
  if (s->treasure == 0x0e && s->param >= 0x0b && s->param <= 0x0d) game.companion = (uint8_t)(s->param - 0x0b);   // the Flute calls its animal
  if (one_time(s->treasure)) { int b = bought_bit(id, subid); game.shop_bought[g & 1][b >> 3] |= (uint8_t)(1 << (b & 7)); }
  *bought = true;
  return "Thank you! Come again!";
}
