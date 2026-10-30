package io.github.tjhgibbs.savepoint.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 helpers. Hashes are lowercase 64-character hex strings. */
public final class Hashes {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashes() {}

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = HEX[(b[i] >> 4) & 15];
            c[i * 2 + 1] = HEX[b[i] & 15];
        }
        return new String(c);
    }

    public static String sha256Hex(byte[] data) {
        return hex(sha256().digest(data));
    }

    /** True if {@code s} is a well-formed hash (so it is safe to use in a file name). */
    public static boolean isHash(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char ch = s.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) return false;
        }
        return true;
    }
}
