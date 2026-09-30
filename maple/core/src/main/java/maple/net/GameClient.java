package maple.net;

import maple.net.model.CharEntry;
import maple.net.model.PlayerData;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The client side of the v83 protocol against the local server. Logs in automatically (offline
 * there is no account to type), lists characters, creates and selects them, and enters the game.
 * Everything after that is passed to {@link #inGameHandler}.
 */
public final class GameClient {
    public enum State { CONNECTING, LOGGING_IN, CHARACTER_SELECT, CREATING, ENTERING, IN_GAME, FAILED }

    public static final String HOST = "127.0.0.1";
    public static final int LOGIN_PORT = 8484;
    public static final String ACCOUNT = "offline";
    public static final String PASSWORD = "offline";
    private static final byte[] HWID = {0x1A, 0x2B, 0x3C, 0x4D};
    private static final String HOST_STRING = "0A1B2C3D4E5F_1A2B3C4D";

    public volatile State state = State.CONNECTING;
    public String error;
    public final List<CharEntry> characters = new ArrayList<>();
    public int characterSlots;
    public String worldName = "";
    public int accountId;
    public PlayerData player;
    /** Result of the last name check: null = pending. */
    public Boolean nameAvailable;
    public String checkedName;
    /** Called for every packet once in game (on the game thread). */
    public Consumer<PacketReader> inGameHandler;
    /** Called when a new SET_FIELD (map change) arrives: map id, spawn portal. */
    public Consumer<int[]> warpHandler;

    private Session login, channel;
    private int selectedId;

    public void start() {
        try {
            login = new Session("login", HOST, LOGIN_PORT);
        } catch (IOException e) {
            fail("Could not reach the game server: " + e.getMessage());
            return;
        }
        state = State.LOGGING_IN;
        sendLogin();
    }

    private void sendLogin() {
        login.send(new PacketWriter(RecvOpcode.LOGIN_PASSWORD.getValue())
                .writeString(ACCOUNT).writeString(PASSWORD)
                .writeBytes(new byte[6]).writeBytes(HWID));
    }

    private void fail(String msg) {
        error = msg;
        state = State.FAILED;
    }

    /** Process everything received since the last call. Call once per frame. */
    public void update() {
        if (login != null) {
            PacketReader r;
            while (login != null && (r = login.poll()) != null) handleLogin(r);
            if (login != null && login.isClosed() && login.error() != null && state.ordinal() < State.ENTERING.ordinal()) {
                fail("Lost connection to the game server: " + login.error());
            }
        }
        if (channel != null) {
            PacketReader r;
            while ((r = channel.poll()) != null) handleChannel(r);
            if (channel.isClosed() && state == State.IN_GAME) {
                fail("Lost connection to the game server" + (channel.error() == null ? "" : ": " + channel.error()));
            }
        }
    }

    private void handleLogin(PacketReader r) {
        if (r.opcode == SendOpcode.LOGIN_STATUS.getValue()) {
            int reason = r.readUByte();
            if (reason == 23) { // terms of service for a new account: accept
                login.send(new PacketWriter(RecvOpcode.ACCEPT_TOS.getValue()).writeByte(1));
                return;
            }
            if (reason != 0) {
                fail("Login failed (code " + reason + ")");
                return;
            }
            r.readByte();
            r.readShort();
            accountId = r.readInt();
            int gender = r.readByte();
            if (gender == 10) { // account gender not chosen yet
                login.send(new PacketWriter(RecvOpcode.SET_GENDER.getValue()).writeByte(1).writeByte(0));
                return;
            }
            login.send(new PacketWriter(RecvOpcode.SERVERLIST_REQUEST.getValue()));
        } else if (r.opcode == SendOpcode.SERVERLIST.getValue()) {
            int world = r.readUByte();
            if (world == 0xFF) {
                // End of the list: open world 0, channel 1.
                login.send(new PacketWriter(RecvOpcode.CHARLIST_REQUEST.getValue()).writeByte(0).writeByte(0).writeByte(0));
            } else if (worldName.isEmpty()) {
                worldName = r.readString();
            }
        } else if (r.opcode == SendOpcode.CHARLIST.getValue()) {
            int status = r.readUByte();
            if (status != 0) {
                fail("Character list unavailable (" + status + ")");
                return;
            }
            characters.clear();
            int n = r.readUByte();
            for (int i = 0; i < n; i++) characters.add(Decode.charEntry(r));
            r.readByte(); // PIC mode
            characterSlots = r.readInt();
            state = State.CHARACTER_SELECT;
        } else if (r.opcode == SendOpcode.CHAR_NAME_RESPONSE.getValue()) {
            checkedName = r.readString();
            nameAvailable = r.readByte() == 0;
        } else if (r.opcode == SendOpcode.ADD_NEW_CHAR_ENTRY.getValue()) {
            if (r.readByte() == 0) characters.add(Decode.charEntry(r));
            state = State.CHARACTER_SELECT;
        } else if (r.opcode == SendOpcode.DELETE_CHAR_RESPONSE.getValue()) {
            int cid = r.readInt();
            int status = r.readUByte();
            if (state == State.CREATING) {
                error = "Could not create that character (" + status + ")";
                state = State.CHARACTER_SELECT;
            } else if (status == 0) {
                characters.removeIf(c -> c.stats.id == cid);
            }
        } else if (r.opcode == SendOpcode.SERVER_IP.getValue()) {
            r.readShort();
            byte[] ip = r.readBytes(4);
            int port = r.readUShort();
            int cid = r.readInt();
            String host = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
            login.close();
            login = null;
            try {
                channel = new Session("channel", host, port);
            } catch (IOException e) {
                fail("Could not join the channel: " + e.getMessage());
                return;
            }
            channel.send(new PacketWriter(RecvOpcode.PLAYER_LOGGEDIN.getValue()).writeInt(cid));
        } else if (r.opcode == SendOpcode.LOGIN_STATUS.getValue() || r.opcode == SendOpcode.SELECT_CHARACTER_BY_VAC.getValue()) {
            // handled above / unused
        }
    }

    private void handleChannel(PacketReader r) {
        if (r.opcode == SendOpcode.SET_FIELD.getValue()) {
            int ch = r.readInt();
            int kind = r.readUByte();
            if (kind == 1) {
                r.readByte();
                r.readShort();
                r.readInt();
                r.readInt();
                r.readInt();
                player = Decode.characterInfo(r, ch);
                state = State.IN_GAME;
                if (warpHandler != null) warpHandler.accept(new int[]{player.stats.mapId, player.stats.spawnPoint, Integer.MIN_VALUE, 0});
            } else {
                r.skip(3); // rest of int 0
                r.readByte();
                int mapId = r.readInt();
                int spawn = r.readUByte();
                int hp = r.readShort();
                boolean pos = r.readBool();
                int x = Integer.MIN_VALUE, y = 0;
                if (pos) {
                    x = r.readInt();
                    y = r.readInt();
                }
                if (player != null) {
                    player.stats.mapId = mapId;
                    player.stats.hp = hp;
                }
                if (warpHandler != null) warpHandler.accept(new int[]{mapId, spawn, x, y});
            }
            return;
        }
        if (inGameHandler != null) inGameHandler.accept(r);
    }

    // ---- actions ----

    public void checkName(String name) {
        nameAvailable = null;
        login.send(new PacketWriter(RecvOpcode.CHECK_CHAR_NAME.getValue()).writeString(name));
    }

    /** job: 0 Cygnus, 1 Explorer, 2 Aran. hair is the style id; hairColor is added to it by the server. */
    public void createCharacter(String name, int job, int face, int hair, int hairColor, int skin,
                                int top, int bottom, int shoes, int weapon, int gender) {
        state = State.CREATING;
        error = null;
        login.send(new PacketWriter(RecvOpcode.CREATE_CHAR.getValue())
                .writeString(name).writeInt(job).writeInt(face).writeInt(hair).writeInt(hairColor).writeInt(skin)
                .writeInt(top).writeInt(bottom).writeInt(shoes).writeInt(weapon).writeByte(gender));
    }

    public void selectCharacter(int charId) {
        selectedId = charId;
        state = State.ENTERING;
        login.send(new PacketWriter(RecvOpcode.CHAR_SELECT.getValue())
                .writeInt(charId).writeString("0A-1B-2C-3D-4E-5F").writeString(HOST_STRING));
    }

    /** Send a packet on the game channel. */
    public void send(PacketWriter w) {
        if (channel != null) channel.send(w);
    }

    public void close() {
        if (login != null) login.close();
        if (channel != null) channel.close();
    }
}
