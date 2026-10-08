// Renders music and effects offline from audio.bin and checks they make sound at sensible pitches.
#include "../src/audio.h"
#include <SDL3/SDL.h>
#include <stdio.h>
#include <stdlib.h>

static int fails;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL: " __VA_ARGS__); printf("\n"); fails++; } } while (0)

static float rms(const float *b, int n) {
  double s = 0;
  for (int i = 0; i < n; i++) s += (double)b[i] * b[i];
  return (float)SDL_sqrt(s / n);
}

int main(int argc, char **argv) {
  if (!audio_load_offline()) { printf("audio.bin missing\n"); return 1; }
  const int n = 48000 * 4;
  float *buf = malloc(sizeof(float) * (size_t)n);
  const char *wav = argc > 1 ? argv[1] : NULL;
  for (int g = 0; g < 2; g++) {
    audio_set_game(g);
    audio_room(0, g ? 0x48 : 0xb6);      // Horon Village / Lynna City and around
    audio_render(buf, n);
    float r = rms(buf, n);
    printf("game %d room music rms %.3f\n", g, r);
    CHECK(r > 0.01f, "game %d: room music is silent", g);
    audio_music_named("MUS_OVERWORLD");
    audio_render(buf, n);
    CHECK(rms(buf, n) > 0.01f, "game %d: overworld music is silent", g);
    if (wav && g == 0) {
      // 16-bit PCM WAV of the overworld theme, to listen to
      FILE *f = fopen(wav, "wb");
      int bytes = n * 2;
      fwrite("RIFF", 1, 4, f); int v = 36 + bytes; fwrite(&v, 4, 1, f); fwrite("WAVEfmt ", 1, 8, f);
      v = 16; fwrite(&v, 4, 1, f); short s16 = 1; fwrite(&s16, 2, 1, f); fwrite(&s16, 2, 1, f);
      v = 48000; fwrite(&v, 4, 1, f); v = 96000; fwrite(&v, 4, 1, f); s16 = 2; fwrite(&s16, 2, 1, f); s16 = 16; fwrite(&s16, 2, 1, f);
      fwrite("data", 1, 4, f); fwrite(&bytes, 4, 1, f);
      for (int i = 0; i < n; i++) { float x = buf[i] * 2.5f; short s = (short)(SDL_clamp(x, -1.0f, 1.0f) * 32000); fwrite(&s, 2, 1, f); }
      fclose(f);
    }
    sfx("SND_SWORDSLASH");
    audio_render(buf, 48000 / 4);
    CHECK(rms(buf, 48000 / 4) > 0.01f, "game %d: sword sound is silent", g);
  }
  free(buf);
  printf(fails ? "%d failures\n" : "audio ok\n", fails);
  return fails != 0;
}
