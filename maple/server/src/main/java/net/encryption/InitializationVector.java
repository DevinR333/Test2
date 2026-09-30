package net.encryption;

public class InitializationVector {
    private final byte[] bytes;

    private InitializationVector(byte[] bytes) {
        this.bytes = bytes;
    }

    /** An IV received from the other side (used by the in-app client). */
    public static InitializationVector of(byte[] bytes) {
        return new InitializationVector(bytes.clone());
    }

    public byte[] getBytes() {
        return bytes;
    }

    public static InitializationVector generateSend() {
        byte[] ivSend = {82, 48, 120, getRandomByte()};
        return new InitializationVector(ivSend);
    }

    public static InitializationVector generateReceive() {
        byte[] ivRecv = {70, 114, 122, getRandomByte()};
        return new InitializationVector(ivRecv);
    }

    private static byte getRandomByte() {
        return (byte) (Math.random() * 255);
    }
}
