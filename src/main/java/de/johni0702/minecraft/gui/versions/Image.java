package de.johni0702.minecraft.gui.versions;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * As of LWJGL 3, AWT must never be initialized, otherwise GLFW will be broken on OSX.
 * **Any** usage of BufferedImage will initialize AWT (static initializer).
 * E.g. https://www.replaymod.com/forum/thread/2566
 */
public class Image implements AutoCloseable {
    // Lazy default: class initialization must not touch disk, Minecraft or mod state.
    private static volatile TempFileFactory tempFileFactory = () -> TemporaryFile.create(null);

    /** Creates one owned task; failed creation must clean its own partial allocation. */
    @FunctionalInterface
    public interface TempFileFactory {
        TemporaryFile create() throws IOException;
    }

    /** Replaces only the factory reference; does not invoke it or perform IO. */
    public static void setTempFileFactory(TempFileFactory factory) {
        tempFileFactory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Pure-JDK ownership handle for one private task directory and its fixed image.png.
     * Root/parents/foreign children are never owned. Boundary checks must reject task
     * redirection and non-regular payloads; no recursive deletion or link traversal.
     * Trusted parents are required: path checks cannot eliminate replacement races.
     */
    public static final class TemporaryFile implements AutoCloseable {
        private final Path canonicalRoot;
        private final Path taskDirectory;
        private final Path payload;
        private boolean closed;

        private TemporaryFile(Path canonicalRoot, Path taskDirectory, Path payload) {
            this.canonicalRoot = canonicalRoot;
            this.taskDirectory = taskDirectory;
            this.payload = payload;
        }

        /**
         * Null selects JDK default temp; otherwise root must already be absolute.
         * Allocate an exclusive task and CREATE_NEW image.png, fixing real paths.
         * Creation failures retain the primary error and suppress cleanup failures.
         */
        public static TemporaryFile create(Path root) throws IOException {
            Path canonical = null;
            Path task = null;
            Path file = null;
            boolean payloadCreated = false;
            String phase = "prepare root";
            try {
                if (root != null) {
                    if (!root.isAbsolute()) {
                        throw new IOException("Explicit temporary root must be absolute: " + root);
                    }
                    Files.createDirectories(root);
                    canonical = root.toRealPath();
                    phase = "allocate task";
                    task = Files.createTempDirectory(canonical, ".jgui-image-");
                } else {
                    phase = "allocate default task";
                    task = Files.createTempDirectory(".jgui-image-");
                    task = task.toAbsolutePath();
                    phase = "resolve default root";
                    canonical = task.getParent().toRealPath();
                    // Fix the resolved parent, not an alias that could later change.
                    Path originalTask = task;
                    task = canonical.resolve(task.getFileName());
                    if (!originalTask.toRealPath().equals(task)) {
                        throw new IOException("Default temporary task was redirected: " + originalTask);
                    }
                }
                file = task.resolve("image.png");
                TemporaryFile result = new TemporaryFile(canonical, task, file);
                phase = "validate task";
                result.checkDirectories(false);
                phase = "create payload";
                Files.createFile(file);
                payloadCreated = true;
                phase = "validate payload";
                result.checkedPath();
                return result;
            } catch (IOException | UnsupportedOperationException | SecurityException failure) {
                IOException primary = diagnostic("Temporary image creation failed; payloadCreated=" + payloadCreated, phase,
                        root, canonical, task, file, failure);
                cleanupAllocation(primary, canonical, task, file, payloadCreated);
                throw primary;
            } catch (RuntimeException | Error failure) {
                cleanupAllocation(failure, canonical, task, file, payloadCreated);
                throw failure;
            }
        }

        private static void cleanupAllocation(Throwable primary, Path root, Path task,
                                              Path file, boolean payloadCreated) {
            if (task == null) {
                return;
            }
            if (root == null) {
                primary.addSuppressed(diagnostic("Temporary image allocation may remain; boundary unknown",
                        "creation cleanup", null, root, task, file, null));
                return;
            }
            try {
                Path ownedPayload = file == null ? task.resolve("image.png") : file;
                new TemporaryFile(root, task, ownedPayload).cleanup(payloadCreated);
            } catch (IOException | RuntimeException | Error failure) {
                primary.addSuppressed(failure);
            }
        }

        private static IOException diagnostic(String message, String phase, Path requestedRoot,
                                              Path root, Path task, Path file, Throwable cause) {
            return new IOException(message + "; phase=" + phase + "; requestedRoot=" + requestedRoot
                    + "; resolved root=" + root + "; task=" + (task == null ? "<not created>" : task)
                    + "; payload=" + (file == null ? "<not created>" : file)
                    + "; paths may remain", cause);
        }

        /** Checks parents before any payload access. These checks are not atomic with IO. */
        private boolean checkDirectories(boolean allowMissing) throws IOException {
            if (!canonicalRoot.isAbsolute() || !canonicalRoot.equals(taskDirectory.getParent())
                    || !taskDirectory.resolve("image.png").equals(payload)) {
                throw new IOException("Invalid temporary image path boundary");
            }
            try {
                checkDirectory(canonicalRoot);
                checkDirectory(taskDirectory);
                return true;
            } catch (NoSuchFileException missing) {
                if (allowMissing) {
                    return false;
                }
                throw missing;
            }
        }

        private static void checkDirectory(Path directory) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(directory,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Temporary image directory has unsafe type: " + directory);
            }
            if (!directory.toRealPath().equals(directory)) {
                throw new IOException("Temporary image directory was redirected: " + directory);
            }
        }

        private void checkPayload() throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(payload,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                    || !payload.toRealPath().equals(payload)) {
                throw new IOException("Temporary image payload has unsafe type or was redirected: " + payload);
            }
        }

        private synchronized Path checkedPath() throws IOException {
            Path path = path();
            try {
                checkDirectories(false);
                checkPayload();
                return path;
            } catch (IOException | UnsupportedOperationException | SecurityException failure) {
                throw diagnostic("Temporary image boundary check failed", "before payload IO",
                        null, canonicalRoot, taskDirectory, payload, failure);
            }
        }

        /** Returns only this task's fixed payload; closed handles cannot be reused. */
        public synchronized Path path() {
            if (closed) {
                throw new IllegalStateException("Temporary image task is closed");
            }
            return payload;
        }

        /**
         * Attempt cleanup once, marking terminal before IO (even on failure).
         * Delete only the validated payload then empty task, never root/foreign items.
         * Repeat closes are no-ops; failures identify root/task/payload and phase.
         */
        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            cleanup(true);
        }

        private void cleanup(boolean ownsPayload) throws IOException {
            String phase = "validate cleanup boundary";
            try {
                if (!checkDirectories(true)) {
                    return;
                }
                if (ownsPayload) {
                    phase = "validate payload for cleanup";
                    try {
                        checkPayload();
                        phase = "delete payload";
                        Files.delete(payload);
                    } catch (NoSuchFileException missing) {
                        // Only an explicit absence is harmless; permissions/unknown types are not.
                    }
                }
                phase = "validate task before deletion";
                if (!checkDirectories(true)) {
                    return;
                }
                phase = "delete empty task";
                try {
                    Files.delete(taskDirectory);
                } catch (NoSuchFileException missing) {
                    // Never reconstruct or retry a task which has disappeared.
                }
            } catch (IOException | UnsupportedOperationException | SecurityException failure) {
                throw diagnostic("temporary image cleanup failed; output may already have been written",
                        phase, null, canonicalRoot, taskDirectory, payload, failure);
            }
        }
    }

    private NativeImage inner;

    public Image(int width, int height) {
        this(
                //#if FABRIC>=1
                new NativeImage(NativeImage.Format.RGBA, width, height, true)
                //#else
                //$$ new NativeImage(NativeImage.PixelFormat.RGBA, width, height, true)
                //#endif
        );
    }

    public Image(NativeImage inner) {
        this.inner = inner;
    }

    public NativeImage getInner() {
        return inner;
    }

    @Override
    protected void finalize() throws Throwable {
        // Great, now we're using a language with GC but still need to take care of memory management.. thanks MC
        close();
        super.finalize();
    }

    @Override
    public void close() {
        if (inner != null) {
            inner.close();
            inner = null;
        }
    }

    public int getWidth() {
        return inner.getWidth();
    }

    public int getHeight() {
        return inner.getHeight();
    }

    public void setRGBA(int x, int y, int r, int g, int b, int a) {
        // actually takes ABGR, not RGBA
        inner.setColor(x, y, ((a & 0xff) << 24) | ((b & 0xff) << 16) | ((g & 0xff) << 8) | (r & 0xff));
    }

    public static Image read(Path path) throws IOException {
        return read(Files.newInputStream(path));
    }

    public static Image read(InputStream in) throws IOException {
        // Minecraft as of 1.20.4 artificially limits itself to images with valid PNG header.
        // Our replay thumbnails are JPG though, so we need to convert those.
        //#if MC>=12004
        byte[] bytes;
        try (InputStream in_ = in) {
            bytes = in.readAllBytes();
        }
        in = new ByteArrayInputStream(bytes);
        if (bytes[0] != (byte) 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4E || bytes[3] != 0x47) {
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(bytes));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "png", out);
            in = new ByteArrayInputStream(out.toByteArray());
        }
        //#endif

        return new Image(NativeImage.read(in));
    }

    public void writePNG(File file) throws IOException {
        inner.writeTo(file);
    }

    public void writePNG(OutputStream outputStream) throws IOException {
        TempFileFactory factory = tempFileFactory;
        try (TemporaryFile temporary = Objects.requireNonNull(factory.create(), "factory returned null")) {
            inner.writeTo(temporary.checkedPath());
            Files.copy(temporary.checkedPath(), outputStream);
        }
    }

    public Image scaledSubRect(int x, int y, int width, int height, int scaledWidth, int scaledHeight) {
        NativeImage dst = new NativeImage(inner.getFormat(), scaledWidth, scaledHeight, false);
        inner.resizeSubRectTo(x, y, width, height, dst);
        return new Image(dst);
    }

    @Deprecated // BufferedImage should not be used on 1.13+, see class docs
    public BufferedImage toBufferedImage() {
        // Not very efficient but certainly the easiest solution.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writePNG(out);
            return ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public NativeImageBackedTexture toTexture() {
        return new NativeImageBackedTexture(inner);
    }
}
