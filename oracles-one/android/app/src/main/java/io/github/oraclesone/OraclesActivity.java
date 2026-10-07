package io.github.oraclesone;

import org.libsdl.app.SDLActivity;

// The whole game is native code (libmain.so, built from ../../CMakeLists.txt); SDL's activity hosts it.
public class OraclesActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }
}
