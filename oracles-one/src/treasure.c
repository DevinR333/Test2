#include "treasure.h"
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

void chests_restore(World *areas, int n) {
  // shutters ($78-$7b) open when a room's enemies are beaten or its switches pressed; until those are
  // in, they stand open (doorController.s turns them into $a0). Key and boss doors stay locked.
  for (int a = 0; a < n; a++) {
    World *w = &areas[a];
    if (w->kind == AREA_OVERWORLD) continue;
    for (int ty = 0; ty < w->base.h; ty++)
      for (int tx = 0; tx < w->base.w; tx++) {
        uint8_t mt = w->mt[ty * w->base.w + tx];
        if (mt >= 0x78 && mt <= 0x7b) unlock_tile(w, tx, ty);
      }
  }
  for (int i = 0; i < n_chests; i++) {
    if (chests[i].area < 0) continue;
    World *w = &areas[chests[i].area];
    if (game.chests_opened[i >> 3] >> (i & 7) & 1) { open_chest_tile(w, chests[i].tx, chests[i].ty); continue; }
    // chests the originals make appear (puzzle solved, room cleared) stand there from the start
    // until those puzzles are in, so nothing a dungeon needs is out of reach
    int r = room_of(w, chests[i].tx, chests[i].ty);
    if (r >= 0 && w->closed_chest_cell[r] != CELL_VOID && world_metatile(w, (float)(chests[i].tx * MT), (float)(chests[i].ty * MT)) != 0xf1)
      world_set_tile(w, chests[i].tx, chests[i].ty, w->closed_chest_cell[r], w->closed_chest_coll[r], 0xf1);
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
