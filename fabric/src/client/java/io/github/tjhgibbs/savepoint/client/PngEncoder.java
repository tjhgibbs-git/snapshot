package io.github.tjhgibbs.savepoint.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Minimal 8-bit RGB PNG writer with per-row filter selection and optional colour quantisation. Needed because the
 * game can only write PNGs to files, and a thumbnail has to fit one 32 KB network packet.
 */
final class PngEncoder {
    private PngEncoder() {}

    /** @param dropBits low bits cleared per channel before compression (0 = lossless), makes smooth areas compress far better */
    static byte[] encode(int[] argb, int w, int h, int dropBits) {
        int stride = w * 3;
        byte[] raw = new byte[h * (stride + 1)];
        byte[] prev = new byte[stride];
        byte[] cur = new byte[stride];
        byte[][] cand = new byte[5][stride];
        int mask = (0xFF << dropBits) & 0xFF;
        int round = dropBits == 0 ? 0 : 1 << (dropBits - 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                cur[x * 3] = (byte) q((p >> 16) & 0xFF, mask, round);
                cur[x * 3 + 1] = (byte) q((p >> 8) & 0xFF, mask, round);
                cur[x * 3 + 2] = (byte) q(p & 0xFF, mask, round);
            }
            int best = 0;
            long bestSum = Long.MAX_VALUE;
            for (int f = 0; f < 5; f++) {
                long sum = 0;
                for (int i = 0; i < stride; i++) {
                    int a = i >= 3 ? cur[i - 3] & 0xFF : 0;
                    int b = prev[i] & 0xFF;
                    int c = i >= 3 ? prev[i - 3] & 0xFF : 0;
                    int pred = switch (f) {
                        case 0 -> 0;
                        case 1 -> a;
                        case 2 -> b;
                        case 3 -> (a + b) / 2;
                        default -> paeth(a, b, c);
                    };
                    byte v = (byte) ((cur[i] & 0xFF) - pred);
                    cand[f][i] = v;
                    sum += Math.abs((int) v);
                }
                if (sum < bestSum) {
                    bestSum = sum;
                    best = f;
                }
            }
            raw[y * (stride + 1)] = (byte) best;
            System.arraycopy(cand[best], 0, raw, y * (stride + 1) + 1, stride);
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) idat.write(buf, 0, d.deflate(buf));
        d.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0).array();
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static int q(int v, int mask, int round) {
        return Math.min(255, v + round) & mask;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
