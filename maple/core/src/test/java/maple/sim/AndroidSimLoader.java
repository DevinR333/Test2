package maple.sim;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the game with a class loader that refuses the JDK classes Android does not ship, so any code
 * path that would fail on a phone with NoClassDefFoundError fails the same way in a desktop test.
 */
public final class AndroidSimLoader extends URLClassLoader {
    static final String[] BLOCKED = {
            "javax.sql.XA", "javax.transaction.", "javax.naming.", "java.lang.management.", "javax.management.",
            "javax.script.", "javax.tools.", "java.awt.", "javax.swing.", "java.rmi.", "javax.rmi.",
            "javax.servlet.", "javax.security.auth.kerberos.", "javax.lang.model.", "java.lang.instrument.",
            "jdk.", "com.sun.", "sun.misc.Signal", "java.net.http.", "javax.imageio.", "javax.sound.",
            "java.beans.Introspector", "java.beans.BeanInfo", "java.beans.PropertyDescriptor", "java.beans.Beans",
            "java.util.prefs.", "javax.print.", "javax.accessibility."};

    public final List<String> blockedHits = new ArrayList<>();

    public AndroidSimLoader() throws MalformedURLException {
        super(classpath(), ClassLoader.getPlatformClassLoader());
    }

    private static URL[] classpath() throws MalformedURLException {
        String[] parts = System.getProperty("java.class.path").split(File.pathSeparator);
        List<URL> urls = new ArrayList<>();
        for (String p : parts) urls.add(new File(p).toURI().toURL());
        return urls.toArray(new URL[0]);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String b : BLOCKED) {
            if (name.startsWith(b)) {
                synchronized (blockedHits) {
                    blockedHits.add(name);
                }
                throw new ClassNotFoundException("Not available on Android: " + name);
            }
        }
        return super.loadClass(name, resolve);
    }
}
