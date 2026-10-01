package offline;

/** Optional extras the player can switch on in the options (all off = the original game). */
public final class OfflineOptions {
    /** Every hairstyle and face in Character.wz may be picked at character creation. */
    public static volatile boolean allStyles;
    /** Monsters sometimes drop Cash Shop equipment. */
    public static volatile boolean cashDrops;

    private OfflineOptions() {}
}
