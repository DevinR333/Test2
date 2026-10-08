#pragma once
#include <stdbool.h>

// The 64 rings both games share (constants/common/rings.s), worn from the Start menu's ring page,
// and what they do. A ring found in Holodrum works the same in Labrynna.

enum {
  RING_FRIENDSHIP, RING_POWER_L1, RING_POWER_L2, RING_POWER_L3, RING_ARMOR_L1, RING_ARMOR_L2, RING_ARMOR_L3,
  RING_RED, RING_BLUE, RING_GREEN, RING_CURSED, RING_EXPERTS, RING_BLAST, RING_RANG_L1, RING_GBA_TIME, RING_MAPLES,
  RING_STEADFAST, RING_PEGASUS, RING_TOSS, RING_HEART_L1, RING_HEART_L2, RING_SWIMMERS, RING_CHARGE, RING_LIGHT_L1,
  RING_LIGHT_L2, RING_BOMBERS, RING_GREEN_LUCK, RING_BLUE_LUCK, RING_GOLD_LUCK, RING_RED_LUCK, RING_GREEN_HOLY,
  RING_BLUE_HOLY, RING_RED_HOLY, RING_SNOWSHOE, RING_ROCS, RING_QUICKSAND, RING_RED_JOY, RING_BLUE_JOY, RING_GOLD_JOY,
  RING_GREEN_JOY, RING_DISCOVERY, RING_RANG_L2, RING_OCTO, RING_MOBLIN, RING_LIKE_LIKE, RING_SUBROSIAN,
  RING_FIRST_GEN, RING_SPIN, RING_BOMBPROOF, RING_ENERGY, RING_DBL_EDGED, RING_GBA_NATURE, RING_SLAYERS, RING_RUPEE,
  RING_VICTORY, RING_SIGN, RING_HUNDREDTH, RING_WHISP, RING_GASHA, RING_PEACE, RING_ZORA, RING_FIST, RING_WHIMSICAL,
  RING_PROTECTION, RING_COUNT
};

bool rings_load(void);
const char *ring_name(int ring);
const char *ring_desc(int ring);
bool ring_worn(int ring);

// What the worn ring does to a number.
int ring_sword_damage(int base);
int ring_damage_taken(int damage);
float ring_knockback(float push);
int ring_rupee_drop(int rupees);
int ring_heart_drop(int quarters);
int ring_bomb_damage(int damage);
int ring_boomerang_damage(int damage);
float ring_swim_speed(void);
// Wears a ring and keeps it in the ring box (the quick-swap list; the oldest drops out when full).
void ring_wear(int ring);
// Select during play: the next ring in the box (after the last one, no ring). Returns the ring worn
// now, or -1 for none.
int ring_cycle(void);
// Per frame: heart rings mend Link slowly.
void rings_update(void);
