package maple.net.model;

/** The character stat block sent in the character list and on login (addCharStats). */
public final class CharStats {
    public int id;
    public String name;
    public int gender, skin, face, hair;
    public long[] petIds = new long[3];
    public int level, job, str, dex, intel, luk, hp, maxHp, mp, maxMp, ap, sp;
    public int exp, fame, gachaExp, mapId, spawnPoint;
}
