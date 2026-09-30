package maple.wz;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * The XOR key stream WZ files use to hide strings (and, rarely, image data).
 * It is AES-256/ECB applied repeatedly to a 4-byte IV, so each region's client has its own IV.
 */
public final class WzKey {
    private static final byte[] AES_KEY = {
            0x13, 0, 0, 0, 0x08, 0, 0, 0, 0x06, 0, 0, 0, (byte) 0xB4, 0, 0, 0,
            0x1B, 0, 0, 0, 0x0F, 0, 0, 0, 0x33, 0, 0, 0, 0x52, 0, 0, 0};

    public static final WzKey GMS = new WzKey("GMS", new byte[]{0x4D, 0x23, (byte) 0xC7, 0x2B});
    public static final WzKey KMS = new WzKey("KMS/EMS", new byte[]{(byte) 0xB9, 0x7D, 0x63, (byte) 0xE9});
    public static final WzKey NONE = new WzKey("unencrypted", new byte[]{0, 0, 0, 0});
    public static final WzKey[] ALL = {GMS, NONE, KMS};

    public final String name;
    private final byte[] iv;
    private final boolean zero;
    private byte[] keys = new byte[0];

    private WzKey(String name, byte[] iv) {
        this.name = name;
        this.iv = iv;
        this.zero = iv[0] == 0 && iv[1] == 0 && iv[2] == 0 && iv[3] == 0;
    }

    public byte at(int i) {
        if (zero) return 0;
        if (i >= keys.length) grow(i + 1);
        return keys[i];
    }

    private synchronized void grow(int size) {
        if (size <= keys.length) return;
        int newSize = Math.max(size, keys.length * 2);
        newSize = (newSize + 4095) & ~4095;
        byte[] out = new byte[newSize];
        System.arraycopy(keys, 0, out, 0, keys.length);
        try {
            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
            byte[] block = new byte[16];
            for (int i = keys.length; i < newSize; i += 16) {
                if (i == 0) {
                    for (int j = 0; j < 16; j++) block[j] = iv[j % 4];
                } else {
                    System.arraycopy(out, i - 16, block, 0, 16);
                }
                byte[] enc = aes.doFinal(block);
                System.arraycopy(enc, 0, out, i, 16);
            }
        } catch (Exception e) {
            throw new RuntimeException("AES unavailable", e);
        }
        keys = out;
    }
}
