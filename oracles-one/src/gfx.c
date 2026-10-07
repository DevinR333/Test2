#include "gfx.h"
#include <stdio.h>
#include <string.h>

void *asset_load(const char *name, size_t *size) {
#ifdef SDL_PLATFORM_ANDROID
  return SDL_LoadFile(name, size);   // relative paths read the APK's assets
#else
  char path[1024];
  snprintf(path, sizeof path, "%sassets/%s", SDL_GetBasePath(), name);
  void *data = SDL_LoadFile(path, size);
  if (!data) {
    snprintf(path, sizeof path, "assets/%s", name);   // running from the source tree
    data = SDL_LoadFile(path, size);
  }
  return data;
#endif
}

bool image_load(SDL_Renderer *ren, const char *name, Image *out) {
  size_t size;
  Uint8 *data = asset_load(name, &size);
  if (!data) { SDL_Log("missing asset %s: %s", name, SDL_GetError()); return false; }
  Uint32 w, h;
  memcpy(&w, data, 4);
  memcpy(&h, data + 4, 4);
  w = SDL_Swap32LE(w);
  h = SDL_Swap32LE(h);
  if (size < 8 + (size_t)w * h * 4) { SDL_Log("asset %s is truncated", name); SDL_free(data); return false; }
  out->tex = SDL_CreateTexture(ren, SDL_PIXELFORMAT_RGBA32, SDL_TEXTUREACCESS_STATIC, (int)w, (int)h);
  if (!out->tex) { SDL_Log("texture %s: %s", name, SDL_GetError()); SDL_free(data); return false; }
  SDL_UpdateTexture(out->tex, NULL, data + 8, (int)w * 4);
  SDL_SetTextureScaleMode(out->tex, SDL_SCALEMODE_NEAREST);
  SDL_SetTextureBlendMode(out->tex, SDL_BLENDMODE_BLEND);
  out->w = (int)w;
  out->h = (int)h;
  SDL_free(data);
  return true;
}

void image_draw(SDL_Renderer *ren, const Image *img, int sx, int sy, int sw, int sh, float dx, float dy, float dw, float dh, bool flip) {
  SDL_FRect src = {(float)sx, (float)sy, (float)sw, (float)sh};
  SDL_FRect dst = {dx, dy, dw, dh};
  if (flip) SDL_RenderTextureRotated(ren, img->tex, &src, &dst, 0, NULL, SDL_FLIP_HORIZONTAL);
  else SDL_RenderTexture(ren, img->tex, &src, &dst);
}

void fill_rect(SDL_Renderer *ren, float x, float y, float w, float h, Uint8 r, Uint8 g, Uint8 b, Uint8 a) {
  SDL_SetRenderDrawBlendMode(ren, a == 255 ? SDL_BLENDMODE_NONE : SDL_BLENDMODE_BLEND);
  SDL_SetRenderDrawColor(ren, r, g, b, a);
  SDL_FRect rc = {x, y, w, h};
  SDL_RenderFillRect(ren, &rc);
}

// 5x7 glyphs, one byte per row (bit 4 = leftmost column), for ' '..'Z'.
static const Uint8 font[59][7] = {
  {0,0,0,0,0,0,0},                                  // space
  {4,4,4,4,4,0,4}, {10,10,0,0,0,0,0}, {10,31,10,10,31,10,0}, {4,15,20,14,5,30,4},
  {24,25,2,4,8,19,3}, {12,18,20,8,21,18,13}, {4,4,0,0,0,0,0}, {2,4,8,8,8,4,2},
  {8,4,2,2,2,4,8}, {0,10,4,31,4,10,0}, {0,4,4,31,4,4,0}, {0,0,0,0,12,4,8},
  {0,0,0,31,0,0,0}, {0,0,0,0,0,12,12}, {0,1,2,4,8,16,0},
  {14,17,19,21,25,17,14}, {4,12,4,4,4,4,14}, {14,17,1,2,4,8,31}, {31,2,4,2,1,17,14},
  {2,6,10,18,31,2,2}, {31,16,30,1,1,17,14}, {6,8,16,30,17,17,14}, {31,1,2,4,8,8,8},
  {14,17,17,14,17,17,14}, {14,17,17,15,1,2,12},
  {0,12,12,0,12,12,0}, {0,12,12,0,12,4,8}, {2,4,8,16,8,4,2}, {0,0,31,0,31,0,0},
  {8,4,2,1,2,4,8}, {14,17,1,2,4,0,4}, {14,17,1,13,21,21,14},
  {14,17,17,17,31,17,17}, {30,17,17,30,17,17,30}, {14,17,16,16,16,17,14}, {28,18,17,17,17,18,28},
  {31,16,16,30,16,16,31}, {31,16,16,30,16,16,16}, {14,17,16,23,17,17,15}, {17,17,17,31,17,17,17},
  {14,4,4,4,4,4,14}, {7,2,2,2,2,18,12}, {17,18,20,24,20,18,17}, {16,16,16,16,16,16,31},
  {17,27,21,21,17,17,17}, {17,17,25,21,19,17,17}, {14,17,17,17,17,17,14}, {30,17,17,30,16,16,16},
  {14,17,17,17,21,18,13}, {30,17,17,30,20,18,17}, {15,16,16,14,1,1,30}, {31,4,4,4,4,4,4},
  {17,17,17,17,17,17,14}, {17,17,17,17,17,10,4}, {17,17,17,21,21,21,10}, {17,17,10,4,10,17,17},
  {17,17,10,4,4,4,4}, {31,1,2,4,8,16,31},
};

float text_width(const char *s, float px) { return (float)strlen(s) * 6 * px - px; }

void draw_text(SDL_Renderer *ren, const char *s, float x, float y, float px, Uint8 r, Uint8 g, Uint8 b) {
  draw_text_alpha(ren, s, x, y, px, r, g, b, 255);
}

void draw_text_alpha(SDL_Renderer *ren, const char *s, float x, float y, float px, Uint8 r, Uint8 g, Uint8 b, Uint8 a) {
  SDL_SetRenderDrawBlendMode(ren, a == 255 ? SDL_BLENDMODE_NONE : SDL_BLENDMODE_BLEND);
  for (; *s; s++, x += 6 * px) {
    int c = *s;
    if (c >= 'a' && c <= 'z') c -= 32;
    if (c < ' ' || c > 'Z') continue;
    const Uint8 *g7 = font[c - ' '];
    for (int row = 0; row < 7; row++)
      for (int col = 0; col < 5; col++)
        if (g7[row] >> (4 - col) & 1) {
          SDL_FRect shadow = {x + col * px + px * 0.5f, y + row * px + px * 0.5f, px, px};
          SDL_SetRenderDrawColor(ren, 0, 0, 0, a);
          SDL_RenderFillRect(ren, &shadow);
        }
    for (int row = 0; row < 7; row++)
      for (int col = 0; col < 5; col++)
        if (g7[row] >> (4 - col) & 1) {
          SDL_FRect px_rc = {x + col * px, y + row * px, px, px};
          SDL_SetRenderDrawColor(ren, r, g, b, a);
          SDL_RenderFillRect(ren, &px_rc);
        }
  }
}
