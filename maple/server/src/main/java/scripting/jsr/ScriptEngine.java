package scripting.jsr;

import java.io.Reader;

/** The small part of javax.script the server needs (javax.script does not exist on Android). */
public interface ScriptEngine {
    void put(String name, Object value);
    Object get(String name);
    Object eval(Reader reader) throws ScriptException;
    Object eval(String source) throws ScriptException;
}
