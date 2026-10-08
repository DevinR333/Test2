#pragma once
#include "world.h"

// The originals' room flags (a byte per room, wRoomFlags): bits 0-3 mark a key door or bombed wall
// opened on that side (up, right, down, left), bit 7 something else broken open for good. Rooms
// remember them, and each time a room is laid out the tiles they name are swapped
// (code/{game}/tileSubstitutions.s applyStandardTileSubstitutions). As in the games, a house shares
// its byte with the overworld screen of the same number (flagLocationGroupTable).

bool roomflags_load(void);
uint8_t *room_flags(int which, int group, int room);
// Swaps the tiles of every screen of an area for the flags set in its rooms.
void roomflags_apply(World *w);
// Link is on this screen: it's been visited (ROOMFLAG_VISITED; what that changes shows next time).
void roomflags_visit(World *w, float px, float py);
// A tile broken by a mode with bit 7 set (updateRoomFlagsForBrokenTile): sets the flags it names.
void roomflags_tile_broken(World *w, int tx, int ty, uint8_t tile);
// A key door opened, Link facing `dir` (setRoomFlagsForUnlockedKeyDoor): this room and the one
// behind the door.
void roomflags_key_door(World *w, int tx, int ty, int dir);
