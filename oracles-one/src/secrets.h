#pragma once
#include "game.h"

// The originals' linked secrets, without the typing. Hearing a secret from the NPC who tells it
// marks it as known; walking up to the NPC who would take it is enough: they recognise it and hand
// over their reward (the same rewards as the originals' scripts). Twenty secrets, the ten named
// after Holodrum's NPCs (GLOBALFLAG_*_CLOCK_SHOP_SECRET...) and the ten after Labrynna's.

typedef enum {
  // rewarded in Holodrum
  SECRET_CLOCK_SHOP, SECRET_GRAVEYARD, SECRET_SUBROSIAN, SECRET_DIVER, SECRET_SMITH,
  SECRET_PIRATE, SECRET_TEMPLE, SECRET_DEKU, SECRET_BIGGORON, SECRET_RUUL,
  // rewarded in Labrynna
  SECRET_KING_ZORA, SECRET_FAIRY, SECRET_TROY, SECRET_PLEN, SECRET_LIBRARY,
  SECRET_TOKAY, SECRET_MAMAMU, SECRET_TINGLE, SECRET_ELDER, SECRET_SYMMETRY,
  SECRET_COUNT
} Secret;

typedef enum {
  REWARD_SWORD_UPGRADE, REWARD_HEART_CONTAINER, REWARD_BOMBCHUS, REWARD_RING,
  REWARD_SHIELD_UPGRADE, REWARD_BOMB_UPGRADE, REWARD_SATCHEL_UPGRADE, REWARD_BIGGORON_SWORD,
  REWARD_RING_BOX_UPGRADE,
} RewardKind;

typedef struct {
  const char *name;           // as the originals name it
  WorldId told_in;            // where the secret is heard
  WorldId taken_in;           // where the NPC who recognises it lives
  const char *taker;          // that NPC
  RewardKind reward;
  uint8_t ring;               // REWARD_RING: which ring (constants/common/rings.s)
} SecretInfo;

extern const SecretInfo secret_info[SECRET_COUNT];

// The NPC who tells a secret calls this when Link talks to them.
void secret_hear(Secret s);
bool secret_known(Secret s);
bool secret_done(Secret s);
// The NPC who takes it calls this when Link walks up and talks: when Link knows the secret and
// hasn't been rewarded yet, gives the reward and returns true (the NPC then says their thanks).
bool secret_redeem(Secret s);
