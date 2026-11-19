package com.replaymod.core.files;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Immutable, non-allocating task snapshot. Only owned children, never these roots, may be cleaned. */
public final class ReplayStorageLayout {
    private final Path replay, raw, recording, tmp, cache, temp, recovery;
    private final boolean ephemeral, rawEnabled;

    private ReplayStorageLayout(Path replay, Path raw, Path recording, Path tmp, Path cache,
            Path temp, Path recovery, boolean ephemeral, boolean rawEnabled) {
        this.replay = replay; this.raw = raw; this.recording = recording; this.tmp = tmp;
        this.cache = cache; this.temp = temp; this.recovery = recovery;
        this.ephemeral = ephemeral; this.rawEnabled = rawEnabled;
    }

    public static ReplayStorageLayout resolve(Path gameDir, String recordingPath, String rawPath,
            String recordingWorkPath, String tmpPath, String cachePath, String tempPath,
            boolean ephemeralCache) throws IOException {
        return resolve(gameDir, recordingPath, rawPath, recordingWorkPath, tmpPath,
                cachePath, tempPath, ephemeralCache, true);
    }

    public static ReplayStorageLayout resolve(Path gameDir, String recordingPath, String rawPath,
            String recordingWorkPath, String tmpPath, String cachePath, String tempPath,
            boolean ephemeralCache, boolean rawBackupEnabled) throws IOException {
        Path game = canonical(gameDir);
        Path replay = resolvePath(game, blank(recordingPath) ? "./replay_recordings/" : recordingPath);
        Path cache = resolvePath(game, blank(cachePath) ? "./.replay_cache/" : cachePath);
        // Do not even parse or probe rawPath while disabled (it may refer to an absent volume).
        Path raw = !rawBackupEnabled ? null : blank(rawPath) ? canonical(replay.resolve("raw")) : resolvePath(game, rawPath);
        Path recording = blank(recordingWorkPath) ? canonical(replay.resolve("recording")) : resolvePath(game, recordingWorkPath);
        Path tmp = blank(tmpPath) ? null : resolvePath(game, tmpPath);
        Path temp = blank(tempPath) ? null : resolvePath(game, tempPath);
        Path recovery = canonical(game.resolve(".replaymod-recovery"));
        for (Path root : new Path[] { replay, raw, recording, tmp, cache, temp, recovery }) {
            if (root != null && Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Storage root is not a directory: " + root);
            }
        }
        Path namespace = canonical(cache.resolve("replay-v2"));
        for (Path protectedRoot : new Path[] { replay, raw, recording, tmp, temp, recovery }) {
            if (protectedRoot != null && protectedRoot.startsWith(namespace)) {
                throw new IOException("Replay cache namespace contains a protected root: " + protectedRoot);
            }
        }
        return new ReplayStorageLayout(replay, raw, recording, tmp, cache, temp, recovery,
                ephemeralCache, rawBackupEnabled);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    static Path resolvePath(Path game, String value) throws IOException {
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() && (path.getRoot() != null || value.matches("^[A-Za-z]:.*"))) {
                throw new IOException("Drive-relative/root-relative path is not supported: " + value);
            }
            return canonical(game.resolve(path));
        } catch (InvalidPathException | SecurityException failure) {
            throw new IOException("Invalid storage path: " + value, failure);
        }
    }

    /** Resolve existing ancestors, rejecting links/junction aliases rather than trusting cleanup through them. */
    static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            BasicFileAttributes attrs;
            try { attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException missing) { continue; }
            if (attrs.isSymbolicLink() || attrs.isOther()
                    || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Linked/unsupported storage boundary: " + current);
            }
        }
        Path ancestor = absolute;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
            if (ancestor == null) throw new IOException("No existing ancestor: " + path);
        }
        return ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize();
    }

    public Path replayRoot() { return replay; }
    /** Null while raw backups are disabled; callers must check rawBackupEnabled first. */
    public Path rawRoot() { return raw; }
    public Path recordingRoot() { return recording; }
    public Path tmpRoot() { return tmp; }
    public Path cacheRoot() { return cache; }
    public Path tempRoot() { return temp; }
    public boolean ephemeralCache() { return ephemeral; }
    public boolean rawBackupEnabled() { return rawEnabled; }
    public Path recoveryRoot() { return recovery; }
}
