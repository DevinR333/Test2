// Checks the shared-state rules: hearts and rupees are one pool, extra hearts turn into rupees.
#include "../src/game.h"
#include "../src/secrets.h"
#include <stdio.h>

static int failures;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

int main(void) {
  game_new();
  CHECK(!game.linked);
  CHECK(game.max_hearts == 3 && game.health == 12);

  // pieces of heart make a container every fourth piece
  for (int i = 0; i < 3; i++) game_give_heart_piece();
  CHECK(game.max_hearts == 3 && game.heart_pieces == 3);
  game_give_heart_piece();
  CHECK(game.max_hearts == 4 && game.heart_pieces == 0 && game.health == 16);

  // with every heart earned, containers are worth 300 rupees and pieces 150
  while (game.max_hearts < MAX_HEARTS) game_give_heart_container();
  CHECK(game.rupees == 0);
  game_give_heart_container();
  CHECK(game.max_hearts == MAX_HEARTS && game.rupees == 300);
  game_give_heart_piece();
  CHECK(game.rupees == 450 && game.heart_pieces == 0);
  for (int i = 0; i < 5; i++) game_give_heart_container();
  CHECK(game.rupees == MAX_RUPEES);

  // items keep their own world's page; shared items go on the page of the world they were found in
  game_new();
  game.world = WORLD_LABRYNNA;
  game_give_item(ITEM_ROCS_CAPE);
  game_give_item(ITEM_BOOMERANG);
  game_give_item(ITEM_SWITCH_HOOK);
  CHECK(game.page[PAGE_SEASONS][0] == ITEM_ROCS_CAPE);
  CHECK(game.page[PAGE_AGES][0] == ITEM_BOOMERANG);
  CHECK(game.page[PAGE_AGES][1] == ITEM_SWITCH_HOOK);
  game_give_item(ITEM_BOOMERANG);       // the magical boomerang upgrades in place
  CHECK(game.item_level[ITEM_BOOMERANG] == 2 && game.page[PAGE_AGES][2] == ITEM_NONE);

  // the final boss needs all 16 essences
  game.essences[PAGE_SEASONS] = 0xff;
  CHECK(!game_all_essences());
  game.essences[PAGE_AGES] = 0xff;
  CHECK(game_all_essences());

  // secrets: hearing one is enough, its taker recognises it and rewards once
  game_new();
  CHECK(!secret_redeem(SECRET_FAIRY));             // not heard yet
  secret_hear(SECRET_FAIRY);
  CHECK(secret_redeem(SECRET_FAIRY) && game.max_hearts == 4);
  CHECK(!secret_redeem(SECRET_FAIRY) && game.max_hearts == 4);
  secret_hear(SECRET_KING_ZORA);
  CHECK(secret_redeem(SECRET_KING_ZORA) && game.item_level[ITEM_SWORD] == 2);
  secret_hear(SECRET_MAMAMU);
  CHECK(secret_redeem(SECRET_MAMAMU) && (game.rings_owned >> 0x21 & 1));
  secret_hear(SECRET_PIRATE);
  CHECK(secret_redeem(SECRET_PIRATE) && game.bomb_max == 20);
  secret_hear(SECRET_RUUL);
  CHECK(secret_redeem(SECRET_RUUL) && game.ring_box_size == 3);

  printf(failures ? "%d failures\n" : "all passed\n", failures);
  return failures != 0;
}
