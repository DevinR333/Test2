#include "minimap.h"

#define MAP_MAX 512                   // the map texture's longer side, in pixels

static const World *cached;
static SDL_Texture *tex;
static int tex_w, tex_h;

static void build(SDL_Renderer *ren, const World *w, const Image *atlas) {
  if (tex) SDL_DestroyTexture(tex);
  int ww = world_px_w(w), wh = world_px_h(w);
  float s = (float)MAP_MAX / (float)(ww > wh ? ww : wh);
  if (s > 0.5f) s = 0.5f;
  tex_w = (int)((float)ww * s) + 1;
  tex_h = (int)((float)wh * s) + 1;
  tex = SDL_CreateTexture(ren, SDL_PIXELFORMAT_RGBA8888, SDL_TEXTUREACCESS_TARGET, tex_w, tex_h);
  if (!tex) return;
  SDL_SetTextureScaleMode(tex, SDL_SCALEMODE_NEAREST);
  SDL_Texture *old = SDL_GetRenderTarget(ren);
  SDL_Rect old_vp, old_clip;
  SDL_GetRenderViewport(ren, &old_vp);
  bool clipped = SDL_RenderClipEnabled(ren);
  SDL_GetRenderClipRect(ren, &old_clip);
  SDL_SetRenderTarget(ren, tex);
  SDL_SetRenderClipRect(ren, NULL);
  SDL_SetRenderDrawColor(ren, 0, 0, 0, 0);
  SDL_RenderClear(ren);
  // the area as world_draw would show it, from a view that fits it all in the texture
  View v = {0, 0, s, {0, 0, tex_w, tex_h}};
  world_draw(ren, w, atlas, &v);
  SDL_SetRenderTarget(ren, old);
  SDL_SetRenderViewport(ren, &old_vp);
  if (clipped) SDL_SetRenderClipRect(ren, &old_clip);
  cached = w;
}

void minimap_draw(SDL_Renderer *ren, const World *w, const Image *atlas, float lx, float ly, SDL_FRect box, int blink) {
  if (w != cached || !tex) build(ren, w, atlas);
  if (!tex) return;
  float s = SDL_min(box.w / (float)tex_w, box.h / (float)tex_h);
  float dw = (float)tex_w * s, dh = (float)tex_h * s;
  SDL_FRect dst = {box.x + (box.w - dw) / 2, box.y + (box.h - dh) / 2, dw, dh};
  SDL_RenderTexture(ren, tex, NULL, &dst);
  // Link: a blinking marker
  float px = dst.x + lx / (float)world_px_w(w) * dw, py = dst.y + ly / (float)world_px_h(w) * dh;
  float r = SDL_max(3.0f, dw / 70.0f);
  if ((blink / 15) & 1) fill_rect(ren, px - r, py - r, 2 * r, 2 * r, 255, 255, 255, 255);
  else fill_rect(ren, px - r, py - r, 2 * r, 2 * r, 230, 40, 40, 255);
}
