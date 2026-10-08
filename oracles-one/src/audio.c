#include "audio.h"
#include "gfx.h"
#include <SDL3/SDL.h>
#include <stdlib.h>
#include <string.h>

#define RATE 48000
#define TICK_HZ 59.73f               // the engine runs once a frame
#define MAX_SOUNDS 256

typedef struct { uint8_t ch; uint32_t off; } ChanStart;
typedef struct { int n; ChanStart ch[8]; } Sound;
typedef struct { char name[24]; uint8_t id; } Named;
typedef struct {
  uint8_t *data;
  uint32_t size;
  Sound sounds[MAX_SOUNDS];
  int n_sounds;
  uint8_t rooms[8][256];
  Named *names;
  int n_names;
} GameAudio;

static GameAudio games[2];
static uint8_t waveforms[0x30][16];
static uint8_t noise_tab[32][3];
static int n_noise;

// One of the engine's eight channels: 0-1 music squares, 2-3 effect squares, 4 music wave,
// 5 effect wave, 6 music noise, 7 effect noise.
typedef struct {
  bool on;
  const uint8_t *data;
  uint32_t pc, size;
  int wait;
  int vol, env_start, env_end, duty, vibrato, pitch_shift, freq_mode;
  // what the voice is doing
  bool sounding;
  float freq, env_vol;
  int env_pace, env_dir, env_timer;
  int vib_ticks;
  uint8_t nr43;
} Chan;

static Chan chans[8];
static int game = 0;
static float music_gain = 0.8f, sfx_gain = 1.0f;
static float tick_acc;
static float phase[4];
static uint32_t lfsr = 0x7fff;
static float noise_acc;
static int current_music = -1;
static SDL_AudioStream *stream;
static SDL_Mutex *lock;

static float note_freq(int n) { return 440.0f * SDL_powf(2.0f, (float)(n + 24 - 69) / 12.0f); }

static void start_channel(int ch, const uint8_t *data, uint32_t size, uint32_t off) {
  Chan *c = &chans[ch];
  memset(c, 0, sizeof *c);
  if (off >= size) return;
  c->on = true;
  c->data = data;
  c->size = size;
  c->pc = off;
  c->vol = 0xf;
  c->wait = 0;
}

static void play_id(int id) {
  GameAudio *g = &games[game];
  if (id < 0 || id >= g->n_sounds || !g->data) return;
  const Sound *s = &g->sounds[id];
  bool music = false;
  for (int i = 0; i < s->n; i++) music |= s->ch[i].ch == 0 || s->ch[i].ch == 1 || s->ch[i].ch == 4 || s->ch[i].ch == 6;
  if (music) {
    current_music = id;
    chans[0].on = chans[1].on = chans[4].on = chans[6].on = false;
  }
  for (int i = 0; i < s->n; i++)
    if (s->ch[i].ch < 8) start_channel(s->ch[i].ch, g->data, g->size, s->ch[i].off);
}

static uint8_t next(Chan *c) { return c->pc < c->size ? c->data[c->pc++] : 0xff; }

static void start_env(Chan *c, int vol, int pace, int dir) {
  c->env_vol = (float)vol;
  c->env_pace = pace;
  c->env_dir = dir;
  c->env_timer = 0;
}

// Reads commands until the channel has something to wait on (code/audio.s doNextChannelCommand).
static void run_channel(int ch) {
  Chan *c = &chans[ch];
  for (int guard = 0; guard < 256 && c->on && c->wait <= 0; guard++) {
    uint8_t b = next(c);
    if (b >= 0xf0) {
      switch (b) {
      case 0xf0: {
        uint8_t a = next(c);
        if (ch == 7) start_env(c, a >> 4, a & 7, a & 8 ? 1 : -1);
        else { c->freq_mode = 1; c->duty = a >> 6; }
        break;
      }
      case 0xf1: case 0xf2: case 0xf3: break;
      case 0xf6: c->duty = next(c); break;
      case 0xf8: next(c); break;
      case 0xf9: c->vibrato = next(c); break;
      case 0xfd: c->pitch_shift = (int8_t)next(c); break;
      case 0xfe: {
        uint32_t off = next(c);
        off |= (uint32_t)next(c) << 8;
        off |= (uint32_t)next(c) << 16;
        if (off >= c->size) { c->on = false; return; }
        c->pc = off;
        break;
      }
      default: c->on = false; c->sounding = false; return;
      }
      continue;
    }
    if (b >= 0xe0) { c->env_start = b & 7; c->env_end = next(c) & 7; continue; }
    if (b >= 0xd0) { c->vol = b & 0x0f; continue; }
    // a note or rest, then how long it lasts
    if (ch >= 6) {
      int len = next(c);
      if (ch == 6) {
        bool found = false;
        for (int i = 0; i < n_noise; i++)
          if (noise_tab[i][0] == b) {
            c->nr43 = noise_tab[i][2];
            start_env(c, c->vol, noise_tab[i][1] & 7, noise_tab[i][1] & 8 ? 1 : -1);
            c->sounding = true;
            found = true;
            break;
          }
        if (!found && b == 0x60) c->sounding = false;
      } else {
        c->nr43 = b;
        c->sounding = true;
        if (c->env_vol <= 0 && c->env_dir < 0) start_env(c, 0xf, 2, -1);
      }
      c->wait = len;
      continue;
    }
    if (c->freq_mode) {                      // arbitrary frequency: high byte, low byte, length
      uint32_t x = (uint32_t)(b & 7) << 8 | next(c);
      c->wait = next(c);
      c->freq = 131072.0f / (float)(2048 - (x > 2047 ? 2047 : x));
      if (ch >= 4) c->freq *= 0.5f;
      c->sounding = true;
      start_env(c, c->vol, c->env_start, -1);
      continue;
    }
    int len = next(c);
    c->wait = len;
    if (b == 0x60) {                         // rest: the note fades out
      if (c->env_end) start_env(c, (int)c->env_vol, c->env_end, -1);
      else if (ch < 4) start_env(c, (int)c->env_vol, 1, -1);
      else c->sounding = false;
      continue;
    }
    if (b == 0x61) continue;                 // wait, the note goes on
    float f = note_freq(b);
    if (c->pitch_shift) {                    // shift in the frequency register's units
      float x = 2048.0f - 131072.0f / f + (float)c->pitch_shift;
      if (x < 2047) f = 131072.0f / (2048.0f - x);
    }
    c->freq = ch >= 4 ? f * 0.5f : f;        // the wave channel plays an octave below a square
    c->sounding = true;
    c->vib_ticks = 0;
    start_env(c, c->vol, c->env_start, -1);
  }
}

static void tick(void) {
  for (int ch = 0; ch < 8; ch++) {
    Chan *c = &chans[ch];
    if (!c->on) continue;
    if (c->wait > 0) c->wait--;
    run_channel(ch);
    c->vib_ticks++;
  }
  if (current_music >= 0 && !chans[0].on && !chans[1].on && !chans[4].on && !chans[6].on) current_music = -1;
}

// The envelope steps every pace/64 of a second, like NRx2.
static void step_env(Chan *c, float dt) {
  if (!c->env_pace) return;
  c->env_timer += 1;
  float step = (float)c->env_pace / 64.0f / dt;
  if ((float)c->env_timer >= step) {
    c->env_timer = 0;
    c->env_vol += (float)c->env_dir;
    if (c->env_vol < 0) c->env_vol = 0;
    if (c->env_vol > 15) c->env_vol = 15;
  }
}

static float voice(int hw, float dt) {
  // an effect channel takes over its voice from the music while it plays
  int m = hw == 0 ? 0 : hw == 1 ? 1 : hw == 2 ? 4 : 6, e = hw == 0 ? 2 : hw == 1 ? 3 : hw == 2 ? 5 : 7;
  Chan *c = chans[e].on ? &chans[e] : &chans[m];
  float gain = chans[e].on ? sfx_gain : music_gain;
  if (!c->on || !c->sounding) return 0;
  step_env(c, dt);
  float amp = c->env_vol / 15.0f;
  if (amp <= 0) return 0;
  if (hw < 2) {
    float f = c->freq;
    if (c->vibrato && c->vib_ticks > (c->vibrato >> 4) * 4)
      f *= 1.0f + 0.0025f * (float)(c->vibrato & 15) * SDL_sinf((float)c->vib_ticks * 0.6f);
    phase[hw] += f * dt;
    phase[hw] -= SDL_floorf(phase[hw]);
    static const float duty[4] = {0.125f, 0.25f, 0.5f, 0.75f};
    return (phase[hw] < duty[c->duty & 3] ? 1.0f : -1.0f) * amp * gain;
  }
  if (hw == 2) {
    phase[2] += c->freq * dt;
    phase[2] -= SDL_floorf(phase[2]);
    int i = (int)(phase[2] * 32) & 31, wf = c->duty < 0x30 ? c->duty : 0;
    uint8_t byte = waveforms[wf][i >> 1];
    int s = i & 1 ? byte & 15 : byte >> 4;
    return ((float)s / 7.5f - 1.0f) * ((float)c->vol / 15.0f) * gain;
  }
  // noise: NR43's clock shift, width and divisor drive a linear feedback shift register
  int shift = c->nr43 >> 4, width7 = c->nr43 & 8, div = c->nr43 & 7;
  float rate = 524288.0f / (div ? (float)div : 0.5f) / (float)(1 << (shift + 1));
  noise_acc += rate * dt;
  while (noise_acc >= 1) {
    noise_acc -= 1;
    uint32_t bit = (lfsr ^ (lfsr >> 1)) & 1;
    lfsr = (lfsr >> 1) | (bit << 14);
    if (width7) lfsr = (lfsr & ~0x40u) | (bit << 6);
  }
  return (lfsr & 1 ? -1.0f : 1.0f) * amp * gain * 0.7f;
}

static void render(float *buf, int n) {
  const float dt = 1.0f / RATE, per_tick = RATE / TICK_HZ;
  for (int i = 0; i < n; i++) {
    tick_acc += 1;
    if (tick_acc >= per_tick) { tick_acc -= per_tick; tick(); }
    float v = 0;
    for (int hw = 0; hw < 4; hw++) v += voice(hw, dt);
    buf[i] = v * 0.12f;
  }
}

void audio_render(float *out, int frames) { render(out, frames); }

static void SDLCALL feed(void *ud, SDL_AudioStream *s, int additional, int total) {
  (void)ud; (void)total;
  static float buf[1024];
  int frames = additional / (int)sizeof(float);
  while (frames > 0) {
    int n = frames > 1024 ? 1024 : frames;
    SDL_LockMutex(lock);
    render(buf, n);
    SDL_UnlockMutex(lock);
    SDL_PutAudioStreamData(s, buf, n * (int)sizeof(float));
    frames -= n;
  }
}

static bool load(void) {
  size_t size;
  Uint8 *d = asset_load("audio.bin", &size);
  if (!d) return false;
  const Uint8 *p = d, *end = d + size;
  if (size < 6 || memcmp(p, "OAUD", 4) != 0) { SDL_free(d); return false; }
  p += 6;
  for (int g = 0; g < 2; g++) {
    GameAudio *ga = &games[g];
    if (p + 4 > end) goto bad;
    ga->size = (uint32_t)(p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24);
    p += 4;
    if (p + ga->size > end) goto bad;
    ga->data = malloc(ga->size);
    memcpy(ga->data, p, ga->size);
    p += ga->size;
    ga->n_sounds = p[0] | p[1] << 8;
    p += 2;
    if (ga->n_sounds > MAX_SOUNDS) goto bad;
    for (int i = 0; i < ga->n_sounds; i++) {
      int n = *p++;
      ga->sounds[i].n = n > 8 ? 8 : n;
      for (int k = 0; k < n; k++, p += 5)
        if (k < 8) ga->sounds[i].ch[k] = (ChanStart){p[0], (uint32_t)(p[1] | p[2] << 8 | p[3] << 16 | (uint32_t)p[4] << 24)};
    }
    memcpy(ga->rooms, p, sizeof ga->rooms);
    p += sizeof ga->rooms;
    ga->n_names = p[0] | p[1] << 8;
    p += 2;
    ga->names = calloc((size_t)ga->n_names + 1, sizeof *ga->names);
    for (int i = 0; i < ga->n_names; i++, p += 25) {
      memcpy(ga->names[i].name, p, 23);
      ga->names[i].id = p[24];
    }
  }
  if (p + sizeof waveforms <= end) { memcpy(waveforms, p, sizeof waveforms); p += sizeof waveforms; }
  if (p < end) {
    n_noise = *p++;
    if (n_noise > 32) n_noise = 32;
    for (int i = 0; i < n_noise && p + 3 <= end; i++, p += 3) memcpy(noise_tab[i], p, 3);
  }
  SDL_free(d);
  return true;
bad:
  SDL_free(d);
  return false;
}

bool audio_load_offline(void) {
  if (!load()) return false;
  lock = SDL_CreateMutex();
  stream = (SDL_AudioStream *)1;       // lets play() run; never used as a stream offline
  return true;
}

bool audio_init(void) {
  if (!SDL_InitSubSystem(SDL_INIT_AUDIO)) { SDL_Log("audio: %s", SDL_GetError()); return false; }
  if (!load()) { SDL_Log("audio.bin missing or out of date: no sound"); return false; }
  lock = SDL_CreateMutex();
  SDL_AudioSpec spec = {SDL_AUDIO_F32, 1, RATE};
  stream = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, feed, NULL);
  if (!stream) { SDL_Log("audio device: %s", SDL_GetError()); return false; }
  SDL_ResumeAudioStreamDevice(stream);
  return true;
}

void audio_shutdown(void) {
  if (stream && stream != (SDL_AudioStream *)1) SDL_DestroyAudioStream(stream);
  stream = NULL;
}

static int find(const char *name) {
  const GameAudio *g = &games[game];
  for (int i = 0; i < g->n_names; i++)
    if (!strcmp(g->names[i].name, name)) return g->names[i].id;
  return -1;
}

void audio_set_game(int g) {
  if (g == game || g < 0 || g > 1) return;
  if (lock) SDL_LockMutex(lock);
  game = g;
  memset(chans, 0, sizeof chans);
  current_music = -1;
  if (lock) SDL_UnlockMutex(lock);
}

static void play(int id, bool restart) {
  if (!stream || id < 0) return;
  SDL_LockMutex(lock);
  if (restart || id != current_music) play_id(id);
  SDL_UnlockMutex(lock);
}

void audio_room(int group, int room) {
  if (group < 0 || group > 7 || room < 0 || room > 255) return;
  int id = games[game].rooms[group][room];
  if (id && id != 0xff) play(id, false);
}

void sfx(const char *name) { play(find(name), true); }
void audio_music_named(const char *name) { play(find(name), false); }

void audio_volume(float music, float effects) {
  if (lock) SDL_LockMutex(lock);
  music_gain = music;
  sfx_gain = effects;
  if (lock) SDL_UnlockMutex(lock);
}
