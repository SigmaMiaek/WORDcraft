package pl.wordcraft.media;

public record MediaFrame(int width, int height, byte[] rgba, long timeUs) {
    public MediaFrame {
        if (width <= 0 || height <= 0 || (long)width * height * 4L != rgba.length) {
            throw new IllegalArgumentException("Nieprawidłowy bufor RGBA");
        }

        if (timeUs < 0L) {
            throw new IllegalArgumentException("Ujemny czas klatki");
        }
    }
}
