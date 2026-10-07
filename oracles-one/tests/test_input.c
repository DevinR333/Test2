// Controllers through SDL's virtual joysticks: face buttons go by their printed label, so the
// button marked A is A on an Xbox pad (bottom) and on a Nintendo-style one (right).
#include "../src/input.h"
#include <stdio.h>

static int failures;
#define CHECK(cond) do { if (!(cond)) { printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static Input in;

static void pump(void) {
  SDL_UpdateJoysticks();
  SDL_Event ev;
  while (SDL_PollEvent(&ev)) input_event(&in, &ev, 1280, 720);
  input_frame(&in, 1280, 720);
}

// Presses one button of a virtual pad that reports itself as vendor:product, returns what the game saw.
static Uint32 press(Uint16 vendor, Uint16 product, SDL_GamepadButton button) {
  SDL_VirtualJoystickDesc desc;
  SDL_INIT_INTERFACE(&desc);
  desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
  desc.vendor_id = vendor;
  desc.product_id = product;
  desc.nbuttons = SDL_GAMEPAD_BUTTON_COUNT;
  desc.naxes = SDL_GAMEPAD_AXIS_COUNT;
  desc.name = "virtual pad";
  SDL_JoystickID id = SDL_AttachVirtualJoystick(&desc);
  SDL_Joystick *joy = SDL_OpenJoystick(id);
  pump();
  SDL_SetJoystickVirtualButton(joy, button, true);
  pump();
  Uint32 held = in.held;
  SDL_SetJoystickVirtualButton(joy, button, false);
  pump();
  SDL_CloseJoystick(joy);
  SDL_DetachVirtualJoystick(id);
  pump();
  return held;
}

int main(void) {
  SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
  if (!SDL_Init(SDL_INIT_GAMEPAD)) { printf("SDL_Init: %s\n", SDL_GetError()); return 1; }
  input_init();
  in.zoom_factor = 1;
  in.touch_ui = true;

  // Xbox One controller: A is the bottom button
  CHECK(press(0x045e, 0x02ea, SDL_GAMEPAD_BUTTON_SOUTH) == BTN_A);
  CHECK(press(0x045e, 0x02ea, SDL_GAMEPAD_BUTTON_EAST) == BTN_B);
  CHECK(!in.touch_ui);                       // a controller hides the touch buttons
  // Switch Pro controller (Nintendo layout, like the Retroid's labels): A is the right button
  CHECK(press(0x057e, 0x2009, SDL_GAMEPAD_BUTTON_EAST) == BTN_A);
  CHECK(press(0x057e, 0x2009, SDL_GAMEPAD_BUTTON_SOUTH) == BTN_B);
  // DualShock 4: cross is A, circle B
  CHECK(press(0x054c, 0x09cc, SDL_GAMEPAD_BUTTON_SOUTH) == BTN_A);
  CHECK(press(0x054c, 0x09cc, SDL_GAMEPAD_BUTTON_EAST) == BTN_B);
  // an unknown pad: the usual positions
  CHECK(press(0x1234, 0x5678, SDL_GAMEPAD_BUTTON_SOUTH) == BTN_A);
  CHECK(press(0x1234, 0x5678, SDL_GAMEPAD_BUTTON_START) == BTN_START);
  CHECK(press(0x1234, 0x5678, SDL_GAMEPAD_BUTTON_BACK) == BTN_SELECT);
  CHECK(press(0x1234, 0x5678, SDL_GAMEPAD_BUTTON_DPAD_LEFT) == BTN_LEFT);

  printf(failures ? "%d failures\n" : "all passed\n", failures);
  SDL_Quit();
  return failures != 0;
}
