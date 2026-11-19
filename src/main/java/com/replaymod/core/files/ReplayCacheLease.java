package com.replaymod.core.files;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/** Process-private payloads; the class monitor is also the open/cleanup admission gate. */
public final class ReplayCacheLease implements Closeable {
    private static final String PROCESS = UUID.randomUUID().toString();
    private static final long PID = ProcessHandle.current().pid();
    private static final String START = ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unknown");
    private static final Map<Path, Shared> ACTIVE = new HashMap<>();
    private final Shared shared;
    private boolean closed;

    private static final class Shared {
        final Path directory, payload;
        final FileChannel channel;
        final FileLock lock;
        final Properties owner;
        int references = 1;
        boolean persistent, quarantined;
        ReplayCacheLease mutator;
        Shared(Path directory, FileChannel channel, FileLock lock, Properties owner) {
            this.directory = directory; this.payload = directory.resolve("payload");
            this.channel = channel; this.lock = lock; this.owner = owner;
            this.persistent = Boolean.parseBoolean(owner.getProperty("persistent"));
        }
    }

    private ReplayCacheLease(Shared shared) { this.shared = shared; }

    /** Existing aliases resolve to their real path; missing names use the real existing parent. */
    static Path logicalIdentity(Path replay) throws IOException {
        Path path = replay.toAbsolutePath().normalize(), ancestor = path;
        while (!Files.exists(ancestor)) {
            if (Files.isSymbolicLink(ancestor)) throw new IOException("Broken replay alias: " + ancestor);
            ancestor = ancestor.getParent();
            if (ancestor == null) throw new IOException("No replay identity ancestor: " + replay);
        }
        return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize();
    }

    static String identityKey(Path replay) throws IOException {
        String value = logicalIdentity(replay).toString();
        return File.separatorChar == '\\' ? value.toLowerCase(Locale.ROOT) : value;
    }

    static Path identityDirectory(ReplayStorageLayout layout, Path replay) throws IOException {
        return ReplayStorageLayout.canonical(layout.cacheRoot().resolve("replay-v2")
                .resolve(ReplayWorkspace.digest(identityKey(replay))));
    }

    public static synchronized ReplayCacheLease acquire(ReplayStorageLayout layout, Path replay) throws IOException {
        Path identity = logicalIdentity(replay);
        Path parent = identityDirectory(layout, identity);
        Path directory = parent.resolve(PROCESS);
        Shared existing = ACTIVE.get(directory);
        if (existing != null) {
            if (!existing.lock.isValid() || !existing.channel.isOpen() || existing.quarantined || existing.mutator != null) {
                throw new IOException("Cache release/mutation incomplete; retry after current writer: " + directory);
            }
            // A false user's policy wins for the entire shared generation (also after that user closes).
            if (!layout.ephemeralCache() && !existing.persistent) {
                existing.owner.setProperty("persistent", "true");
                writeOwner(directory, existing.owner);
                existing.persistent = true;
            }
            existing.references++;
            return new ReplayCacheLease(existing);
        }
        Files.createDirectories(parent);
        ReplayStorageLayout.canonical(parent);
        boolean fresh = !Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        if (fresh) Files.createDirectory(directory);
        else requireDirectory(directory);
        FileChannel channel = null;
        try {
            Properties owner;
            if (fresh) {
                owner = new Properties();
                owner.setProperty("format", "replay-cache-v2"); owner.setProperty("version", "2");
                owner.setProperty("process", PROCESS); owner.setProperty("pid", Long.toString(PID));
                owner.setProperty("start", START); owner.setProperty("logical", identity.toString());
                owner.setProperty("identity", identityKey(identity));
                owner.setProperty("persistent", Boolean.toString(!layout.ephemeralCache()));
                owner.setProperty("lastUsed", Long.toString(System.currentTimeMillis()));
                writeOwner(directory, owner);
                Files.createFile(directory.resolve("lease.lock"));
                Files.createDirectory(directory.resolve("payload"));
            } else {
                owner = readOwner(directory);
                if (!PROCESS.equals(owner.getProperty("process")) || !START.equals(owner.getProperty("start"))
                        || !Long.toString(PID).equals(owner.getProperty("pid"))) throw new IOException("Foreign cache owner: " + directory);
                // No ACTIVE user remains: an idle reopen starts a new policy generation.
                // Persistence is sticky only while users of that shared generation are alive.
                owner.setProperty("persistent", Boolean.toString(!layout.ephemeralCache()));
            }
            requireRegular(directory.resolve("lease.lock")); requireDirectory(directory.resolve("payload"));
            channel = FileChannel.open(directory.resolve("lease.lock"), StandardOpenOption.WRITE);
            FileLock lock = tryLock(channel);
            if (lock == null) throw new IOException("Cache namespace occupied: " + directory);
            owner.setProperty("lastUsed", Long.toString(System.currentTimeMillis()));
            writeOwner(directory, owner);
            Shared shared = new Shared(directory, channel, lock, owner);
            ACTIVE.put(directory, shared);
            return new ReplayCacheLease(shared);
        } catch (IOException | RuntimeException | Error failure) {
            if (channel != null) try { channel.close(); } catch (Throwable close) { failure.addSuppressed(close); }
            // Incomplete construction is evidence, not deletion authority.
            throw failure;
        }
    }

    public Path path() { return shared.payload; }

    // Real ZipReplayFile constructor / packet close may recursively invalidate its cache.
    // They must never run that operation against another user's actual live streams.
    void beginMutation() throws IOException {
        synchronized (ReplayCacheLease.class) {
            if (closed || shared.quarantined || shared.references != 1 || shared.mutator != null) {
                throw new IOException("Replay cache mutation requires its sole actual user: " + shared.directory);
            }
            shared.mutator = this;
        }
    }
    void endMutation() {
        synchronized (ReplayCacheLease.class) { if (shared.mutator == this && !shared.quarantined) shared.mutator = null; }
    }
    boolean isQuarantined() {
        synchronized (ReplayCacheLease.class) { return shared.quarantined; }
    }
    void quarantine(Throwable failure) {
        synchronized (ReplayCacheLease.class) {
            shared.quarantined = true;
            System.err.println("ReplayStudio construction release unproven; cache locked until process exits: " + shared.directory + ": " + failure);
        }
    }

    @Override public void close() throws IOException {
        synchronized (ReplayCacheLease.class) {
            if (closed) return;
            if (shared.quarantined || shared.mutator == this) throw new IOException("Actual cache user not released: " + shared.directory);
            if (shared.references > 1) { shared.references--; closed = true; return; }
            // Do not count a failed actual close as release. A retry may finish a partially released lock.
            if (shared.lock.isValid()) shared.lock.release();
            shared.channel.close();
            shared.references = 0; closed = true; ACTIVE.remove(shared.directory, shared);
            try {
                Properties owner = readOwner(shared.directory);
                if (!PROCESS.equals(owner.getProperty("process")) || !START.equals(owner.getProperty("start"))) {
                    throw new IOException("Cache owner changed before release");
                }
                owner.setProperty("lastUsed", Long.toString(System.currentTimeMillis()));
                owner.setProperty("persistent", Boolean.toString(shared.persistent));
                writeOwner(shared.directory, owner);
                if (!shared.persistent) deleteNamespace(shared.directory);
            } catch (IOException | RuntimeException cleanup) {
                System.err.println("Replay cache released; cleanup retained " + shared.directory + ": " + cleanup);
            }
        }
    }

    /** Only recognized, inactive v2 material. Unknown/legacy/native directories are never interpreted. */
    public static synchronized void cleanupStale(ReplayStorageLayout layout) throws IOException {
        Path root = ReplayStorageLayout.canonical(layout.cacheRoot().resolve("replay-v2"));
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        requireDirectory(root);
        try (DirectoryStream<Path> identities = Files.newDirectoryStream(root)) {
            for (Path identity : identities) {
                if (!identity.getFileName().toString().matches("[0-9a-f]{64}")) continue;
                try {
                    requireDirectory(identity);
                    try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(identity)) {
                        for (Path namespace : namespaces) {
                            try { cleanupNamespace(namespace); }
                            catch (IOException | RuntimeException uncertain) {
                                System.err.println("Cache retained/skipped: " + namespace + ": " + uncertain);
                            }
                        }
                    }
                } catch (IOException | RuntimeException uncertain) {
                    System.err.println("Cache identity retained/skipped: " + identity + ": " + uncertain);
                }
            }
        }
    }

    private static void cleanupNamespace(Path directory) throws IOException {
        if (ACTIVE.containsKey(directory)) return;
        Properties owner = readOwner(directory);
        String start = owner.getProperty("start");
        long pid = Long.parseLong(owner.getProperty("pid"));
        if (PROCESS.equals(owner.getProperty("process"))) {
            if (pid != PID || !START.equals(start)) return;
        } else {
            Optional<ProcessHandle> process = ProcessHandle.of(pid);
            if (process.isPresent() && process.get().isAlive()) {
                Optional<Instant> actual = process.get().info().startInstant();
                if (actual.isEmpty() || start.equals("unknown") || actual.get().toString().equals(start)) return;
            }
        }
        long lastUsed = Long.parseLong(owner.getProperty("lastUsed"));
        long now = System.currentTimeMillis();
        if (lastUsed < 0 || lastUsed > now) return;
        Path logical = Path.of(owner.getProperty("logical"));
        // NOFOLLOW: broken aliases and inaccessible/unknown files are not proof of absence.
        boolean absent = Files.notExists(logical, LinkOption.NOFOLLOW_LINKS);
        if (!absent && now - lastUsed < 7L * 24 * 60 * 60 * 1000) return;
        requireRegular(directory.resolve("lease.lock"));
        try (FileChannel channel = FileChannel.open(directory.resolve("lease.lock"), StandardOpenOption.WRITE)) {
            FileLock lock = tryLock(channel);
            if (lock == null) return;
            try (lock) {
                readOwner(directory); // repeat ownership and tree preflight under the exclusive lock
                preflight(directory);
                deleteTree(directory.resolve("payload"));
                Files.delete(directory.resolve("owner"));
            }
        }
        // Other processes never acquire this process UUID; same-process opens hold the class gate.
        Files.delete(directory.resolve("lease.lock")); Files.delete(directory);
    }

    /** Reverse lookup only for a recognized production namespace, not URL-decoded arbitrary paths. */
    static Path replayForCache(ReplayStorageLayout layout, Path cache) throws IOException {
        Path root = ReplayStorageLayout.canonical(layout.cacheRoot().resolve("replay-v2"));
        Path path = ReplayStorageLayout.canonical(cache);
        if (!path.startsWith(root)) throw new IOException("Unrecognized legacy cache identity: " + cache);
        Path relative = root.relativize(path);
        if (relative.getNameCount() >= 2) return Path.of(readOwner(root.resolve(relative.subpath(0, 2))).getProperty("logical"));
        if (relative.getNameCount() == 1) {
            Path logical = null;
            try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(path)) {
                for (Path namespace : namespaces) {
                    Path candidate = Path.of(readOwner(namespace).getProperty("logical"));
                    if (logical != null && !identityKey(logical).equals(identityKey(candidate))) throw new IOException("Ambiguous cache owner");
                    logical = candidate;
                }
            }
            if (logical != null) return logical;
        }
        throw new IOException("Cache has no recognized replay owner: " + cache);
    }

    private static Properties readOwner(Path directory) throws IOException {
        requireDirectory(directory); requireRegular(directory.resolve("owner"));
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(directory.resolve("owner"))) {
            byte[] bytes = in.readNBytes(16385);
            if (bytes.length > 16384) throw new IOException("Oversized cache owner");
            p.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        }
        try {
            String id = p.getProperty("process"); UUID.fromString(id);
            String key = p.getProperty("identity");
            if (!"replay-cache-v2".equals(p.getProperty("format")) || !"2".equals(p.getProperty("version"))
                    || !directory.getFileName().toString().equals(id)
                    || !directory.getParent().getFileName().toString().equals(ReplayWorkspace.digest(key))
                    || !Path.of(p.getProperty("logical")).isAbsolute()
                    || !(p.getProperty("persistent").equals("true") || p.getProperty("persistent").equals("false"))) throw new IOException("Unknown cache owner: " + directory);
            long pid = Long.parseLong(p.getProperty("pid"));
            if (pid <= 0) throw new IOException("Invalid cache PID");
            if (!p.getProperty("start").equals("unknown")) Instant.parse(p.getProperty("start"));
            Long.parseLong(p.getProperty("lastUsed"));
            String logical = Path.of(p.getProperty("logical")).toAbsolutePath().normalize().toString();
            if (File.separatorChar == '\\') logical = logical.toLowerCase(Locale.ROOT);
            if (!logical.equals(key)) throw new IOException("Cache logical identity mismatch");
        } catch (RuntimeException invalid) { throw new IOException("Invalid cache owner: " + directory, invalid); }
        return p;
    }

    private static void writeOwner(Path directory, Properties owner) throws IOException {
        Path next = directory.resolve("owner.next-" + UUID.randomUUID());
        try (Writer out = Files.newBufferedWriter(next, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) { owner.store(out, "Replay cache ownership"); }
        Files.move(next, directory.resolve("owner"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); } catch (OverlappingFileLockException active) { return null; }
    }
    private static void requireDirectory(Path directory) throws IOException {
        ReplayStorageLayout.canonical(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a cache directory: " + directory);
    }
    private static void requireRegular(Path file) throws IOException {
        ReplayStorageLayout.canonical(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular cache file: " + file);
    }
    private static void preflight(Path directory) throws IOException {
        readOwner(directory);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) if (!Set.of("owner", "payload", "lease.lock").contains(child.getFileName().toString())) {
                throw new IOException("Unknown cache material: " + child);
            }
        }
        requireDirectory(directory.resolve("payload")); requireRegular(directory.resolve("lease.lock"));
        Files.walkFileTree(directory.resolve("payload"), new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException { requireDirectory(dir); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException { requireRegular(file); return FileVisitResult.CONTINUE; }
        });
    }
    private static void deleteNamespace(Path directory) throws IOException {
        preflight(directory); deleteTree(directory.resolve("payload"));
        Files.delete(directory.resolve("owner")); Files.delete(directory.resolve("lease.lock")); Files.delete(directory);
    }
    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException { requireDirectory(dir); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException { requireRegular(file); Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException { if (failure != null) throw failure; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
}
