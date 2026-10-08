#include "rings.h"
#include "game.h"
#include "gfx.h"
#include <string.h>

static char names[RING_COUNT][24], descs[RING_COUNT][64];
static int regen_timer;

bool rings_load(void) {
  size_t size;
  Uint8 *d = asset_load("rings.bin", &size);
  if (!d || size < RING_COUNT * 88) { SDL_free(d); return false; }
  for (int i = 0; i < RING_COUNT; i++) {
    memcpy(names[i], d + i * 88, 23);
    memcpy(descs[i], d + i * 88 + 24, 63);
  }
  SDL_free(d);
  return true;
}

const char *ring_name(int r) { return r >= 0 && r < RING_COUNT ? names[r] : ""; }
const char *ring_desc(int r) { return r >= 0 && r < RING_COUNT ? descs[r] : ""; }
bool ring_worn(int r) { return game.ring_worn == r; }

int ring_sword_damage(int d) {
  switch (game.ring_worn) {
  case RING_POWER_L1: return d + 1;
  case RING_POWER_L2: return d + 2;
  case RING_POWER_L3: return d + 3;
  case RING_ARMOR_L1: case RING_ARMOR_L2: case RING_ARMOR_L3: return d > 1 ? d - 1 : 1;
  case RING_RED: return d * 2;
  case RING_GREEN: return d * 3 / 2;
  case RING_CURSED: return d > 1 ? d / 2 : 1;
  case RING_DBL_EDGED: return d * 2;
  default: return d;
  }
}

int ring_damage_taken(int d) {
  switch (game.ring_worn) {
  case RING_POWER_L1: return d + 1;
  case RING_POWER_L2: return d + 2;
  case RING_POWER_L3: return d + 4;
  case RING_ARMOR_L1: return d > 1 ? d - 1 : 1;
  case RING_ARMOR_L2: return d > 2 ? d - 2 : 1;
  case RING_ARMOR_L3: return d > 3 ? d - 3 : 1;
  case RING_BLUE: return d > 1 ? d / 2 : 1;
  case RING_GREEN: return d * 3 / 4 > 0 ? d * 3 / 4 : 1;
  case RING_CURSED: return d * 2;
  case RING_PROTECTION: return 4;                   // always one heart
  default: return d;
  }
}

float ring_knockback(float push) { return game.ring_worn == RING_STEADFAST ? push * 0.3f : push; }
int ring_rupee_drop(int r) { return game.ring_worn == RING_RED_JOY || game.ring_worn == RING_GOLD_JOY ? r * 2 : r; }
int ring_heart_drop(int q) { return game.ring_worn == RING_BLUE_JOY || game.ring_worn == RING_GOLD_JOY ? q * 2 : q; }
int ring_bomb_damage(int d) { return game.ring_worn == RING_BLAST ? d + 2 : d; }
int ring_boomerang_damage(int d) {
  return d + (game.ring_worn == RING_RANG_L1 ? 1 : game.ring_worn == RING_RANG_L2 ? 2 : 0);
}
float ring_swim_speed(void) { return game.ring_worn == RING_SWIMMERS ? 1.5f : 1.0f; }

void rings_update(void) {
  int every = game.ring_worn == RING_HEART_L1 ? 60 * 6 : game.ring_worn == RING_HEART_L2 ? 60 * 2 : 0;
  if (!every) { regen_timer = 0; return; }
  if (++regen_timer < every) return;
  regen_timer = 0;
  if (game.health > 0 && game.health < game.max_hearts * 4) game.health++;
}

void ring_wear(int r) {
  if (r < 0 || r >= RING_COUNT) return;
  game.ring_worn = (uint8_t)r;
  for (int i = 0; i < 5; i++) if (game.ring_box[i] == r) return;
  for (int i = 0; i < 5; i++) if (game.ring_box[i] == 0xff) { game.ring_box[i] = (uint8_t)r; return; }
  memmove(game.ring_box, game.ring_box + 1, 4);     // full: the oldest makes room
  game.ring_box[4] = (uint8_t)r;
}

int ring_cycle(void) {
  int box[5], n = 0, at = -1;
  for (int i = 0; i < 5; i++)
    if (game.ring_box[i] < RING_COUNT) { if (game.ring_box[i] == game.ring_worn) at = n; box[n++] = game.ring_box[i]; }
  if (!n) return game.ring_worn < RING_COUNT ? game.ring_worn : -1;
  int next = at + 1;                          // -1 (none) -> first; last -> none
  if (game.ring_worn >= RING_COUNT) next = 0;
  if (next >= n) { game.ring_worn = 0xff; return -1; }
  game.ring_worn = (uint8_t)box[next];
  return box[next];
}
