package io.github.tjhgibbs.savepoint.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.tjhgibbs.savepoint.mod.SavepointMod;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Util;

/**
 * Keeps one small screenshot of the world as the player last saw it, taken just before a screen opened over it (so
 * the savepoint list never photographs itself). When the server reports a new savepoint the picture is uploaded as
 * its thumbnail. The shot is shrunk to a 16:9 PNG that fits one network packet; cost is one GPU read-back plus a
 * few ms on the IO pool.
 */
final class SceneShots {
    private static final int[][] SIZES = {{256, 144}, {192, 108}, {128, 72}};

    /** Latest encoded shot, or null before the first one finished. */
    private static volatile byte[] latest;
    private static volatile long latestAt;
    /** A savepoint waiting for a shot that is still being encoded. */
    private static volatile String waitingId;
    private static volatile boolean busy;

    private SceneShots() {}

    /** Photographs the last rendered frame (must not be showing our own UI). Client thread. */
    static void capture(Minecraft mc) {
        if (mc.level == null || busy) return;
        busy = true;
        try {
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> Util.ioPool().execute(() -> {
                try (image) {
                    byte[] png = encode(image);
                    latest = png;
                    latestAt = System.currentTimeMillis();
                } catch (RuntimeException e) {
                    SavepointMod.LOG.debug("Scene shot failed", e);
                } finally {
                    busy = false;
                }
                String id = waitingId;
                if (id != null && latest != null) {
                    waitingId = null;
                    Minecraft.getInstance().execute(() -> upload(id));
                }
            }));
        } catch (RuntimeException e) {
            busy = false;
            SavepointMod.LOG.debug("Scene shot failed", e);
        }
    }

    /** The server made savepoint {@code id}: send the newest shot for it (now, or as soon as it is encoded). Client thread. */
    static void attachTo(String id) {
        if (latest != null && !busy) upload(id);
        else waitingId = id;
    }

    /** The newest shot if it is recent enough to fairly show the world as it is now, else null. Any thread. */
    static byte[] fresh() {
        byte[] png = latest;
        if (png == null || png.length > SavepointPayloads.MAX_THUMBNAIL) return null;
        return System.currentTimeMillis() - latestAt > 10 * 60_000L ? null : png;
    }

    private static void upload(String id) {
        byte[] png = fresh();
        if (png == null) return;
        if (ClientPlayNetworking.canSend(SavepointPayloads.ThumbnailUploadPayload.TYPE)) {
            ClientPlayNetworking.send(new SavepointPayloads.ThumbnailUploadPayload(id, png));
        }
    }

    static void clear() {
        latest = null;
        waitingId = null;
    }

    private static byte[] encode(NativeImage img) {
        int sw = img.getWidth(), sh = img.getHeight();
        int cw = Math.min(sw, sh * 16 / 9), ch = cw * 9 / 16; // centre crop to 16:9
        int x0 = (sw - cw) / 2, y0 = (sh - ch) / 2;
        byte[] best = null;
        for (int[] size : SIZES) {
            int[] px = shrink(img, x0, y0, cw, ch, size[0], size[1]);
            for (int drop : new int[] {3, 4, 5}) {
                byte[] png = PngEncoder.encode(px, size[0], size[1], drop);
                if (png.length <= SavepointPayloads.MAX_THUMBNAIL) return png;
                best = png;
            }
        }
        return best;
    }

    /** Point-sampled box filter: 3x3 samples per output pixel. */
    private static int[] shrink(NativeImage img, int x0, int y0, int cw, int ch, int dw, int dh) {
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int r = 0, g = 0, b = 0;
                for (int j = 0; j < 3; j++) {
                    for (int i = 0; i < 3; i++) {
                        int sx = x0 + (int) ((x + (i + 0.5) / 3) * cw / dw);
                        int sy = y0 + (int) ((y + (j + 0.5) / 3) * ch / dh);
                        int p = img.getPixel(Math.min(sx, x0 + cw - 1), Math.min(sy, y0 + ch - 1));
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                out[y * dw + x] = 0xFF000000 | (r / 9) << 16 | (g / 9) << 8 | (b / 9);
            }
        }
        return out;
    }
}
