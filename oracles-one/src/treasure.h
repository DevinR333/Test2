#pragma once
#include "game.h"
#include "world.h"

// Treasures (constants/common/treasure.s) as both games number them, given to the one shared Link.
enum {
  TREASURE_SHIELD = 0x01, TREASURE_BOMBS = 0x03, TREASURE_CANE = 0x04, TREASURE_SWORD = 0x05,
  TREASURE_BOOMERANG = 0x06, TREASURE_ROD = 0x07, TREASURE_MAGNET_GLOVES = 0x08,
  TREASURE_SWITCH_HOOK = 0x0a, TREASURE_BIGGORON_SWORD = 0x0c, TREASURE_BOMBCHUS = 0x0d,
  TREASURE_FLUTE = 0x0e, TREASURE_SHOOTER = 0x0f, TREASURE_HARP = 0x11, TREASURE_SLINGSHOT = 0x13,
  TREASURE_SHOVEL = 0x15, TREASURE_BRACELET = 0x16, TREASURE_FEATHER = 0x17, TREASURE_SATCHEL = 0x19,
  TREASURE_FOOLS_ORE = 0x1e, TREASURE_EMBER_SEEDS = 0x20, TREASURE_MYSTERY_SEEDS = 0x24,
  TREASURE_RUPEES = 0x28, TREASURE_HEART_REFILL = 0x29, TREASURE_HEART_CONTAINER = 0x2a,
  TREASURE_HEART_PIECE = 0x2b, TREASURE_RING_BOX = 0x2c, TREASURE_RING = 0x2d, TREASURE_FLIPPERS = 0x2e,
  TREASURE_SMALL_KEY = 0x30, TREASURE_BOSS_KEY = 0x31, TREASURE_COMPASS = 0x32, TREASURE_MAP = 0x33,
  TREASURE_GASHA_SEED = 0x34, TREASURE_ORE_CHUNKS = 0x37, TREASURE_ESSENCE = 0x40,
  TREASURE_BOMB_UPGRADE = 0x61, TREASURE_SATCHEL_UPGRADE = 0x62,
};

// Gives a treasure found in `game`'s world (dungeon: which dungeon, for keys, maps and compasses).
void treasure_give(WorldId game, int dungeon, int treasure, int param);
int rupee_value(int param);
// Whether Link already has a treasure (at that level, for leveled items).
bool treasure_owned(WorldId game, int treasure, int param);       // code/bank0.s getRupeeValue

// Chests (chests.bin) and the tiles that keys open, placed into the loaded areas.
bool chests_load(World *areas, int n);
// Puts opened chests and unlocked doors back after loading a save.
void chests_restore(World *areas, int n);

// Changes a tile for good (a bombed wall, an opened door): remembered in the save.
void tile_change_for_good(World *areas, World *w, int tx, int ty, uint8_t mt);

// Shutters close behind Link in a room whose enemies are up and open when they're beaten; chests
// that the originals make appear show up then. Called every frame; _enter when arriving in an area.
void room_events_update(World *areas, int n, World *w, float lx, float ly);
void room_events_enter(World *areas, World *w);

// Shops (shops.bin): what a shop item (interaction $47, Subrosia's $81) sells.
bool shops_load(void);
// Whether a shop item has been bought for good (it no longer stands in the shop).
bool shop_sold_out(int game, int id, int subid);
// Buying it: returns the message (price, not enough money, bought), or NULL if it isn't for sale.
// confirm false only asks; true pays and gives.
const char *shop_offer(int game, int id, int subid, bool confirm, bool *bought);

// Link pressed A facing (px, py): opens a chest there. Returns the pickup text, or NULL.
const char *chest_open_at(World *areas, int n, World *w, float px, float py);
// Link has pushed against (px, py) for a while: unlocks a key block or key door there with a key
// of that dungeon. Returns a message when he has no key, NULL otherwise.
const char *keydoor_push(World *areas, int n, World *w, float px, float py, int dir);
