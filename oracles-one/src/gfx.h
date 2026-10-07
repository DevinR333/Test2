#pragma once
#include <SDL3/SDL.h>
#include <stdbool.h>
#include <stddef.h>

// A texture made from one of the .rgba files tools/extract_*.py write.
typedef struct {
  SDL_Texture *tex;
  int w, h;
} Image;

// Reads an asset (from the APK on Android, from assets/ next to the program elsewhere).
void *asset_load(const char *name, size_t *size);
bool image_load(SDL_Renderer *ren, const char *name, Image *out);

// Draws src (in image pixels) to the screen rectangle; flip mirrors horizontally.
void image_draw(SDL_Renderer *ren, const Image *img, int sx, int sy, int sw, int sh, float dx, float dy, float dw, float dh, bool flip);

// The view: what part of the world is on screen and how big a world pixel is.
typedef struct {
  float x, y;          // world position of the screen's top-left corner
  float scale;         // screen pixels per world pixel
  SDL_Rect viewport;   // where the game is drawn (smaller than the window when letterboxed)
} View;

// Snaps a world coordinate to a whole screen pixel so neighbouring tiles never leave a seam.
static inline float view_sx(const View *v, float wx) { return (float)v->viewport.x + SDL_floorf((wx - v->x) * v->scale); }
static inline float view_sy(const View *v, float wy) { return (float)v->viewport.y + SDL_floorf((wy - v->y) * v->scale); }

void fill_rect(SDL_Renderer *ren, float x, float y, float w, float h, Uint8 r, Uint8 g, Uint8 b, Uint8 a);
// Simple 5x7 text for menus, scaled by `px` screen pixels per font pixel.
void draw_text(SDL_Renderer *ren, const char *s, float x, float y, float px, Uint8 r, Uint8 g, Uint8 b);
void draw_text_alpha(SDL_Renderer *ren, const char *s, float x, float y, float px, Uint8 r, Uint8 g, Uint8 b, Uint8 a);
float text_width(const char *s, float px);
