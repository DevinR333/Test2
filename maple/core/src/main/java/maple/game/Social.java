package maple.game;

import maple.net.model.CharLook;

import java.util.ArrayList;
import java.util.List;

/** Friends, party, guild and Maple Messenger state, as the server last reported it. */
public final class Social {
    public static final class Buddy {
        public int id;
        public String name = "", group = "";
        public int channel = -1; // -1 offline
    }

    public static final class Member {
        public int id;
        public String name = "";
        public int job, level, channel = -2, mapId;
        public int rank; // guild rank 1..5
        public boolean online;
    }

    public final List<Buddy> buddies = new ArrayList<>();
    public int buddyCapacity = 20;

    /** Party id, or 0 when not in a party. */
    public int partyId;
    public int partyLeader;
    public final List<Member> party = new ArrayList<>();

    /** Guild id, or 0 when not in a guild. */
    public int guildId;
    public String guildName = "", guildNotice = "";
    public final String[] rankTitles = new String[5];
    public final List<Member> guild = new ArrayList<>();
    public int guildCapacity, guildPoints;

    /** Maple Messenger: whether it is open, the three seats and the chat log. */
    public boolean messengerOpen;
    public int messengerSeat = -1;
    public final String[] seatNames = new String[3];
    public final CharLook[] seatLooks = new CharLook[3];
    public final List<String> messengerLog = new ArrayList<>();
}
