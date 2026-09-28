package com.fable2.recomp;

import android.hardware.input.InputManager;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * Runs the recompiled game. SDLActivity creates the surface and calls
 * SDL_main in libmain.so; this class adds the content folder hand-off and the
 * touch-control overlay on top.
 */
public class Fable2Activity extends SDLActivity implements InputManager.InputDeviceListener {
    private static final String TAG = "Fable2";

    private TouchControlsView touchControls;
    private InputManager inputManager;

    @Override
    protected String[] getLibraries() {
        // libmain.so pulls in librexruntime.so and libc++_shared.so itself.
        return new String[] {"SDL3", "main"};
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Everything the desktop build keeps "next to the exe" (game content,
        // saves, cache, config, logs) lives in the app's external files dir.
        File dir = LauncherActivity.contentDir(this);
        if (dir != null) {
            try {
                Os.setenv("REX_ANDROID_APP_DIR", dir.getAbsolutePath(), true);
                Os.setenv("HOME", dir.getAbsolutePath(), true);
            } catch (ErrnoException e) {
                Log.e(TAG, "setenv failed", e);
            }
        }

        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        touchControls = new TouchControlsView(this);
        addContentView(touchControls, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        inputManager.registerInputDeviceListener(this, null);
        updateControllerState();
        hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // A physical controller hides the overlay (the toggle button stays, so it
    // can be brought back); unplugging it shows the overlay again.
    private void updateControllerState() {
        boolean hasPad = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                hasPad = true;
                break;
            }
        }
        if (touchControls != null) {
            touchControls.setControlsVisible(!hasPad);
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateControllerState();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updateControllerState();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updateControllerState();
    }
}
