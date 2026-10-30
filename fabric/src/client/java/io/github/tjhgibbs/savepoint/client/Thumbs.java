package io.github.tjhgibbs.savepoint.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.tjhgibbs.savepoint.mod.SavepointMod;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Thumbnails of the savepoint list, fetched lazily (only for rows that are on screen) and shown through
 * {@link DynamicTexture}s. The PNG bytes of recent ones are kept (they never change) so reopening the screen does
 * not flicker; the GPU textures are released by {@link #release()} when the screen closes. Client thread only.
 */
final class Thumbs {
    private static final int MAX_IN_FLIGHT = 3;
    private static final int MAX_CACHED = 120;
    private static final AtomicInteger NEXT = new AtomicInteger();

    private record Tex(Identifier id, DynamicTexture texture, int w, int h) {}

    private static final Map<String, byte[]> bytes = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > MAX_CACHED;
        }
    };
    private static final Map<String, Tex> textures = new HashMap<>();
    private static final Set<String> requested = new HashSet<>();

    private Thumbs() {}

    /**
     * Draws the savepoint's thumbnail into the box (cropping nothing: the box is 16:9 like the image). Returns false if
     * there is none yet (the caller draws a placeholder). Asks the server for it the first time.
     */
    static boolean draw(GuiGraphicsExtractor g, String id, boolean hasThumbnail, int x, int y, int w, int h) {
        if (!hasThumbnail) return false;
        Tex t = textures.get(id);
        if (t == null) {
            byte[] png = bytes.get(id);
            if (png != null) t = upload(id, png);
            else request(id);
        }
        if (t == null) return false;
        g.blit(RenderPipelines.GUI_TEXTURED, t.id, x, y, 0, 0, w, h, t.w, t.h, t.w, t.h);
        return true;
    }

    private static void request(String id) {
        if (requested.contains(id) || requested.size() >= MAX_IN_FLIGHT) return;
        if (!ClientPlayNetworking.canSend(SavepointPayloads.RequestThumbnailPayload.TYPE)) return;
        requested.add(id);
        ClientPlayNetworking.send(new SavepointPayloads.RequestThumbnailPayload(id));
    }

    static void onReceived(SavepointPayloads.ThumbnailPayload p) {
        requested.remove(p.id());
        bytes.put(p.id(), p.png());
    }

    private static Tex upload(String id, byte[] png) {
        try {
            NativeImage img = NativeImage.read(png);
            Identifier tid = Identifier.fromNamespaceAndPath(SavepointMod.MOD_ID, "dynamic/thumb_" + NEXT.getAndIncrement());
            DynamicTexture dt = new DynamicTexture(() -> "savepoint thumbnail", img);
            Minecraft.getInstance().getTextureManager().register(tid, dt);
            Tex t = new Tex(tid, dt, img.getWidth(), img.getHeight());
            textures.put(id, t);
            return t;
        } catch (IOException | RuntimeException e) {
            bytes.remove(id);
            requested.add(id); // do not ask again this session
            return null;
        }
    }

    /** Frees the GPU textures. Call when the screen that showed them is removed. */
    static void release() {
        for (Tex t : textures.values()) Minecraft.getInstance().getTextureManager().release(t.id);
        textures.clear();
        requested.clear();
    }

    /** Forget everything (left the world). */
    static void reset() {
        release();
        bytes.clear();
    }
}
