package offline;

/** Optional extras the player can switch on in the options (all off = the original game). */
public final class OfflineOptions {
    /** Every hairstyle and face in Character.wz may be picked at character creation. */
    public static volatile boolean allStyles;
    /** Monsters sometimes drop Cash Shop equipment. */
    public static volatile boolean cashDrops;
    /** Everything in the Cash Shop is free (offline there is no way to buy NX). */
    public static volatile boolean freeCashShop = true;
    /** Cash items (rentals, pets) never expire. */
    public static volatile boolean permanentCash = true;
    /** The Cash Shop also sells its retired seasonal and limited items. */
    public static volatile boolean limitedCash = true;
    /** Holiday events all year: dated event quests stay open and holiday monsters roam (map changes need a restart). */
    public static volatile boolean holidays = true;
    /** Pets loot mesos and items without wearing a Meso Magnet / Item Pouch. */
    public static volatile boolean petLoot = true;
    /** Ultimate Explorers' Empress's Blessing: other characters gain from your best Cygnus Knight's level. */
    public static volatile boolean empressBlessing = true;
    /** World rates, changeable while playing (OfflineServer.applyRates). */
    public static volatile int expRate = 3, mesoRate = 5, dropRate = 1;

    private OfflineOptions() {}
}
