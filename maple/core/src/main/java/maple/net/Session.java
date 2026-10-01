package maple.net;

import net.encryption.MapleAESOFB;
import net.encryption.MapleCustomEncryption;
import net.encryption.InitializationVector;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One connection to the (local) game server, speaking the v83 wire protocol: an unencrypted
 * handshake, then AES-OFB + Maple's custom cipher on every packet, like the original client.
 */
public final class Session {
    public static final short VERSION = 83;

    private final Socket socket;
    private final OutputStream out;
    private MapleAESOFB sendCypher, recvCypher;
    private final ConcurrentLinkedQueue<byte[]> incoming = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    private volatile String error;
    public final String name;

    public Session(String name, String host, int port) throws IOException {
        this.name = name;
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 10000);
        out = socket.getOutputStream();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        // Handshake: [short len] [short version] [string patch] [4 recvIV] [4 sendIV] [byte locale]
        int len = Short.reverseBytes(in.readShort()) & 0xFFFF;
        byte[] hello = new byte[len];
        in.readFully(hello);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(hello).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        short version = bb.getShort();
        int patchLen = bb.getShort() & 0xFFFF;
        bb.position(bb.position() + patchLen);
        byte[] serverRecvIv = new byte[4];
        byte[] serverSendIv = new byte[4];
        bb.get(serverRecvIv);
        bb.get(serverSendIv);
        if (version != VERSION) throw new IOException("server version " + version);
        // We send with the IV the server receives with, and vice versa.
        sendCypher = new MapleAESOFB(iv(serverRecvIv), VERSION);
        recvCypher = new MapleAESOFB(iv(serverSendIv), (short) (0xFFFF - VERSION));
        Thread t = new Thread(() -> readLoop(in), "net-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static InitializationVector iv(byte[] b) {
        return InitializationVector.of(b);
    }

    private void readLoop(DataInputStream in) {
        try {
            byte[] header = new byte[4];
            while (!closed) {
                in.readFully(header);
                int length = ((((header[1] ^ header[3]) & 0xFF) << 8) | ((header[0] ^ header[2]) & 0xFF));
                byte[] data = new byte[length];
                in.readFully(data);
                recvCypher.crypt(data);
                MapleCustomEncryption.decryptData(data);
                int opcode = data.length >= 2 ? (data[0] & 0xFF) | (data[1] & 0xFF) << 8 : -1;
                if (opcode == net.opcodes.SendOpcode.PING.getValue()) {
                    // Keep-alive: answer right away (the server drops clients that don't answer within 15 s).
                    send(new PacketWriter(net.opcodes.RecvOpcode.PONG.getValue()));
                    continue;
                }
                incoming.add(data);
            }
        } catch (EOFException e) {
            if (!closed) error = "Disconnected";
        } catch (IOException e) {
            if (!closed) error = e.getMessage();
        } finally {
            closed = true;
        }
    }

    public synchronized void send(PacketWriter w) {
        if (closed) return;
        byte[] data = w.bytes();
        try {
            byte[] header = sendCypher.getPacketHeader(data.length);
            MapleCustomEncryption.encryptData(data);
            sendCypher.crypt(data);
            byte[] all = new byte[4 + data.length];
            System.arraycopy(header, 0, all, 0, 4);
            System.arraycopy(data, 0, all, 4, data.length);
            out.write(all);
            out.flush();
        } catch (IOException e) {
            error = e.getMessage();
            close();
        }
    }

    /** Next received packet, or null. */
    public PacketReader poll() {
        byte[] d = incoming.poll();
        return d == null ? null : new PacketReader(d);
    }

    public boolean isClosed() { return closed; }
    public String error() { return error; }

    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
