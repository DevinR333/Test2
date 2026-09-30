package scripting.jsr;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaClass;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the server's JavaScript (NPC, quest, portal, reactor, event scripts) with Mozilla Rhino in
 * interpreted mode, which works on Android. Provides GraalJS's {@code Java.type} / {@code Java.to}.
 */
public final class RhinoEngine implements ScriptEngine, Invocable {
    private static final Map<String, String> RENAMED = new HashMap<>();
    static {
        RENAMED.put("java.awt.Point", "compat.awt.Point");
        RENAMED.put("java.awt.Rectangle", "compat.awt.Rectangle");
        RENAMED.put("java.awt.Dimension", "compat.awt.Dimension");
    }

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected void onContextCreated(Context cx) {
            super.onContextCreated(cx);
            cx.setOptimizationLevel(-1); // interpreter: no bytecode generation (required on Android)
            cx.setLanguageVersion(Context.VERSION_ES6);
        }
    };

    private final ScriptableObject scope;

    public RhinoEngine() {
        Context cx = FACTORY.enterContext();
        try {
            scope = cx.initStandardObjects();
            NativeObject java = new NativeObject();
            ScriptableObject.putProperty(java, "type", new TypeFunction());
            ScriptableObject.putProperty(java, "to", new ToFunction());
            ScriptableObject.putProperty(java, "from", new FromFunction());
            ScriptableObject.putProperty(scope, "Java", java);
        } finally {
            Context.exit();
        }
    }

    @Override
    public synchronized void put(String name, Object value) {
        Context.enter();
        try {
            ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope));
        } finally {
            Context.exit();
        }
    }

    @Override
    public synchronized Object get(String name) {
        Context.enter();
        try {
            return unwrap(ScriptableObject.getProperty(scope, name));
        } finally {
            Context.exit();
        }
    }

    @Override
    public synchronized Object eval(Reader reader) throws ScriptException {
        Context cx = FACTORY.enterContext();
        try {
            return unwrap(cx.evaluateReader(scope, reader, "script", 1, null));
        } catch (RhinoException | IOException e) {
            throw new ScriptException(e);
        } finally {
            Context.exit();
        }
    }

    @Override
    public synchronized Object eval(String source) throws ScriptException {
        Context cx = FACTORY.enterContext();
        try {
            return unwrap(cx.evaluateString(scope, source, "script", 1, null));
        } catch (RhinoException e) {
            throw new ScriptException(e);
        } finally {
            Context.exit();
        }
    }

    @Override
    public synchronized Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException {
        return call(scope, name, args);
    }

    @Override
    public synchronized Object invokeMethod(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException {
        Scriptable target = thiz instanceof Scriptable ? (Scriptable) thiz : scope;
        return call(target, name, args);
    }

    private Object call(Scriptable target, String name, Object[] args) throws ScriptException, NoSuchMethodException {
        Context cx = FACTORY.enterContext();
        try {
            Object f = ScriptableObject.getProperty(target, name);
            if (!(f instanceof Function)) throw new NoSuchMethodException(name);
            Object[] jsArgs = new Object[args == null ? 0 : args.length];
            for (int i = 0; i < jsArgs.length; i++) jsArgs[i] = Context.javaToJS(args[i], scope);
            return unwrap(((Function) f).call(cx, scope, target, jsArgs));
        } catch (RhinoException e) {
            throw new ScriptException(e);
        } finally {
            Context.exit();
        }
    }

    @Override
    public <T> T getInterface(Class<T> clasz) {
        return getInterface(null, clasz);
    }

    @Override
    public <T> T getInterface(Object thiz, Class<T> clasz) {
        Object proxy = Proxy.newProxyInstance(clasz.getClassLoader(), new Class<?>[]{clasz}, (p, method, a) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == a[0];
                    default: return "RhinoInterface[" + clasz.getSimpleName() + "]";
                }
            }
            Object r = thiz == null ? invokeFunction(method.getName(), a) : invokeMethod(thiz, method.getName(), a);
            return convertReturn(r, method.getReturnType());
        });
        return clasz.cast(proxy);
    }

    private static Object convertReturn(Object r, Class<?> type) {
        if (type == void.class) return null;
        if (type == boolean.class || type == Boolean.class) return r instanceof Boolean ? r : (r != null && Context.toBoolean(r));
        if (r instanceof Number) {
            Number n = (Number) r;
            if (type == int.class || type == Integer.class) return n.intValue();
            if (type == long.class || type == Long.class) return n.longValue();
            if (type == short.class || type == Short.class) return n.shortValue();
            if (type == byte.class || type == Byte.class) return n.byteValue();
            if (type == double.class || type == Double.class) return n.doubleValue();
            if (type == float.class || type == Float.class) return n.floatValue();
        }
        return r;
    }

    static Object unwrap(Object o) {
        if (o instanceof Wrapper) return ((Wrapper) o).unwrap();
        if (o == Undefined.instance) return null;
        return o;
    }

    // ---- Java.type / Java.to / Java.from ----

    private final class TypeFunction extends BaseFunction {
        @Override
        public Object call(Context cx, Scriptable s, Scriptable thisObj, Object[] args) {
            String name = Context.toString(args[0]);
            String mapped = RENAMED.getOrDefault(name, name);
            try {
                Class<?> c;
                if (mapped.endsWith("[]")) {
                    String comp = mapped.substring(0, mapped.length() - 2);
                    comp = RENAMED.getOrDefault(comp, comp);
                    c = Array.newInstance(Class.forName(comp), 0).getClass();
                } else {
                    c = Class.forName(mapped);
                }
                return new NativeJavaClass(scope, c);
            } catch (ClassNotFoundException e) {
                throw Context.reportRuntimeError("Java.type: class not found: " + name);
            }
        }
    }

    private final class ToFunction extends BaseFunction {
        @Override
        public Object call(Context cx, Scriptable s, Scriptable thisObj, Object[] args) {
            Object src = args[0];
            Class<?> component = Object.class;
            if (args.length > 1 && args[1] instanceof NativeJavaClass) {
                Class<?> t = ((NativeJavaClass) args[1]).getClassObject();
                if (t.isArray()) component = t.getComponentType();
                else if (List.class.isAssignableFrom(t)) component = null;
            }
            Object[] items;
            if (src instanceof NativeArray) {
                NativeArray na = (NativeArray) src;
                items = new Object[(int) na.getLength()];
                for (int i = 0; i < items.length; i++) items[i] = na.get(i, na);
            } else {
                Object u = unwrap(src);
                if (u instanceof List) items = ((List<?>) u).toArray();
                else if (u != null && u.getClass().isArray()) {
                    items = new Object[Array.getLength(u)];
                    for (int i = 0; i < items.length; i++) items[i] = Array.get(u, i);
                } else items = new Object[0];
            }
            if (component == null) {
                java.util.ArrayList<Object> list = new java.util.ArrayList<>();
                for (Object o : items) list.add(unwrap(o));
                return Context.javaToJS(list, scope);
            }
            Object arr = Array.newInstance(component, items.length);
            for (int i = 0; i < items.length; i++) Array.set(arr, i, Context.jsToJava(items[i], component));
            return Context.javaToJS(arr, scope);
        }
    }

    private final class FromFunction extends BaseFunction {
        @Override
        public Object call(Context cx, Scriptable s, Scriptable thisObj, Object[] args) {
            Object u = unwrap(args[0]);
            Object[] items;
            if (u instanceof java.util.Collection) items = ((java.util.Collection<?>) u).toArray();
            else if (u != null && u.getClass().isArray()) {
                items = new Object[Array.getLength(u)];
                for (int i = 0; i < items.length; i++) items[i] = Array.get(u, i);
            } else items = new Object[0];
            for (int i = 0; i < items.length; i++) items[i] = Context.javaToJS(items[i], scope);
            return cx.newArray(scope, items);
        }
    }
}
