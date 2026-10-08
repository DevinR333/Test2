// Every warp of both games leads to a room the game data has (needs assets/: run from the build dir).
#include "../src/world.h"
#include <stdio.h>
#include <stdlib.h>

int main(void) {
  World *areas;
  int n = world_load_all(&areas);
  if (!n || !warps_load()) { printf("assets missing\n"); return 1; }
  int total, ok = warps_resolvable(areas, n, &total);
  printf("%d areas; %d of %d warps lead somewhere\n", n, ok, total);
  return ok * 100 < total * 97;   // a few point at rooms the originals never use
}
