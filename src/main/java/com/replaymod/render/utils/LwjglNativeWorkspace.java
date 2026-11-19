package com.replaymod.render.utils;

import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Pure-JDK preparation only. A loaded native belongs to the process, not a render or loader close. */
public final class LwjglNativeWorkspace {
    private static final String NAMESPACE = ".replaymod-native-v1";
    private static final int LIMIT = 4 * 1024 * 1024;
    // Keep locks strongly reachable even after a failed child initialization or URLClassLoader.close().
    private static final List<LwjglNativeWorkspace> LIVE = new ArrayList<>();
    private static final String LIBRARY = "org/lwjgl/system/Library.class";
    private static final String CONFIGURATION = "org/lwjgl/system/Configuration.class";
    private static final String TINYEXR = "org/lwjgl/util/tinyexr/TinyEXR.class";
    // These are the existing MC 3.3.3 core and bundled 3.3.1 binding, not downloaded replacements.
    private static final Map<String, String> CLASSES = Map.of(
            LIBRARY, "c82ca2d57734406a01dd969ad8b161f6f33c88a41045930679c68925544a0f75",
            CONFIGURATION, "007b38113339b9400c0589e812e17b09ce0561d518dedc4c261cb26fae5e09fd",
            TINYEXR, "4889f216d47b7f54c5741e04914a03f703db35b62633c7522441dac797e013b0");
    private static final Map<String, String> TINY_HASHES = Map.of(
            "windows", "648673577a6945857e4c13b54e2dc88b1a8a5399f43aaa0581a3e46c7d665381",
            "linux", "147213b633fce4e7eba0ab64ddd3bedb30209e65228f9c5d7920fdee8f80f1e9",
            "macos", "84cc723b60311a1c2369d609af82e7362c0d625c58a4f1eb83d76d17c39f51b2");

    private final Path directory;
    private final String coreRoot, tinyRoot, coreResource, tinyResource;
    private final Map<String, String> hashes;
    private final FileChannel channel;
    private final FileLock lock;

    private LwjglNativeWorkspace(Path directory, String coreRoot, String tinyRoot, String coreResource,
            String tinyResource, Map<String, String> hashes, FileChannel channel, FileLock lock) {
        this.directory = directory;
        this.coreRoot = coreRoot;
        this.tinyRoot = tinyRoot;
        this.coreResource = coreResource;
        this.tinyResource = tinyResource;
        this.hashes = Map.copyOf(hashes);
        this.channel = channel;
        this.lock = lock;
    }

    /** No native or LWJGL class is initialized here; an explicit bad root is never substituted. */
    public static synchronized LwjglNativeWorkspace open(Path selectedTempRoot, ClassLoader resources) throws IOException {
        Objects.requireNonNull(resources, "resources");
        String platform = platform();
        String coreName = platform.equals("windows") ? "lwjgl.dll" : "liblwjgl." + (platform.equals("macos") ? "dylib" : "so");
        String tinyName = platform.equals("windows") ? "lwjgl_tinyexr.dll" : "liblwjgl_tinyexr." + (platform.equals("macos") ? "dylib" : "so");
        String core = platform + "/x64/org/lwjgl/" + coreName;
        String tiny = platform + "/x64/org/lwjgl/tinyexr/" + tinyName;
        Map<String, URL> classes = new HashMap<>();
        for (Map.Entry<String, String> entry : CLASSES.entrySet()) {
            URL url = required(resources, entry.getKey());
            if (!digest(url, "SHA-256").equals(entry.getValue()))
                throw new IOException("EXR requires verified MC core 3.3.3 / TinyEXR 3.3.1 class: " + url);
            classes.put(entry.getKey(), url);
        }
        String coreRoot = resourceRoot(classes.get(LIBRARY), LIBRARY);
        String tinyRoot = resourceRoot(classes.get(TINYEXR), TINYEXR);
        if (!resourceRoot(classes.get(CONFIGURATION), CONFIGURATION).equals(coreRoot))
            throw new IOException("EXR core Configuration and Library resource origins differ");
        URL coreURL = required(resources, core), tinyURL = required(resources, tiny);
        // Core's expected native SHA1 is read from the verified Java resource origin, not another cache JAR.
        URL coreSHA1 = new URL(classes.get(LIBRARY), coreRoot + "META-INF/" + core + ".sha1");
        String expectedSHA1 = new String(readBounded(coreSHA1, 128), StandardCharsets.US_ASCII).trim();
        if (!expectedSHA1.matches("[0-9a-f]{40}") || !digest(coreURL, "SHA-1").equals(expectedSHA1))
            throw new IOException("EXR core native does not match the visible core 3.3.3 Java resource: " + coreURL);
        if (!digest(tinyURL, "SHA-256").equals(TINY_HASHES.get(platform)))
            throw new IOException("EXR native does not match bundled TinyEXR 3.3.1 " + platform + " x64: " + tinyURL);
        Map<String, String> hashes = Map.of(core, digest(coreURL, "SHA-256"), tiny, TINY_HASHES.get(platform));
        Path root = root(selectedTempRoot, true);
        // Cleanup is best effort and never masks a new EXR initialization failure.
        try { cleanupStale(selectedTempRoot); }
        catch (IOException | RuntimeException warning) { System.err.println("EXR native stale cleanup retained: " + warning); }
        Path namespace = root.resolve(NAMESPACE);
        ensureDirectories(namespace);
        String id = UUID.randomUUID().toString();
        Path task = Files.createDirectory(namespace.resolve("task-" + id));
        FileChannel channel = null;
        FileLock lock = null;
        List<Path> created = new ArrayList<>();
        try {
            Path lockPath = task.resolve("owner.lock");
            channel = FileChannel.open(lockPath, CREATE_NEW, READ, WRITE, NOFOLLOW_LINKS);
            created.add(lockPath);
            lock = channel.tryLock();
            if (lock == null) throw new IOException("EXR native task cannot acquire exclusive lock: " + task);
            channel.write(ByteBuffer.wrap(id.getBytes(StandardCharsets.US_ASCII)));
            channel.force(true);
            ProcessHandle process = ProcessHandle.current();
            Instant start = process.info().startInstant().orElseThrow(() -> new IOException("EXR native owner startInstant unavailable"));
            for (Map.Entry<String, URL> entry : Map.of(core, coreURL, tiny, tinyURL).entrySet()) {
                Path target = task.resolve(entry.getKey());
                ensureDirectories(target.getParent());
                created.add(target);
                try (InputStream in = entry.getValue().openStream(); OutputStream out = Files.newOutputStream(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
                    copyBounded(in, out, LIMIT);
                }
                if (!digest(target).equals(hashes.get(entry.getKey())))
                    throw new IOException("EXR native resource changed while copying: " + entry.getValue());
            }
            Properties owner = new Properties();
            owner.setProperty("version", "1"); owner.setProperty("id", id);
            owner.setProperty("pid", Long.toString(process.pid())); owner.setProperty("start", start.toString());
            owner.setProperty("platform", platform);
            owner.setProperty("core", core); owner.setProperty("tiny", tiny);
            owner.setProperty("coreHash", hashes.get(core)); owner.setProperty("tinyHash", hashes.get(tiny));
            Path record = task.resolve("owner.properties");
            created.add(record);
            // All values are restricted ASCII tokens/relative resource names. Avoid Properties.store's
            // timestamp, which would initialize and mutate the JVM's lazy user.timezone property.
            StringBuilder receipt = new StringBuilder();
            for (String key : owner.stringPropertyNames().stream().sorted().toList())
                receipt.append(key).append('=').append(owner.getProperty(key)).append('\n');
            Files.writeString(record, receipt, StandardCharsets.US_ASCII, CREATE_NEW, WRITE, NOFOLLOW_LINKS);
            LwjglNativeWorkspace workspace = new LwjglNativeWorkspace(task, coreRoot, tinyRoot, core, tiny, hashes, channel, lock);
            LIVE.add(workspace);
            return workspace;
        } catch (IOException | RuntimeException | Error failure) {
            // Not loaded yet. Delete only the exact files created by this attempt; unexpected material is retained.
            for (Path path : created.reversed()) try { if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.delete(path); }
            catch (IOException | RuntimeException warning) { failure.addSuppressed(warning); }
            if (lock != null) try { lock.release(); } catch (IOException warning) { failure.addSuppressed(warning); }
            if (channel != null) try { channel.close(); } catch (IOException warning) { failure.addSuppressed(warning); }
            System.err.println("EXR native preparation failed; empty/uncertain task directories may remain: " + task);
            throw failure;
        }
    }

    public Path directory() { return directory; }

    /** Only the two prepared native resources (or their basename aliases) can be returned. */
    public URL nativeResource(String name) throws IOException {
        String relative = name.equals(coreResource) || name.equals(Path.of(coreResource).getFileName().toString()) ? coreResource
                : name.equals(tinyResource) || name.equals(Path.of(tinyResource).getFileName().toString()) ? tinyResource : null;
        if (relative == null) throw new IOException("EXR native resource was not prepared: " + name);
        checkAncestors(directory);
        Path file = directory.resolve(relative);
        checkAncestors(file.getParent());
        if (!regular(file) || !digest(file).equals(hashes.get(relative)))
            throw new IOException("EXR prepared native is missing/changed: " + file);
        return file.toUri().toURL();
    }

    Path coreLibrary() throws IOException {
        try { return Path.of(nativeResource(coreResource).toURI()); }
        catch (java.net.URISyntaxException impossible) { throw new IOException(impossible); }
    }

    /** Reject mixed Java origins rather than guessing compatibility or adding local Gradle caches. */
    void verifyClassResource(String name, URL resource) throws IOException {
        String root = name.startsWith("org/lwjgl/util/tinyexr/") ? tinyRoot : coreRoot;
        String actual = resourceRoot(resource, name);
        // Parent resource lookup already selected the Java-21 multi-release implementation, if any.
        boolean multiRelease = actual.startsWith(root)
                && actual.substring(root.length()).matches("META-INF/versions/(9|1[0-9]|2[01])/");
        if (!actual.equals(root) && !multiRelease)
            throw new IOException("EXR class resource origin differs from verified core/binding: " + resource);
    }

    /** Ended original process + exclusive lock + exact non-linked tree; unknown entries are never deleted. */
    public static void cleanupStale(Path selectedTempRoot) throws IOException {
        Path namespace = root(selectedTempRoot, false).resolve(NAMESPACE);
        if (!Files.exists(namespace, NOFOLLOW_LINKS)) return;
        checkAncestors(namespace);
        try (DirectoryStream<Path> tasks = Files.newDirectoryStream(namespace, "task-*")) {
            for (Path task : tasks) try { cleanupTask(task); }
            catch (IOException | RuntimeException uncertain) { System.err.println("EXR native task retained: " + task + ": " + uncertain); }
        }
    }

    private static void cleanupTask(Path task) throws IOException {
        checkAncestors(task);
        Path record = task.resolve("owner.properties"), lockPath = task.resolve("owner.lock");
        if (!regular(record) || !regular(lockPath)) return;
        byte[] receipt = readFile(record, 8192);
        Properties owner = new Properties(); owner.load(new ByteArrayInputStream(receipt));
        if (!owner.stringPropertyNames().equals(Set.of("version", "id", "pid", "start", "platform", "core", "tiny", "coreHash", "tinyHash"))
                || !"1".equals(owner.getProperty("version"))) return;
        String id = owner.getProperty("id");
        if (!UUID.fromString(id).toString().equals(id) || !task.getFileName().toString().equals("task-" + id)) return;
        long pid = Long.parseLong(owner.getProperty("pid"));
        if (pid <= 0) return;
        Instant start = Instant.parse(owner.getProperty("start"));
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isPresent()) {
            Optional<Instant> actual = process.get().info().startInstant();
            // Same original process OR unknown identity: retain. A different known start proves PID reuse.
            if (actual.isEmpty() || actual.get().equals(start)) return;
        }
        String platform = owner.getProperty("platform");
        if (!TINY_HASHES.containsKey(platform)) return;
        String core = platform + "/x64/org/lwjgl/" + (platform.equals("windows") ? "lwjgl.dll" : "liblwjgl." + (platform.equals("macos") ? "dylib" : "so"));
        String tiny = platform + "/x64/org/lwjgl/tinyexr/" + (platform.equals("windows") ? "lwjgl_tinyexr.dll" : "liblwjgl_tinyexr." + (platform.equals("macos") ? "dylib" : "so"));
        if (!core.equals(owner.getProperty("core")) || !tiny.equals(owner.getProperty("tiny"))
                || !owner.getProperty("coreHash").matches("[0-9a-f]{64}")
                || !TINY_HASHES.get(platform).equals(owner.getProperty("tinyHash"))) return;
        if (!Arrays.equals(readFile(lockPath, 64), id.getBytes(StandardCharsets.US_ASCII))) return;
        try (FileChannel channel = FileChannel.open(lockPath, READ, WRITE, NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (java.nio.channels.OverlappingFileLockException active) { return; }
            if (lock == null) return;
            try (lock) {
                Set<Path> files = Set.of(record, lockPath, task.resolve(core), task.resolve(tiny));
                Set<Path> directories = new HashSet<>(); directories.add(task);
                for (Path file : files) for (Path p = file.getParent(); !p.equals(task); p = p.getParent()) directories.add(p);
                // Enumerate ONLY expected directories, checking each before opening it. Never descend
                // through an unknown directory/junction just to discover that it was not in the receipt.
                for (Path dir : directories) {
                    checkAncestors(dir);
                    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
                        for (Path path : children) {
                            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                            if (attrs.isSymbolicLink() || attrs.isOther() || (attrs.isDirectory() ? !directories.contains(path) : !attrs.isRegularFile() || !files.contains(path)))
                                return;
                        }
                    }
                }
                if (!digest(task.resolve(core)).equals(owner.getProperty("coreHash"))
                        || !digest(task.resolve(tiny)).equals(owner.getProperty("tinyHash"))
                        || !Arrays.equals(receipt, readFile(record, 8192))
                        || !lockMatches(channel, id)) return;
                Files.delete(task.resolve(core)); Files.delete(task.resolve(tiny));
                for (Path dir : directories.stream().filter(p -> !p.equals(task)).sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) Files.delete(dir);
            }
        }
        // Windows requires the lock handle closed before deleting it. Exclusive trusted task use is assumed;
        // this is not a hostile same-user TOCTOU/transplanted-owner defense or a power-loss durability promise.
        if (!Arrays.equals(receipt, readFile(record, 8192)) || !Arrays.equals(readFile(lockPath, 64), id.getBytes(StandardCharsets.US_ASCII))) return;
        Files.delete(record); Files.delete(lockPath); Files.delete(task); // Root and namespace always remain.
    }

    private static boolean lockMatches(FileChannel channel, String id) throws IOException {
        byte[] expected = id.getBytes(StandardCharsets.US_ASCII);
        if (channel.size() != expected.length) return false;
        ByteBuffer token = ByteBuffer.allocate(expected.length);
        channel.position(0);
        while (token.hasRemaining()) if (channel.read(token) < 0) return false;
        // Read through the already locked handle. A second handle is denied by Windows byte-range locks.
        return Arrays.equals(token.array(), expected);
    }

    private static String platform() throws IOException {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64") && !arch.equals("x64"))
            throw new IOException("EXR bundled natives support x64 only, not " + arch);
        String os = System.getProperty("os.name", "");
        if (os.startsWith("Windows")) return "windows";
        if (os.startsWith("Linux")) return "linux";
        if (os.startsWith("Mac OS X") || os.startsWith("Darwin")) return "macos";
        throw new IOException("EXR native platform not verified: " + os);
    }

    private static Path root(Path selected, boolean create) throws IOException {
        String jdk = System.getProperty("java.io.tmpdir");
        if (selected == null && (jdk == null || jdk.isBlank())) throw new IOException("JDK temporary directory unavailable");
        Path root = (selected == null ? Path.of(jdk) : selected).toAbsolutePath().normalize();
        if (create) ensureDirectories(root);
        else if (Files.exists(root, NOFOLLOW_LINKS)) checkAncestors(root);
        else { Path parent = root.getParent(); while (parent != null && !Files.exists(parent, NOFOLLOW_LINKS)) parent = parent.getParent(); if (parent != null) checkAncestors(parent); }
        return root;
    }

    private static void ensureDirectories(Path path) throws IOException {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Path parent = path.getParent(); if (parent != null) ensureDirectories(parent);
            try { Files.createDirectory(path); } catch (FileAlreadyExistsException concurrent) { /* checked below */ }
        }
        checkAncestors(path);
    }

    private static void checkAncestors(Path path) throws IOException {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther() || !p.toRealPath().equals(p))
                throw new IOException("EXR native directory is linked/unsafe/not a directory: " + p);
        }
    }

    private static boolean regular(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        return attrs.isRegularFile() && !attrs.isSymbolicLink() && !attrs.isOther();
    }

    private static URL required(ClassLoader resources, String name) throws IOException {
        URL url = resources.getResource(name);
        if (url == null) throw new IOException("EXR classpath resource missing: " + name + "; parent=" + resources);
        return url;
    }

    private static String resourceRoot(URL url, String resource) throws IOException {
        String value = url.toExternalForm();
        if (!value.endsWith(resource)) throw new IOException("EXR resource origin cannot be verified: " + url);
        return value.substring(0, value.length() - resource.length());
    }

    private static MessageDigest hash(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String digest(URL resource, String algorithm) throws IOException {
        try (InputStream in = resource.openStream()) { return digest(in, algorithm); }
    }

    private static String digest(Path path) throws IOException {
        if (!regular(path)) throw new IOException("EXR native payload is not a regular file: " + path);
        try (InputStream in = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) { return digest(in, "SHA-256"); }
    }

    private static String digest(InputStream in, String algorithm) throws IOException {
        MessageDigest hash = hash(algorithm); byte[] buffer = new byte[8192]; int total = 0, count;
        while ((count = in.read(buffer)) != -1) {
            if ((total += count) > LIMIT) throw new IOException("EXR resource exceeds " + LIMIT + " bytes");
            hash.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static byte[] readBounded(URL resource, int limit) throws IOException {
        try (InputStream in = resource.openStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) { copyBounded(in, out, limit); return out.toByteArray(); }
    }

    private static byte[] readFile(Path path, int limit) throws IOException {
        if (!regular(path)) throw new IOException("EXR owner is not a regular file: " + path);
        try (InputStream in = Files.newInputStream(path, READ, NOFOLLOW_LINKS); ByteArrayOutputStream out = new ByteArrayOutputStream()) { copyBounded(in, out, limit); return out.toByteArray(); }
    }

    private static void copyBounded(InputStream in, OutputStream out, int limit) throws IOException {
        byte[] buffer = new byte[8192]; int total = 0, count;
        while ((count = in.read(buffer)) != -1) {
            if ((total += count) > limit) throw new IOException("EXR resource/owner exceeds " + limit + " bytes");
            out.write(buffer, 0, count);
        }
    }
}
