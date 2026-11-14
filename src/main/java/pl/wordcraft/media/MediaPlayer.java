package pl.wordcraft.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.DataLine.Info;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

public final class MediaPlayer implements AutoCloseable {
    private static final long MAX_PIXELS = 16777216L;
    private static final int MAX_DIMENSION = 8192;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int MAX_PREVIEW_AUDIO_BYTES = 88200;
    private final Object control = new Object();
    private final AtomicReference<MediaFrame> pending = new AtomicReference<>();
    private final AtomicBoolean endedEvent = new AtomicBoolean();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
        Thread thread = new Thread(task, "WORDcraft-media");
        thread.setDaemon(true);
        thread.setPriority(4);
        return thread;
    });
    private volatile MediaPlayer.State state = MediaPlayer.State.EMPTY;
    private volatile MediaFrame current;
    private volatile String error;
    private volatile String warning;
    private volatile long durationUs;
    private volatile boolean video;
    private volatile boolean hasAudio;
    private volatile MediaPlayer.Task active;
    private Future<?> future;
    private Path source;

    public void load(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Brak ścieżki multimediów");
        }

        synchronized (this.control) {
            if (this.state != MediaPlayer.State.CLOSED) {
                this.cancelLocked();
                this.source = path.toAbsolutePath().normalize();
                this.current = null;
                this.pending.set(null);
                this.endedEvent.set(false);
                this.error = null;
                this.warning = null;
                this.durationUs = 0L;
                this.hasAudio = false;
                this.video = !isImagePath(this.source);
                this.state = MediaPlayer.State.LOADING;
                MediaPlayer.Task task = new MediaPlayer.Task(this.source, this.video);
                this.active = task;
                this.worker.purge();
                this.future = this.worker.submit(() -> this.decode(task));
            }
        }
    }

    public void play() {
        synchronized (this.control) {
            if (this.active != null && !this.active.cancelled && this.state != MediaPlayer.State.ERROR && this.state != MediaPlayer.State.CLOSED) {
                this.active.playRequested = true;
                this.control.notifyAll();
            }
        }
    }

    public void replay() {
        synchronized (this.control) {
            if (this.source != null && this.state != MediaPlayer.State.CLOSED) {
                this.load(this.source);
                this.play();
            }
        }
    }

    public MediaFrame pollFrame() {
        return this.pending.getAndSet(null);
    }

    public MediaFrame currentFrame() {
        return this.current;
    }

    public MediaPlayer.State state() {
        return this.state;
    }

    public boolean isReady() {
        return this.state == MediaPlayer.State.READY || this.state == MediaPlayer.State.PLAYING || this.state == MediaPlayer.State.ENDED;
    }

    public boolean isPlaying() {
        return this.state == MediaPlayer.State.PLAYING;
    }

    public boolean isEnded() {
        return this.state == MediaPlayer.State.ENDED;
    }

    public boolean isVideo() {
        return this.video;
    }

    public boolean hasAudio() {
        return this.hasAudio;
    }

    public String getError() {
        return this.error;
    }

    public String warning() {
        return this.warning;
    }

    public long durationUs() {
        return this.durationUs;
    }

    public boolean consumeEndedEvent() {
        return this.endedEvent.getAndSet(false);
    }

    @Override
    public void close() {
        synchronized (this.control) {
            if (this.state != MediaPlayer.State.CLOSED) {
                this.cancelLocked();
                this.active = null;
                this.source = null;
                this.current = null;
                this.pending.set(null);
                this.endedEvent.set(false);
                this.state = MediaPlayer.State.CLOSED;
                this.worker.shutdownNow();
            }
        }
    }

    public boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
        return this.worker.awaitTermination(timeout, unit);
    }

    private void cancelLocked() {
        if (this.active != null) {
            this.active.cancelled = true;
        }

        if (this.future != null) {
            this.future.cancel(true);
        }

        this.control.notifyAll();
    }

    private boolean valid(MediaPlayer.Task task) {
        return this.active == task && !task.cancelled && !Thread.currentThread().isInterrupted();
    }

    private void decode(MediaPlayer.Task task) {
        try {
            if (!this.valid(task)) {
                return;
            }

            if (!Files.isRegularFile(task.path)) {
                throw new IOException("Nie znaleziono pliku: " + task.path.getFileName());
            }

            if (task.video) {
                this.decodeVideo(task);
            } else {
                this.decodeImage(task);
            }
        } catch (Exception | LinkageError var7) {
            Throwable failure = var7;
            synchronized (this.control) {
                if (this.valid(task)) {
                    String reason = failure.getMessage();
                    this.error = "Nie można odczytać "
                        + task.path.getFileName()
                        + ": "
                        + (reason != null && !reason.isBlank() ? reason : failure.getClass().getSimpleName());
                    this.state = MediaPlayer.State.ERROR;
                }
            }
        }
    }

    private void decodeImage(MediaPlayer.Task task) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(task.path.toFile())) {
            if (input == null) {
                throw new IOException("Nie można otworzyć obrazu");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Nieobsługiwany format obrazu");
            }

            ImageReader reader = readers.next();

            try {
                reader.setInput(input, true, true);
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                if (!this.valid(task)) {
                    return;
                }

                BufferedImage image = reader.read(0);
                int width = image.getWidth();
                int height = image.getHeight();
                byte[] rgba = new byte[width * height * 4];
                int[] row = new int[width];

                for (int y = 0; y < height; y++) {
                    if (!this.valid(task)) {
                        return;
                    }

                    image.getRGB(0, y, width, 1, row, 0, width);

                    for (int x = 0; x < width; x++) {
                        int argb = row[x];
                        int offset = (y * width + x) * 4;
                        rgba[offset] = (byte)(argb >>> 16);
                        rgba[offset + 1] = (byte)(argb >>> 8);
                        rgba[offset + 2] = (byte)argb;
                        rgba[offset + 3] = (byte)(argb >>> 24);
                    }
                }

                synchronized (this.control) {
                    if (!this.valid(task)) {
                        return;
                    }

                    this.publish(new MediaFrame(width, height, rgba, 0L));
                    this.state = MediaPlayer.State.READY;
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private void decodeVideo(MediaPlayer.Task task) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(task.path.toFile())) {
            grabber.setPixelFormat(26);
            grabber.setSampleFormat(1);
            grabber.setSampleRate(44100);
            grabber.setAudioChannels(2);
            grabber.setVideoOption("threads", "2");
            grabber.setAudioOption("threads", "1");
            grabber.setOption("protocol_whitelist", "file");
            grabber.setOption("rw_timeout", "3000000");
            grabber.start();
            if (!this.valid(task)) {
                return;
            }

            if (!grabber.hasVideo()) {
                throw new IOException("Plik nie zawiera ścieżki obrazu wideo");
            }

            checkDimensions(grabber.getImageWidth(), grabber.getImageHeight());
            long mediaDuration = Math.max(0L, grabber.getLengthInTime());
            double rate = grabber.getFrameRate();
            long frameDuration = rate > 0.0 && Double.isFinite(rate) ? Math.max(1L, Math.round(1000000.0 / rate)) : 40000L;
            List<MediaPlayer.AudioPacket> preRoll = new ArrayList<>();
            int preRollBytes = 0;

            Frame first;
            while (this.valid(task) && (first = grabber.grab()) != null) {
                if (first.image != null) {
                    long originUs = first.timestamp;
                    MediaFrame preview = copyVideo(first, 0L);
                    synchronized (this.control) {
                        if (!this.valid(task)) {
                            return;
                        }

                        this.durationUs = mediaDuration;
                        this.hasAudio = grabber.hasAudio();
                        this.publish(preview);
                        this.state = MediaPlayer.State.READY;
                    }

                    if (!this.awaitPlay(task)) {
                        return;
                    }

                    this.playVideo(task, grabber, originUs, frameDuration, preRoll);
                    return;
                }

                if (first.samples != null && preRollBytes < 88200) {
                    byte[] pcm = copyAudio(first);
                    preRollBytes += pcm.length;
                    if (preRollBytes <= 88200) {
                        preRoll.add(new MediaPlayer.AudioPacket(first.timestamp, pcm));
                    }
                }
            }

            if (this.valid(task)) {
                throw new IOException("Nie znaleziono poprawnej klatki wideo");
            }
        }
    }

    private boolean awaitPlay(MediaPlayer.Task task) throws InterruptedException {
        synchronized (this.control) {
            while (this.valid(task) && !task.playRequested) {
                this.control.wait(100L);
            }

            if (!this.valid(task)) {
                return false;
            }

            this.state = MediaPlayer.State.PLAYING;
            return true;
        }
    }

    private void playVideo(MediaPlayer.Task task, FFmpegFrameGrabber grabber, long originUs, long frameDuration, List<MediaPlayer.AudioPacket> preRoll) throws Exception {
        try (MediaPlayer.AudioOutput audio = new MediaPlayer.AudioOutput(task, this.hasAudio)) {
            long started = System.nanoTime();
            long lastVideoUs = 0L;

            for (MediaPlayer.AudioPacket packet : preRoll) {
                if (!this.awaitTimestamp(task, started, Math.max(0L, packet.timeUs - originUs))) {
                    return;
                }

                audio.write(packet.pcm);
            }

            preRoll.clear();

            Frame frame;
            while (this.valid(task) && (frame = grabber.grab()) != null) {
                if (frame.image != null) {
                    long pts = Math.max(0L, frame.timestamp - originUs);
                    if (pts <= lastVideoUs) {
                        pts = lastVideoUs + frameDuration;
                    }

                    lastVideoUs = pts;
                    if (!this.awaitTimestamp(task, started, pts)) {
                        return;
                    }

                    MediaFrame decoded = copyVideo(frame, pts);
                    synchronized (this.control) {
                        if (!this.valid(task)) {
                            return;
                        }

                        this.publish(decoded);
                    }
                }

                if (frame.samples != null && audio.enabled()) {
                    if (!this.awaitTimestamp(task, started, Math.max(0L, frame.timestamp - originUs))) {
                        return;
                    }

                    audio.write(copyAudio(frame));
                }
            }

            long endUs = Math.max(lastVideoUs + frameDuration, this.durationUs);
            if (!this.awaitTimestamp(task, started, endUs)) {
                return;
            }

            synchronized (this.control) {
                if (!this.valid(task)) {
                    return;
                }

                this.state = MediaPlayer.State.ENDED;
                this.endedEvent.set(true);
            }
        }
    }

    private boolean awaitTimestamp(MediaPlayer.Task task, long started, long ptsUs) {
        while (this.valid(task)) {
            long remaining = ptsUs * 1000L - (System.nanoTime() - started);
            if (remaining <= 0L) {
                return true;
            }

            LockSupport.parkNanos(Math.min(remaining, 20000000L));
        }

        return false;
    }

    private static MediaFrame copyVideo(Frame frame, long timeUs) throws IOException {
        checkDimensions(frame.imageWidth, frame.imageHeight);
        if (frame.imageDepth == 8 && frame.imageChannels == 4 && frame.image[0] instanceof ByteBuffer) {
            ByteBuffer pixels = ((ByteBuffer)frame.image[0]).duplicate();
            int width = frame.imageWidth;
            int height = frame.imageHeight;
            byte[] rgba = new byte[width * height * 4];
            if (frame.imageStride >= width * 4 && (long)frame.imageStride * (height - 1) + width * 4 <= pixels.limit()) {
                for (int y = 0; y < height; y++) {
                    pixels.position(y * frame.imageStride);
                    pixels.get(rgba, y * width * 4, width * 4);
                }

                return new MediaFrame(width, height, rgba, timeUs);
            } else {
                throw new IOException("Nieprawidłowy rozmiar klatki wideo");
            }
        } else {
            throw new IOException("Dekoder nie zwrócił oczekiwanego obrazu RGBA8");
        }
    }

    private static byte[] copyAudio(Frame frame) throws IOException {
        if (frame.samples.length == 1 && frame.samples[0] instanceof ShortBuffer) {
            ShortBuffer samples = ((ShortBuffer)frame.samples[0]).duplicate();
            if (samples.remaining() > 1048576) {
                throw new IOException("Nieprawidłowy rozmiar ramki dźwiękowej");
            }

            byte[] pcm = new byte[samples.remaining() * 2];

            for (int i = 0; i < pcm.length; i += 2) {
                short sample = samples.get();
                pcm[i] = (byte)sample;
                pcm[i + 1] = (byte)(sample >>> 8);
            }

            return pcm;
        } else {
            throw new IOException("Dekoder nie zwrócił dźwięku PCM S16");
        }
    }

    private void publish(MediaFrame frame) {
        this.current = frame;
        this.pending.set(frame);
    }

    private static void checkDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || (long)width * height > 16777216L) {
            throw new IOException("Nieobsługiwany rozmiar obrazu: " + width + "×" + height);
        }
    }

    private static boolean isImagePath(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg")
            || name.endsWith(".jpeg")
            || name.endsWith(".png")
            || name.endsWith(".bmp")
            || name.endsWith(".gif")
            || name.endsWith(".wbmp");
    }

    private final class AudioOutput implements AutoCloseable {
        private final MediaPlayer.Task task;
        private SourceDataLine line;

        private AudioOutput(MediaPlayer.Task task, boolean requested) {
            this.task = task;
            if (requested && MediaPlayer.this.valid(task)) {
                try {
                    AudioFormat format = new AudioFormat(44100.0F, 16, 2, true, false);
                    this.line = (SourceDataLine)AudioSystem.getLine(new Info(SourceDataLine.class, format));
                    this.line.open(format, 17640);
                    this.line.start();
                } catch (Exception unavailable) {
                    if (this.line != null) {
                        this.line.close();
                    }

                    this.line = null;
                    synchronized (MediaPlayer.this.control) {
                        if (MediaPlayer.this.valid(task)) {
                            MediaPlayer.this.warning = "Brak dostępnego wyjścia dźwięku — odtwarzany jest obraz filmu.";
                        }
                    }
                }
            }
        }

        private boolean enabled() {
            return this.line != null;
        }

        private void write(byte[] pcm) {
            if (this.line != null) {
                int offset = 0;

                while (offset < pcm.length && MediaPlayer.this.valid(this.task)) {
                    int writable = Math.min(this.line.available(), pcm.length - offset);
                    writable -= writable % 4;
                    if (writable > 0) {
                        offset += this.line.write(pcm, offset, writable);
                    } else {
                        LockSupport.parkNanos(5000000L);
                    }
                }
            }
        }

        @Override
        public void close() {
            if (this.line != null) {
                this.line.stop();
                this.line.flush();
                this.line.close();
            }
        }
    }

    private record AudioPacket(long timeUs, byte[] pcm) {
    }

    public enum State {
        EMPTY,
        LOADING,
        READY,
        PLAYING,
        ENDED,
        ERROR,
        CLOSED;
    }

    private static final class Task {
        private final Path path;
        private final boolean video;
        private volatile boolean cancelled;
        private volatile boolean playRequested;

        private Task(Path path, boolean video) {
            this.path = path;
            this.video = video;
        }
    }
}
