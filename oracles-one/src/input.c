#include "input.h"
#include "gfx.h"

// Keyboard: arrows/WASD move, X = A, Z = B, Enter = Start, Right Shift/Backspace = Select,
// +/- or the mouse wheel zoom, F2 cycles the aspect ratio. Gamepads (Xbox, PlayStation, Switch,
// and handhelds' built-in controls such as the Retroid Pocket's, via SDL's controller database):
// the face buttons go by their printed label, so the button marked A is A whatever the layout
// (on Nintendo-style handhelds that is exactly the Game Boy's); PlayStation's cross is A and
// circle B. D-pad or left stick move, Start/Menu is Start, Back/View/Select is Select, the
// shoulders zoom. Touch: d-pad and buttons on screen, pinch to zoom; they hide while a controller
// is in use. Android's back button opens the menu.

#define MAX_FINGERS 10
typedef enum { ROLE_NONE, ROLE_DPAD, ROLE_BUTTON, ROLE_FREE } Role;
typedef struct { SDL_FingerID id; bool down; float x, y; Role role; Uint32 button; } Finger;

static Finger fingers[MAX_FINGERS];
static Uint32 key_held, pad_held, touch_held, prev_held;
static float pinch_dist;
#define MAX_PADS 8
static SDL_Gamepad *pads[MAX_PADS];

void input_init(void) { SDL_memset(fingers, 0, sizeof fingers); }

static Uint32 key_button(SDL_Scancode sc) {
  switch (sc) {
  case SDL_SCANCODE_UP: case SDL_SCANCODE_W: return BTN_UP;
  case SDL_SCANCODE_DOWN: case SDL_SCANCODE_S: return BTN_DOWN;
  case SDL_SCANCODE_LEFT: case SDL_SCANCODE_A: return BTN_LEFT;
  case SDL_SCANCODE_RIGHT: case SDL_SCANCODE_D: return BTN_RIGHT;
  case SDL_SCANCODE_X: case SDL_SCANCODE_K: return BTN_A;
  case SDL_SCANCODE_Z: case SDL_SCANCODE_J: return BTN_B;
  case SDL_SCANCODE_RETURN: case SDL_SCANCODE_ESCAPE: case SDL_SCANCODE_AC_BACK: return BTN_START;
  case SDL_SCANCODE_RSHIFT: case SDL_SCANCODE_BACKSPACE: case SDL_SCANCODE_TAB: return BTN_SELECT;
  case SDL_SCANCODE_EQUALS: case SDL_SCANCODE_KP_PLUS: return BTN_ZOOM_IN;
  case SDL_SCANCODE_MINUS: case SDL_SCANCODE_KP_MINUS: return BTN_ZOOM_OUT;
  case SDL_SCANCODE_F2: return BTN_ASPECT;
  case SDL_SCANCODE_T: return BTN_TOUCH_TOGGLE;
  default: return 0;
  }
}

static Uint32 pad_button(SDL_JoystickID which, int b) {
  if (b <= SDL_GAMEPAD_BUTTON_NORTH) {
    switch (SDL_GetGamepadButtonLabel(SDL_GetGamepadFromID(which), (SDL_GamepadButton)b)) {
    case SDL_GAMEPAD_BUTTON_LABEL_A: case SDL_GAMEPAD_BUTTON_LABEL_CROSS: return BTN_A;
    case SDL_GAMEPAD_BUTTON_LABEL_B: case SDL_GAMEPAD_BUTTON_LABEL_CIRCLE: return BTN_B;
    case SDL_GAMEPAD_BUTTON_LABEL_UNKNOWN: return b == SDL_GAMEPAD_BUTTON_SOUTH ? BTN_A : b == SDL_GAMEPAD_BUTTON_EAST ? BTN_B : 0;
    default: return BTN_TOUCH_TOGGLE;   // X/Y, square/triangle (only the menu uses them for now)
    }
  }
  switch (b) {
  case SDL_GAMEPAD_BUTTON_DPAD_UP: return BTN_UP;
  case SDL_GAMEPAD_BUTTON_DPAD_DOWN: return BTN_DOWN;
  case SDL_GAMEPAD_BUTTON_DPAD_LEFT: return BTN_LEFT;
  case SDL_GAMEPAD_BUTTON_DPAD_RIGHT: return BTN_RIGHT;
  case SDL_GAMEPAD_BUTTON_START: return BTN_START;
  case SDL_GAMEPAD_BUTTON_BACK: return BTN_SELECT;
  case SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER: return BTN_ZOOM_IN;
  case SDL_GAMEPAD_BUTTON_LEFT_SHOULDER: return BTN_ZOOM_OUT;
  default: return 0;
  }
}

// On-screen control layout, in window pixels.
typedef struct { float x, y, r; } Circle;
static float unit(int w, int h) { return (float)(w < h ? w : h) / 10.0f; }
static Circle dpad_circle(int w, int h) { float u = unit(w, h); return (Circle){1.9f * u, (float)h - 1.9f * u, 1.5f * u}; }
static Circle button_circle(Uint32 b, int w, int h) {
  float u = unit(w, h);
  switch (b) {
  case BTN_A: return (Circle){(float)w - 1.2f * u, (float)h - 2.3f * u, 0.8f * u};
  case BTN_B: return (Circle){(float)w - 2.9f * u, (float)h - 1.4f * u, 0.8f * u};
  case BTN_START: return (Circle){(float)w * 0.5f + 1.1f * u, (float)h - 0.8f * u, 0.6f * u};
  default: return (Circle){(float)w * 0.5f - 1.1f * u, (float)h - 0.8f * u, 0.6f * u};   // SELECT
  }
}
static const Uint32 touch_buttons[] = {BTN_A, BTN_B, BTN_START, BTN_SELECT};

static bool inside(Circle c, float x, float y, float slack) {
  float dx = x - c.x, dy = y - c.y;
  return dx * dx + dy * dy <= (c.r * slack) * (c.r * slack);
}

static Uint32 dpad_dirs(Circle c, float x, float y) {
  float dx = x - c.x, dy = y - c.y;
  if (dx * dx + dy * dy < (c.r * 0.2f) * (c.r * 0.2f)) return 0;
  float a = SDL_atan2f(dy, dx);             // 8 sectors of 45 degrees
  int sector = (int)SDL_floorf((a + SDL_PI_F / 8) / (SDL_PI_F / 4)) & 7;
  static const Uint32 dirs[8] = {BTN_RIGHT, BTN_RIGHT | BTN_DOWN, BTN_DOWN, BTN_DOWN | BTN_LEFT, BTN_LEFT, BTN_LEFT | BTN_UP, BTN_UP, BTN_UP | BTN_RIGHT};
  return dirs[sector];
}

static Finger *finger(SDL_FingerID id, bool create) {
  for (int i = 0; i < MAX_FINGERS; i++) if (fingers[i].down && fingers[i].id == id) return &fingers[i];
  if (!create) return NULL;
  for (int i = 0; i < MAX_FINGERS; i++) if (!fingers[i].down) { fingers[i] = (Finger){id, true, 0, 0, ROLE_NONE, 0}; return &fingers[i]; }
  return NULL;
}

static int free_fingers(Finger *out[2]) {
  int n = 0;
  for (int i = 0; i < MAX_FINGERS && n < 2; i++) if (fingers[i].down && fingers[i].role == ROLE_FREE) out[n++] = &fingers[i];
  return n;
}

static float free_distance(void) {
  Finger *f[2];
  if (free_fingers(f) < 2) return 0;
  float dx = f[0]->x - f[1]->x, dy = f[0]->y - f[1]->y;
  return SDL_sqrtf(dx * dx + dy * dy);
}

void input_event(Input *in, const SDL_Event *ev, int win_w, int win_h) {
  switch (ev->type) {
  case SDL_EVENT_QUIT: in->quit = true; break;
  case SDL_EVENT_KEY_DOWN: if (!ev->key.repeat) key_held |= key_button(ev->key.scancode); in->touch_ui = false; break;
  case SDL_EVENT_KEY_UP: key_held &= ~key_button(ev->key.scancode); break;
  case SDL_EVENT_GAMEPAD_ADDED:
    // also sent at start-up for controllers already there, like a handheld's own buttons
    for (int i = 0; i < MAX_PADS; i++) if (!pads[i]) { pads[i] = SDL_OpenGamepad(ev->gdevice.which); break; }
    in->touch_ui = false;
    break;
  case SDL_EVENT_GAMEPAD_REMOVED:
    for (int i = 0; i < MAX_PADS; i++)
      if (pads[i] && SDL_GetGamepadID(pads[i]) == ev->gdevice.which) { SDL_CloseGamepad(pads[i]); pads[i] = NULL; }
    pad_held = 0;
    break;
  case SDL_EVENT_GAMEPAD_BUTTON_DOWN: pad_held |= pad_button(ev->gbutton.which, ev->gbutton.button); in->touch_ui = false; break;
  case SDL_EVENT_GAMEPAD_BUTTON_UP: pad_held &= ~pad_button(ev->gbutton.which, ev->gbutton.button); break;
  case SDL_EVENT_MOUSE_WHEEL: in->zoom_factor *= ev->wheel.y > 0 ? 1.1f : ev->wheel.y < 0 ? 1.0f / 1.1f : 1.0f; break;
  case SDL_EVENT_FINGER_DOWN: {
    in->touch_ui = true;
    Finger *f = finger(ev->tfinger.fingerID, true);
    if (!f) break;
    f->x = ev->tfinger.x * (float)win_w;
    f->y = ev->tfinger.y * (float)win_h;
    if (in->touch_hidden) f->role = ROLE_FREE;   // hidden controls don't take touches
    else if (inside(dpad_circle(win_w, win_h), f->x, f->y, 1.3f)) f->role = ROLE_DPAD;
    else {
      f->role = ROLE_FREE;
      for (size_t i = 0; i < SDL_arraysize(touch_buttons); i++)
        if (inside(button_circle(touch_buttons[i], win_w, win_h), f->x, f->y, 1.25f)) { f->role = ROLE_BUTTON; f->button = touch_buttons[i]; }
    }
    pinch_dist = free_distance();
    break;
  }
  case SDL_EVENT_FINGER_MOTION: {
    Finger *f = finger(ev->tfinger.fingerID, false);
    if (!f) break;
    f->x = ev->tfinger.x * (float)win_w;
    f->y = ev->tfinger.y * (float)win_h;
    if (f->role == ROLE_FREE) {
      float d = free_distance();
      if (d > 0 && pinch_dist > 0) in->zoom_factor *= d / pinch_dist;
      pinch_dist = d;
    }
    break;
  }
  case SDL_EVENT_FINGER_UP: case SDL_EVENT_FINGER_CANCELED: {
    Finger *f = finger(ev->tfinger.fingerID, false);
    if (f) f->down = false;
    pinch_dist = free_distance();
    break;
  }
  default: break;
  }
}

void input_frame(Input *in, int win_w, int win_h) {
  touch_held = 0;
  for (int i = 0; i < MAX_FINGERS; i++) {
    Finger *f = &fingers[i];
    if (!f->down) continue;
    if (f->role == ROLE_DPAD) touch_held |= dpad_dirs(dpad_circle(win_w, win_h), f->x, f->y);
    else if (f->role == ROLE_BUTTON) touch_held |= f->button;
  }
  Uint32 axes = 0;
  for (int i = 0; i < MAX_PADS; i++) {
    if (!pads[i]) continue;
    int ax = SDL_GetGamepadAxis(pads[i], SDL_GAMEPAD_AXIS_LEFTX), ay = SDL_GetGamepadAxis(pads[i], SDL_GAMEPAD_AXIS_LEFTY);
    if (ax < -12000) axes |= BTN_LEFT;
    if (ax > 12000) axes |= BTN_RIGHT;
    if (ay < -12000) axes |= BTN_UP;
    if (ay > 12000) axes |= BTN_DOWN;
  }
  in->held = key_held | pad_held | touch_held | axes;
  in->pressed = in->held & ~prev_held;
  prev_held = in->held;
}

static void draw_circle(SDL_Renderer *ren, Circle c, Uint8 a) {
  SDL_SetRenderDrawBlendMode(ren, SDL_BLENDMODE_BLEND);
  SDL_SetRenderDrawColor(ren, 255, 255, 255, a);
  for (float dy = -c.r; dy <= c.r; dy += 1.0f) {
    float half = SDL_sqrtf(c.r * c.r - dy * dy);
    SDL_RenderLine(ren, c.x - half, c.y + dy, c.x + half, c.y + dy);
  }
}

void input_draw_touch(SDL_Renderer *ren, const Input *in, int win_w, int win_h) {
  if (!in->touch_ui || in->touch_hidden) return;
  Circle d = dpad_circle(win_w, win_h);
  draw_circle(ren, d, 50);
  float arm = d.r * 0.3f;
  fill_rect(ren, d.x - arm, d.y - d.r * 0.85f, arm * 2, d.r * 1.7f, 20, 20, 30, 120);
  fill_rect(ren, d.x - d.r * 0.85f, d.y - arm, d.r * 1.7f, arm * 2, 20, 20, 30, 120);
  static const char *labels[] = {"A", "B", "START", "SELECT"};
  for (size_t i = 0; i < SDL_arraysize(touch_buttons); i++) {
    Circle c = button_circle(touch_buttons[i], win_w, win_h);
    draw_circle(ren, c, (in->held & touch_buttons[i]) ? 110 : 50);
    // A and B in big letters; START and SELECT small enough to fit inside their circles
    float px = i < 2 ? c.r / 6 : c.r * 1.7f / (float)(6 * SDL_strlen(labels[i]));
    draw_text(ren, labels[i], c.x - text_width(labels[i], px) / 2, c.y - 3.5f * px, px, 255, 255, 255);
  }
}
