package pl.wordcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import pl.wordcraft.media.MediaFrame;

final class MediaTexture implements AutoCloseable {
    private static final AtomicInteger SERIAL = new AtomicInteger();
    private final ResourceLocation id = ResourceLocation.fromNamespaceAndPath("wordcraft", "local_media_" + SERIAL.incrementAndGet());
    private DynamicTexture texture;
    private int width;
    private int height;

    void update(MediaFrame frame) {
        if (frame != null) {
            if (this.texture == null || this.width != frame.width() || this.height != frame.height()) {
                this.close();
                this.width = frame.width();
                this.height = frame.height();
                this.texture = new DynamicTexture(new NativeImage(this.width, this.height, false));
                Minecraft.getInstance().getTextureManager().register(this.id, this.texture);
            }

            copyPixels(this.texture.getPixels(), frame);
            this.texture.upload();
        }
    }

    // NativeImage in 1.21.1 accepts packed ABGR integers instead of an RGBA byte buffer.
    static void copyPixels(NativeImage pixels, MediaFrame frame) {
        byte[] rgba = frame.rgba();
        for (int y = 0, offset = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++, offset += 4) {
                int abgr = (rgba[offset] & 255) | ((rgba[offset + 1] & 255) << 8)
                        | ((rgba[offset + 2] & 255) << 16) | ((rgba[offset + 3] & 255) << 24);
                pixels.setPixelRGBA(x, y, abgr);
            }
        }
    }

    boolean available() {
        return this.texture != null;
    }

    int draw(GuiGraphics g, int x, int y, int maxWidth, int maxHeight) {
        if (this.texture == null) {
            return 0;
        }

        double scale = Math.min((double)maxWidth / this.width, (double)maxHeight / this.height);
        int w = Math.max(1, (int)Math.round(this.width * scale));
        int h = Math.max(1, (int)Math.round(this.height * scale));
        g.fill(x, y, x + maxWidth, y + h, -16447479);
        g.blit(this.id, x + (maxWidth - w) / 2, y, w, h, 0.0F, 0.0F, this.width, this.height, this.width, this.height);
        return h;
    }

    void drawCentered(GuiGraphics g, int x, int y, int maxWidth, int maxHeight) {
        if (this.texture != null && maxWidth > 0 && maxHeight > 0) {
            double scale = Math.min((double)maxWidth / this.width, (double)maxHeight / this.height);
            int w = Math.max(1, (int)Math.round(this.width * scale));
            int h = Math.max(1, (int)Math.round(this.height * scale));
            g.fill(x, y, x + maxWidth, y + maxHeight, -16447479);
            g.blit(
                this.id,
                x + (maxWidth - w) / 2,
                y + (maxHeight - h) / 2,
                w,
                h,
                0.0F,
                0.0F,
                this.width,
                this.height,
                this.width,
                this.height
            );
        }
    }

    @Override
    public void close() {
        if (this.texture != null) {
            Minecraft.getInstance().getTextureManager().release(this.id);
            this.texture = null;
        }
    }
}
