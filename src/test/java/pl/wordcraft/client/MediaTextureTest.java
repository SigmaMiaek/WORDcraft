package pl.wordcraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;
import pl.wordcraft.media.MediaFrame;

class MediaTextureTest {
    @Test void rgbaFramesKeepColorsAlphaAndRowOrderInMinecraft1211() {
        byte[] rgba = {(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
                0, 0, (byte) 255, (byte) 255, 11, 22, 33, (byte) 128};
        try (NativeImage pixels = new NativeImage(2, 2, false)) {
            MediaTexture.copyPixels(pixels, new MediaFrame(2, 2, rgba, 0));
            assertEquals(0xff0000ff, pixels.getPixelRGBA(0, 0));
            assertEquals(0xff00ff00, pixels.getPixelRGBA(1, 0));
            assertEquals(0xffff0000, pixels.getPixelRGBA(0, 1));
            assertEquals(0x8021160b, pixels.getPixelRGBA(1, 1));
        }
    }
}
