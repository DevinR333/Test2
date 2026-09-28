// fable_2 (Android) - on-screen touch controls -> guest gamepad
//
// The Java overlay (TouchControlsView) draws every Xbox 360 control: A/B/X/Y,
// LB/RB, LT/RT, the d-pad, Start/Back, both analog sticks and L3/R3 (tap a
// stick). On every touch change it publishes the full pad state through
// nativeSetState(); the guest reads it through this synthetic input driver,
// which the SDK OR-merges with a real controller (SDL driver), so a Bluetooth
// pad and the touch overlay work at the same time.

#pragma once

#include <atomic>
#include <cstdint>
#include <vector>

#include <rex/input/input.h>
#include <rex/input/input_driver.h>
#include <rex/input/input_system.h>

namespace fable2::touch {

using rex::X_RESULT;
using rex::X_STATUS;

// Packed pad state, written by the UI thread (JNI), read by guest threads.
// Two 64-bit words keep every read tear-free without a lock:
//   word0 = buttons(16) | lt(8) | rt(8) | seq(32)
//   word1 = lx(16) | ly(16) | rx(16) | ry(16)
inline std::atomic<uint64_t> g_word0{0};
inline std::atomic<uint64_t> g_word1{0};

inline void Publish(uint16_t buttons, uint8_t lt, uint8_t rt, int16_t lx,
                    int16_t ly, int16_t rx, int16_t ry) {
  static uint32_t seq = 0;
  ++seq;
  g_word1.store(uint64_t(uint16_t(lx)) | (uint64_t(uint16_t(ly)) << 16) |
                    (uint64_t(uint16_t(rx)) << 32) |
                    (uint64_t(uint16_t(ry)) << 48),
                std::memory_order_relaxed);
  g_word0.store(uint64_t(buttons) | (uint64_t(lt) << 16) |
                    (uint64_t(rt) << 24) | (uint64_t(seq) << 32),
                std::memory_order_release);
}

class TouchGamepadDriver final : public rex::input::InputDriver {
 public:
  TouchGamepadDriver(rex::ui::Window* window, size_t window_z_order)
      : InputDriver(window, window_z_order) {}

  X_STATUS Setup() override { return X_STATUS_SUCCESS; }

  void EnumerateDevices(std::vector<rex::input::DeviceInfo>& out) override {
    rex::input::DeviceInfo info;
    info.id = kDeviceId;
    info.name = "Touch controls";
    info.guid = "fable2-touch-gamepad";
    info.synthetic = true;  // routed to guest user 0, merged with real pads
    out.push_back(info);
  }

  rex::X_RESULT GetDeviceState(rex::input::DeviceId id,
                               rex::input::X_INPUT_STATE* out_state) override {
    if (id != kDeviceId) return X_ERROR_DEVICE_NOT_CONNECTED;
    const uint64_t w0 = g_word0.load(std::memory_order_acquire);
    const uint64_t w1 = g_word1.load(std::memory_order_relaxed);
    out_state->packet_number.set(uint32_t(w0 >> 32));
    out_state->gamepad.buttons.set(uint16_t(w0));
    out_state->gamepad.left_trigger = uint8_t(w0 >> 16);
    out_state->gamepad.right_trigger = uint8_t(w0 >> 24);
    out_state->gamepad.thumb_lx.set(int16_t(uint16_t(w1)));
    out_state->gamepad.thumb_ly.set(int16_t(uint16_t(w1 >> 16)));
    out_state->gamepad.thumb_rx.set(int16_t(uint16_t(w1 >> 32)));
    out_state->gamepad.thumb_ry.set(int16_t(uint16_t(w1 >> 48)));
    return X_ERROR_SUCCESS;
  }

  rex::X_RESULT GetDeviceCapabilities(
      rex::input::DeviceId id, uint32_t flags,
      rex::input::X_INPUT_CAPABILITIES* out_caps) override {
    (void)flags;
    if (id != kDeviceId) return X_ERROR_DEVICE_NOT_CONNECTED;
    *out_caps = rex::input::X_INPUT_CAPABILITIES{};
    out_caps->type = rex::input::XINPUT_DEVTYPE_GAMEPAD;
    out_caps->sub_type = 0x01;  // XINPUT_DEVSUBTYPE_GAMEPAD
    return X_ERROR_SUCCESS;
  }

  rex::X_RESULT SetDeviceVibration(
      rex::input::DeviceId id, rex::input::X_INPUT_VIBRATION* vibration) override {
    (void)vibration;
    return id == kDeviceId ? X_ERROR_SUCCESS : X_ERROR_DEVICE_NOT_CONNECTED;
  }

  rex::X_RESULT GetDeviceKeystroke(
      rex::input::DeviceId id, uint32_t flags,
      rex::input::X_INPUT_KEYSTROKE* out_keystroke) override {
    (void)id;
    (void)flags;
    (void)out_keystroke;
    return X_ERROR_EMPTY;
  }

 private:
  // Distinct from the SDL driver's sequential ids and the keyboard pad's id.
  static constexpr rex::input::DeviceId kDeviceId{(1ull << 60) + 2};
};

}  // namespace fable2::touch
