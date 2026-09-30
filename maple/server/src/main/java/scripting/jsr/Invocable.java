package scripting.jsr;

/** Mirror of javax.script.Invocable. */
public interface Invocable {
    Object invokeMethod(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException;
    Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException;
    <T> T getInterface(Class<T> clasz);
    <T> T getInterface(Object thiz, Class<T> clasz);
}
