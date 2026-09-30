package maple.net.model;

/** One character on the character select screen. */
public final class CharEntry {
    public final CharStats stats = new CharStats();
    public final CharLook look = new CharLook();
    public boolean rankEnabled;
    public int rank, rankMove, jobRank, jobRankMove;
}
