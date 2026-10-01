package maple.game;

/** One page of an NPC conversation (the server's NPC_TALK packet). */
public final class NpcTalk {
    /** v83 message types: 0 say, 1 yes/no, 2 get text, 3 get number, 4 simple (menu), 7 style, 8 pet, 0x0C accept/decline. */
    public int type;
    public int npcId;
    public int speaker;
    public String text = "";
    /** For type 0: the "prev"/"next" flags from the end bytes. */
    public boolean prev, next;
    /** Type 2: default text. */
    public String defText = "";
    /** Type 3: default, min, max. */
    public int def, min, max;
    /** Type 7: selectable style ids (hair/face). */
    public int[] styles = new int[0];
}
