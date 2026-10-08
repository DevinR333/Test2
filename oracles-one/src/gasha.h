#pragma once
#include <stdbool.h>

// Gasha Seeds (gashaSpot.s): planted in a Gasha spot, the tree grows as Link beats enemies; its nut
// holds a prize drawn by the originals' weights for the spot's rank and how far the game has gone.
bool gasha_load(void);
// A at a Gasha spot (interaction $b6): plants a seed, says the tree is growing, or harvests the nut.
const char *gasha_talk(int game, int group, int room, int subid);
// An enemy beaten: the planted trees grow, and prizes get better over the game.
void gasha_kill(void);
