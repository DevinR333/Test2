#include "secrets.h"

// Takers (by their script labels) and rewards from the scripts that set GLOBALFLAG_DONE_*_SECRET
// (scripts/seasons/scripts.s, scripts2.s; scripts/ages/scripts.s, scriptHelper.s).
const SecretInfo secret_info[SECRET_COUNT] = {
  [SECRET_CLOCK_SHOP] = {"Clock Shop Secret", WORLD_LABRYNNA, WORLD_HOLODRUM, "Troy, clock shop",     REWARD_SWORD_UPGRADE, 0},
  [SECRET_GRAVEYARD]  = {"Graveyard Secret",  WORLD_LABRYNNA, WORLD_HOLODRUM, "Ghini, graveyard",     REWARD_HEART_CONTAINER, 0},
  [SECRET_SUBROSIAN]  = {"Subrosian Secret",  WORLD_LABRYNNA, WORLD_HOLODRUM, "Golden Cave Subrosian", REWARD_BOMBCHUS, 0},
  [SECRET_DIVER]      = {"Diver Secret",      WORLD_LABRYNNA, WORLD_HOLODRUM, "Master Diver",         REWARD_RING, 0x15},   // Swimmer's Ring
  [SECRET_SMITH]      = {"Smith Secret",      WORLD_LABRYNNA, WORLD_HOLODRUM, "Subrosian Smith",      REWARD_SHIELD_UPGRADE, 0},
  [SECRET_PIRATE]     = {"Pirate Secret",     WORLD_LABRYNNA, WORLD_HOLODRUM, "Unlucky Sailor",       REWARD_BOMB_UPGRADE, 0},
  [SECRET_TEMPLE]     = {"Temple Secret",     WORLD_LABRYNNA, WORLD_HOLODRUM, "Temple Great Fairy",   REWARD_RING, 0x13},   // Heart Ring L-1
  [SECRET_DEKU]       = {"Deku Secret",       WORLD_LABRYNNA, WORLD_HOLODRUM, "Deku Scrub",           REWARD_SATCHEL_UPGRADE, 0},
  [SECRET_BIGGORON]   = {"Biggoron Secret",   WORLD_LABRYNNA, WORLD_HOLODRUM, "Biggoron",             REWARD_BIGGORON_SWORD, 0},
  [SECRET_RUUL]       = {"Ruul Secret",       WORLD_LABRYNNA, WORLD_HOLODRUM, "Lady in mayor's house", REWARD_RING_BOX_UPGRADE, 0},
  [SECRET_KING_ZORA]  = {"King Zora Secret",  WORLD_HOLODRUM, WORLD_LABRYNNA, "King Zora",            REWARD_SWORD_UPGRADE, 0},
  [SECRET_FAIRY]      = {"Fairy Secret",      WORLD_HOLODRUM, WORLD_LABRYNNA, "Forest Fairy",         REWARD_HEART_CONTAINER, 0},
  [SECRET_TROY]       = {"Troy Secret",       WORLD_HOLODRUM, WORLD_LABRYNNA, "Troy, target carts",   REWARD_BOMBCHUS, 0},
  [SECRET_PLEN]       = {"Plen Secret",       WORLD_HOLODRUM, WORLD_LABRYNNA, "Mayor Plen",           REWARD_RING, 0x2f},   // Spin Ring
  [SECRET_LIBRARY]    = {"Library Secret",    WORLD_HOLODRUM, WORLD_LABRYNNA, "Old man, library",     REWARD_SHIELD_UPGRADE, 0},
  [SECRET_TOKAY]      = {"Tokay Secret",      WORLD_HOLODRUM, WORLD_LABRYNNA, "Tokay game manager",   REWARD_BOMB_UPGRADE, 0},
  [SECRET_MAMAMU]     = {"Mamamu Secret",     WORLD_HOLODRUM, WORLD_LABRYNNA, "Mamamu Yan",           REWARD_RING, 0x21},   // Snowshoe Ring
  [SECRET_TINGLE]     = {"Tingle Secret",     WORLD_HOLODRUM, WORLD_LABRYNNA, "Tingle",               REWARD_SATCHEL_UPGRADE, 0},
  [SECRET_ELDER]      = {"Elder Secret",      WORLD_HOLODRUM, WORLD_LABRYNNA, "Goron Elder, gallery", REWARD_BIGGORON_SWORD, 0},
  [SECRET_SYMMETRY]   = {"Symmetry Secret",   WORLD_HOLODRUM, WORLD_LABRYNNA, "Symmetry City NPC"   , REWARD_RING_BOX_UPGRADE, 0},
};

void secret_hear(Secret s) { game.secrets_earned |= 1u << s; }
bool secret_known(Secret s) { return game.secrets_earned >> s & 1; }
bool secret_done(Secret s) { return game.secrets_told >> s & 1; }

static void give(const SecretInfo *si) {
  switch (si->reward) {
  case REWARD_SWORD_UPGRADE: case REWARD_SHIELD_UPGRADE:
    game_give_item(si->reward == REWARD_SWORD_UPGRADE ? ITEM_SWORD : ITEM_SHIELD);
    break;
  case REWARD_HEART_CONTAINER: game_give_heart_container(); break;
  case REWARD_BOMBCHUS:
    game_give_item(ITEM_BOMBCHUS);
    game.bombchus = 10;
    break;
  case REWARD_RING: game.rings_owned |= 1ull << si->ring; break;
  case REWARD_BOMB_UPGRADE:
    game.bomb_max = (uint8_t)(game.bomb_max < 10 ? 20 : game.bomb_max + 10);
    game.bombs = game.bomb_max;
    break;
  case REWARD_SATCHEL_UPGRADE: game_give_item(ITEM_SEED_SATCHEL); break;
  case REWARD_BIGGORON_SWORD: game.biggoron_sword = true; break;
  case REWARD_RING_BOX_UPGRADE: if (game.ring_box_size < 5) game.ring_box_size = game.ring_box_size < 3 ? 3 : 5; break;
  }
}

bool secret_redeem(Secret s) {
  if (s >= SECRET_COUNT || !secret_known(s) || secret_done(s)) return false;
  give(&secret_info[s]);
  game.secrets_told |= 1u << s;
  return true;
}
