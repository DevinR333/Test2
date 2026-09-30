package scripting.jsr;

/** Mirror of javax.script.ScriptException. */
public class ScriptException extends Exception {
    public ScriptException(String message) { super(message); }
    public ScriptException(Exception cause) { super(cause.getMessage(), cause); }
    public ScriptException(String message, Throwable cause) { super(message, cause); }
}
