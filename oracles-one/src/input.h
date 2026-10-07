#pragma once
#include <SDL3/SDL.h>
#include <stdbool.h>

enum {
  BTN_UP = 1 << 0, BTN_DOWN = 1 << 1, BTN_LEFT = 1 << 2, BTN_RIGHT = 1 << 3,
  BTN_A = 1 << 4, BTN_B = 1 << 5, BTN_START = 1 << 6, BTN_SELECT = 1 << 7,
  BTN_ZOOM_IN = 1 << 8, BTN_ZOOM_OUT = 1 << 9, BTN_ASPECT = 1 << 10,
};

typedef struct {
  Uint32 held, pressed;          // pressed: went down this frame
  float zoom_factor;             // multiply the zoom by this (pinch, mouse wheel); 1 when idle
  bool touch_ui;                 // show the on-screen controls (last input was a touch)
  bool quit;
} Input;

void input_init(void);
void input_event(Input *in, const SDL_Event *ev, int win_w, int win_h);
// Call once per frame after the events: works out `pressed` and folds in touch buttons.
void input_frame(Input *in, int win_w, int win_h);
void input_draw_touch(SDL_Renderer *ren, const Input *in, int win_w, int win_h);
