// fable_2 (Android) - JNI entry points called by the Java side.
//
// TouchControlsView publishes the on-screen pad through nativeSetState();
// see input/touch_gamepad.h for how the guest reads it.

#include <jni.h>

#include "touch_gamepad.h"

extern "C" {

JNIEXPORT void JNICALL
Java_com_fable2_recomp_TouchControlsView_nativeSetState(
    JNIEnv*, jclass, jint buttons, jint lt, jint rt, jint lx, jint ly, jint rx,
    jint ry) {
  fable2::touch::Publish(uint16_t(buttons), uint8_t(lt), uint8_t(rt),
                         int16_t(lx), int16_t(ly), int16_t(rx), int16_t(ry));
}

}  // extern "C"
