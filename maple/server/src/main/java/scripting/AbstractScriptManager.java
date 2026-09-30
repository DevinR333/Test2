/*
This file is part of the OdinMS Maple Story Server
Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
Matthias Butz <matze@odinms.de>
Jan Christian Meyer <vimes@odinms.de>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation version 3 as published by
the Free Software Foundation. You may not use, modify or distribute
this program under any other version of the GNU Affero General Public
License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package scripting;

import scripting.jsr.RhinoEngine;
import scripting.jsr.ScriptEngine;
import scripting.jsr.ScriptException;
import java.io.Reader;

import client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Matze
 */
public abstract class AbstractScriptManager {
    private static final Logger log = LoggerFactory.getLogger(AbstractScriptManager.class);

    /** Opens a script by path relative to the scripts folder, or returns null if there is none. */
    public interface ScriptLoader {
        Reader open(String path) throws IOException;

        /** File names in a scripts sub-folder, e.g. "event". */
        default String[] list(String dir) throws IOException {
            java.io.File[] files = new java.io.File("scripts", dir).listFiles();
            if (files == null) return new String[0];
            String[] names = new String[files.length];
            for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
            return names;
        }
    }

    /** Desktop default: the "scripts" folder. The Android app replaces this to read from the APK. */
    public static volatile ScriptLoader loader = path -> {
        Path scriptFile = Path.of("scripts", path);
        if (!Files.exists(scriptFile)) {
            return null;
        }
        return Files.newBufferedReader(scriptFile, StandardCharsets.UTF_8);
    };

    protected AbstractScriptManager() {
    }

    protected ScriptEngine getInvocableScriptEngine(String path) {
        Reader reader;
        try {
            reader = loader.open(path);
        } catch (IOException e) {
            log.warn("Could not open script {}", path, e);
            return null;
        }
        if (reader == null) {
            return null;
        }

        RhinoEngine engine = new RhinoEngine();
        try (Reader br = reader) {
            engine.eval(br);
        } catch (final ScriptException | IOException t) {
            log.warn("Exception during script eval for file: {}", path, t);
            return null;
        }

        return engine;
    }

    protected ScriptEngine getInvocableScriptEngine(String path, Client c) {
        ScriptEngine engine = c.getScriptEngine("scripts/" + path);
        if (engine == null) {
            engine = getInvocableScriptEngine(path);
            c.setScriptEngine(path, engine);
        }

        return engine;
    }

    protected void resetContext(String path, Client c) {
        c.removeScriptEngine("scripts/" + path);
    }
}
