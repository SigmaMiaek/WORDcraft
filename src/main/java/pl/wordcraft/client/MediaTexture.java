package pl.wordcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import pl.wordcraft.media.MediaFrame;

final class MediaTexture implements AutoCloseable {
    private static final AtomicInteger SERIAL = new AtomicInteger();
    private final Identifier id = Identifier.fromNamespaceAndPath("wordcraft", "local_media_" + SERIAL.incrementAndGet());
    private DynamicTexture texture;
    private int width;
    private int height;

    void update(MediaFrame frame) {
        if (frame != null) {
            if (this.texture == null || this.width != frame.width() || this.height != frame.height()) {
                this.close();
                this.width = frame.width();
                this.height = frame.height();
                this.texture = new DynamicTexture(() -> "WORDcraft local multimedia", new NativeImage(this.width, this.height, false));
                Minecraft.getInstance().getTextureManager().register(this.id, this.texture);
            }

            this.texture.getPixels().getPixelBytes().clear().put(frame.rgba());
            this.texture.upload();
        }
    }

    boolean available() {
        return this.texture != null;
    }

    int draw(GuiGraphicsExtractor g, int x, int y, int maxWidth, int maxHeight) {
        if (this.texture == null) {
            return 0;
        }

        double scale = Math.min((double)maxWidth / this.width, (double)maxHeight / this.height);
        int w = Math.max(1, (int)Math.round(this.width * scale));
        int h = Math.max(1, (int)Math.round(this.height * scale));
        g.fill(x, y, x + maxWidth, y + h, -16447479);
        g.blit(RenderPipelines.GUI_TEXTURED, this.id, x + (maxWidth - w) / 2, y, 0.0F, 0.0F, w, h, this.width, this.height, this.width, this.height);
        return h;
    }

    void drawCentered(GuiGraphicsExtractor g, int x, int y, int maxWidth, int maxHeight) {
        if (this.texture != null && maxWidth > 0 && maxHeight > 0) {
            double scale = Math.min((double)maxWidth / this.width, (double)maxHeight / this.height);
            int w = Math.max(1, (int)Math.round(this.width * scale));
            int h = Math.max(1, (int)Math.round(this.height * scale));
            g.fill(x, y, x + maxWidth, y + maxHeight, -16447479);
            g.blit(
                RenderPipelines.GUI_TEXTURED,
                this.id,
                x + (maxWidth - w) / 2,
                y + (maxHeight - h) / 2,
                0.0F,
                0.0F,
                w,
                h,
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
