package provider;

import maple.wz.Wz;
import maple.wz.WzFile;
import provider.wz.WZFiles;
import provider.wz.WzDataProvider;

import java.util.EnumMap;
import java.util.Map;

/** Hands out data providers backed by the player's own .wz files. */
public class DataProviderFactory {
    /** Set by the app before the server starts. */
    public static volatile Wz wz;
    /** Tests can supply data another way (e.g. an XML export). */
    public static volatile java.util.function.Function<WZFiles, DataProvider> override;
    private static final Map<WZFiles, DataProvider> cache = new EnumMap<>(WZFiles.class);

    public static synchronized DataProvider getDataProvider(WZFiles in) {
        DataProvider p = cache.get(in);
        if (p != null) return p;
        if (override != null) {
            p = override.apply(in);
            cache.put(in, p);
            return p;
        }
        if (wz == null) throw new IllegalStateException("DataProviderFactory.wz is not set");
        WzFile f = null;
        String base = in.getBaseName();
        if (!base.equals("List")) {
            try {
                f = wz.file(base);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(DataProviderFactory.class).warn("{}.wz unavailable: {}", base, e.getMessage());
            }
        }
        p = new WzDataProvider(f);
        cache.put(in, p);
        return p;
    }
}
