package maple.game;

/** What the game tells the interface (chat log, dialogs, windows to refresh...). */
public interface GameEvents {
    /** A line for the chat log. Color is ARGB. */
    void chat(String text, int color);

    /** Yellow notice at the top / status line (pickup messages, EXP gained...). */
    void status(String text, int color);

    /** An NPC conversation page (or null to close the dialog). */
    void npcTalk(NpcTalk talk);

    /** An NPC shop opened. */
    void shop(Shop shop);

    /** Shop transaction result code. */
    void shopResult(int code);

    /** Stats, inventory, skills or quests changed: refresh windows/HUD. */
    void refresh();

    /** A server notice popup (type 1) or similar. */
    void popup(String text);

    /** Storage opened/updated. */
    void storage(Storage storage);

    /** The player died: show the revive notice. */
    void died();

    /** Someone invited you to their party. */
    default void partyInvite(int partyId, String from) {}

    /** A Maple Life item was used: open the character creation screen for it. */
    default void mapleLife(int slot, int itemId) {}

    /** An NPC asked for the name of a new guild. */
    default void guildNamePrompt() {}

    /** Key bindings arrived from the server. */
    void keymap(int[] types, int[] actions);
}
