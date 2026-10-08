#pragma once
#include "world.h"
#include <stdbool.h>
#include <stdint.h>

// One game: Holodrum and Labrynna share Link, his hearts, rupees and every item. Each item still
// belongs to the inventory page of the game it comes from (the pause menu shows both pages).

#define MAX_HEARTS 16            // the most either original game allows
#define MAX_RUPEES 999
#define HEART_CONTAINER_RUPEES 300   // a heart container found with every heart already earned
#define HEART_PIECE_RUPEES 150

typedef enum {
  ITEM_NONE,
  // Holodrum (Seasons) page
  ITEM_SWORD, ITEM_SHIELD, ITEM_BOMBS, ITEM_ROD_OF_SEASONS, ITEM_SEED_SATCHEL, ITEM_SLINGSHOT,
  ITEM_ROCS_FEATHER, ITEM_ROCS_CAPE, ITEM_BOOMERANG, ITEM_MAGNETIC_GLOVES, ITEM_SHOVEL,
  ITEM_POWER_BRACELET, ITEM_STRANGE_FLUTE, ITEM_FOOLS_ORE,
  // Labrynna (Ages) page
  ITEM_CANE_OF_SOMARIA, ITEM_SWITCH_HOOK, ITEM_SEED_SHOOTER, ITEM_HARP_OF_AGES, ITEM_POWER_GLOVES,
  ITEM_BOMBCHUS, ITEM_MERMAID_SUIT,
  ITEM_COUNT
} Item;

typedef enum { PAGE_SEASONS, PAGE_AGES, PAGE_COUNT } Page;

typedef struct {
  const char *name;
  Page page;
  uint8_t icon, icon2, pal, pal2;   // treasureDisplayData: left/right 8x16 icon columns and palettes
  uint8_t max_level;
} ItemInfo;

extern const ItemInfo item_info[ITEM_COUNT];

#define PAGE_SLOTS 16

typedef struct {
  uint32_t version;
  // Link, shared by both worlds
  int16_t health;                 // quarter hearts
  uint8_t max_hearts;             // whole heart containers
  uint8_t heart_pieces;           // 0-3 towards the next container
  uint16_t rupees;
  uint8_t item_level[ITEM_COUNT]; // 0: not owned
  uint8_t page[PAGE_COUNT][PAGE_SLOTS];  // inventory layout per page
  uint8_t equip_a, equip_b;
  // ammunition and collectables, one count whichever world they were found in
  uint8_t seeds[5];               // ember, scent, pegasus, gale, mystery
  uint8_t bombs, bomb_max, bombchus;
  uint8_t seed_selected;          // the seed the satchel and shooters use (SEED_*)
  bool biggoron_sword;
  uint8_t gasha_seeds;
  uint16_t ore_chunks;
  // rings: both games' 64 rings are the same set, so one collection and one ring box
  uint64_t rings_owned, rings_appraised;
  uint8_t ring_box_size;
  uint8_t ring_box[5];            // ring ids, 0xff empty
  uint8_t ring_worn;              // 0xff none
  // treasures by the originals' numbers, per game (keys, maps, quest items...)
  uint8_t treasures[WORLD_COUNT][32];
  uint8_t small_keys[WORLD_COUNT][16];       // per dungeon
  uint16_t boss_keys[WORLD_COUNT], dungeon_maps[WORLD_COUNT], compasses[WORLD_COUNT];
  // the world as Link changed it: chests opened (by chests.bin index), doors and blocks unlocked
  uint8_t chests_opened[64];
  uint16_t n_unlocked;
  uint16_t unlocked_area[256], unlocked_tile[256];
  uint8_t unlocked_mt[256];       // what each became (opened doors, bombed walls)
  // story
  bool linked;                    // a linked game (new files start as a normal game)
  uint8_t essences[PAGE_COUNT];   // bit per essence (8 per game)
  bool onox_beaten, veran_beaten; // each game's last boss (both open the way to Twinrova and Ganon)
  bool ganon_beaten;
  uint32_t secrets_earned;        // a bit per Secret heard: its taker recognises it (secrets.h)
  uint32_t secrets_told;          // a bit per Secret whose reward Link has had
  // where Link is
  uint8_t world;                  // the game whose world it is (WorldId)
  uint16_t area;                  // the area (overworld, dungeon floor, room) in areas.bin
  float x, y;
  int8_t dir;
} GameState;

extern GameState game;

void game_new(void);
bool game_load(void);
bool game_save(void);

void game_give_item(Item item);
// Heart containers and pieces: past MAX_HEARTS they turn into rupees.
void game_give_heart_container(void);
void game_give_heart_piece(void);
void game_add_rupees(int n);
void game_heal(int quarters);
bool game_all_essences(void);     // the final boss opens only with all 16
