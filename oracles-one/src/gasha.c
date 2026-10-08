#include "gasha.h"
#include "game.h"
#include "gfx.h"
#include "rings.h"
#include "treasure.h"
#include <stdio.h>
#include <string.h>

#define GROW_KILLS 40                 // enemies to beat before a planted tree bears its nut

static uint8_t ranks[2][16];
static uint8_t weights[2][5][5][10];   // [game][rank][maturity row][prize]
static uint8_t tiers[5][16], tier_n[5];
static Uint32 rng = 0x9e3779b9u;

static int rnd(int n) { rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return (int)(rng % (Uint32)n); }

bool gasha_load(void) {
  size_t size;
  Uint8 *d = asset_load("gasha.bin", &size);
  if (!d) return false;
  const Uint8 *p = d, *end = d + size;
  for (int g = 0; g < 2; g++) {
    if (p + 16 + 250 > end) { SDL_free(d); return false; }
    memcpy(ranks[g], p, 16);
    p += 16;
    memcpy(weights[g], p, 250);
    p += 250;
  }
  for (int t = 0; t < 5 && p < end; t++) {
    tier_n[t] = SDL_min(*p++, 16);
    for (int i = 0; i < tier_n[t] && p < end; i++) tiers[t][i] = *p++;
  }
  SDL_free(d);
  rng ^= (Uint32)SDL_GetTicks();
  return true;
}

void gasha_kill(void) {
  if (game.gasha_maturity < 300) game.gasha_maturity++;
  for (int i = 0; i < 16; i++)
    if (game.gasha_spots[i].planted && game.gasha_spots[i].kills < 255) game.gasha_spots[i].kills++;
}

// gashaSpot.s's maturity thresholds: 300, 200, 120, 40 (row 0 is the best).
static int maturity_row(void) {
  int m = game.gasha_maturity;
  return m >= 300 ? 0 : m >= 200 ? 1 : m >= 120 ? 2 : m >= 40 ? 3 : 4;
}

static const char *prize(int g, int subid) {
  static char msg[120];
  const uint8_t *w = weights[g][ranks[g][subid & 15] % 5][maturity_row()];
  int total = 0;
  for (int i = 0; i < 10; i++) total += w[i];
  int pick = 9, r = total ? rnd(total) : 0;
  for (int i = 0; i < 10; i++) { if (r < w[i]) { pick = i; break; } r -= w[i]; }
  switch (pick) {
  case 0: treasure_give((WorldId)g, 0, TREASURE_HEART_PIECE, 1); return "The Gasha Nut held a Piece of Heart!";
  case 1: case 2: case 3: case 4: case 5: {
    int t = pick - 1, ring = tier_n[t] ? tiers[t][rnd(tier_n[t])] : 0;
    for (int k = 0; k < 8 && (game.rings_owned >> ring & 1) && tier_n[t]; k++) ring = tiers[t][rnd(tier_n[t])];  // a new one if it can
    game.rings_owned |= 1ull << ring;
    snprintf(msg, sizeof msg, "The Gasha Nut held a ring: the %s!", ring_name(ring));
    return msg;
  }
  case 6: game.health = (int16_t)(game.max_hearts * 4); return "The Gasha Nut held a Potion! You feel restored.";
  case 7: game_add_rupees(200); return "The Gasha Nut held 200 Rupees!";
  case 8: game_heal(24); return "A fairy flew out of the Gasha Nut!";
  default: game_heal(20); return "The Gasha Nut held five Hearts!";
  }
}

const char *gasha_talk(int g, int group, int room, int subid) {
  for (int i = 0; i < 16; i++) {
    GashaSpot *s = &game.gasha_spots[i];
    if (!s->planted || s->game != g || s->group != group || s->room != room) continue;
    if (s->kills < GROW_KILLS) return "A Gasha Tree is growing here. Beat more monsters and it will bear a nut.";
    s->planted = false;
    return prize(g, subid);
  }
  if (!game.gasha_seeds) return "This soft soil looks like a good place to plant a Gasha Seed.";
  for (int i = 0; i < 16; i++) {
    GashaSpot *s = &game.gasha_spots[i];
    if (s->planted) continue;
    *s = (GashaSpot){true, (uint8_t)g, (uint8_t)group, (uint8_t)room, 0};
    game.gasha_seeds--;
    return "You planted a Gasha Seed. Beat monsters and it will grow.";
  }
  return "You can't tend any more Gasha Trees.";
}
