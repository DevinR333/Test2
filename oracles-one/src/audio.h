#pragma once
#include <stdbool.h>

// Music and sound effects: the games' own sound data (audio.bin from tools/extract_audio.py), read
// command by command the way their sound engine (code/audio.s) does, and played on a synth with
// the Game Boy's four voices: two square waves, the wave channel and noise.

bool audio_init(void);
void audio_shutdown(void);
// Which game's sound data plays (Holodrum: Seasons', Labrynna: Ages').
void audio_set_game(int game);
// The music of a room (musicAssignments.s); changes only when it differs from what's playing.
void audio_room(int group, int room);
// A sound by its constant name (MUS_* or SND_*), e.g. "SND_SWORDSLASH".
void sfx(const char *name);
void audio_music_named(const char *name);
void audio_volume(float music, float effects);
// For tests: loads the data without a sound device and renders mono 48 kHz samples.
bool audio_load_offline(void);
void audio_render(float *out, int frames);
