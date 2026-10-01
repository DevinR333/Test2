package maple.sim;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

/** The full server + client flow with Android's missing JDK classes blocked. */
public class AndroidSimTest {
    @Test(timeout = 300000)
    public void fullFlowWithoutClassesAndroidLacks() throws Throwable {
        String xml = System.getenv("MAPLE_XML_WZ"), bin = System.getenv("MAPLE_BIN_WZ");
        Assume.assumeTrue("no game data", (xml != null && !xml.isEmpty() && new File(xml).isDirectory())
                || (bin != null && !bin.isEmpty() && new File(bin).isDirectory()));
        AndroidSimLoader loader = new AndroidSimLoader();
        Thread t = new Thread(() -> {
            try {
                Runnable flow = (Runnable) loader.loadClass("maple.sim.SimFlow").getDeclaredConstructor().newInstance();
                flow.run();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
        t.setContextClassLoader(loader);
        Throwable[] err = new Throwable[1];
        t.setUncaughtExceptionHandler((th, e) -> err[0] = e);
        t.start();
        t.join();
        System.out.println("Blocked classes requested: " + new java.util.TreeSet<>(loader.blockedHits));
        if (err[0] != null) throw err[0];
        assertTrue(true);
    }
}
