package com.replaymod.core.files;

import com.google.common.net.PercentEscaper;
import com.replaymod.core.Setting;
import com.replaymod.core.SettingsRegistry;
import de.johni0702.minecraft.gui.versions.Image;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import static com.replaymod.core.utils.Utils.ensureDirectoryExists;

public class ReplayFoldersService {
    private final Path mcDir = MinecraftClient.getInstance().runDirectory.toPath();
    private final SettingsRegistry settings;

    public ReplayFoldersService(SettingsRegistry settings) {
        this.settings = settings;
    }

    /**
     * Project-owned video API: snapshot TEMP_PATH once, without allocating a task.
     * Null means the JDK default temp selection; explicit roots never fall back.
     * Relative paths resolve against mcDir. Nonblank values are not trimmed.
     */
    public Path snapshotVideoMetadataTempRoot() throws IOException {
        String configured = null;
        Path root = null;
        try {
            configured = settings.get(Setting.TEMP_PATH);
            if (configured == null || configured.isBlank()) return null;
            root = Path.of(configured);
            if (!root.isAbsolute() && root.getRoot() != null) {
                throw new IOException("Drive-relative or root-relative tempPath is not supported");
            }
            return mcDir.resolve(root).toAbsolutePath().normalize();
        } catch (IOException | InvalidPathException | UnsupportedOperationException | SecurityException failure) {
            throw new IOException("Cannot snapshot video metadata tempPath=" + configured
                    + "; phase=parse/resolve; resolved root=" + root, failure);
        }
    }

    /**
     * Creates one image task from a single TEMP_PATH setting snapshot. Blank means JDK
     * default temp; relative paths resolve against mcDir, never the process cwd.
     * Invalid/drive-relative paths and IO failures must fail without a fallback.
     * The returned handle owns only its private task directory and image.png.
     */
    public Image.TemporaryFile createImageTempFile() throws IOException {
        String configured = null;
        Path root = null;
        String phase = "read setting";
        try {
            configured = settings.get(Setting.TEMP_PATH);
            if (configured != null && !configured.isBlank()) {
                phase = "parse setting";
                root = Path.of(configured);
                if (!root.isAbsolute() && root.getRoot() != null) {
                    throw new IOException("Drive-relative or root-relative tempPath is not supported");
                }
                phase = "resolve against game directory";
                root = mcDir.resolve(root).toAbsolutePath().normalize();
            }
            phase = "create image task";
            return Image.TemporaryFile.create(root);
        } catch (IOException | InvalidPathException | UnsupportedOperationException | SecurityException failure) {
            throw new IOException("Cannot create temporary image; tempPath=" + configured
                    + "; phase=" + phase + "; resolved root=" + root, failure);
        }
    }

    /** One sender snapshot. Disk allocation is lazy; errors never select another root. */
    public ResourcePackLease createResourcePackLease() throws IOException {
        String configured = null;
        Path root = null;
        try {
            configured = settings.get(Setting.TEMP_PATH);
            if (configured != null && !configured.isBlank()) {
                root = Path.of(configured);
                if (!root.isAbsolute() && root.getRoot() != null) {
                    throw new IOException("Drive-relative or root-relative tempPath is not supported");
                }
                root = mcDir.resolve(root).toAbsolutePath().normalize();
            }
            return new ResourcePackLease(configured, root);
        } catch (IOException | InvalidPathException | UnsupportedOperationException | SecurityException failure) {
            throw new IOException("Cannot snapshot replay resource-pack tempPath=" + configured
                    + "; phase=parse/resolve; resolved root=" + root, failure);
        }
    }

    /** Pure JDK file ownership. No ReplayFile ownership or MC callbacks under the state lock. */
    public static final class ResourcePackLease {
        public enum State { ACTIVE, STOPPING, WAIT_RELEASE, SEALED, CLOSED, RETAINED }
        @FunctionalInterface public interface InputOpener { java.io.InputStream open() throws IOException; }
        public final class Permit implements AutoCloseable {
            private final long epoch;
            private final boolean producer;
            private boolean closed;
            private Permit(long epoch, boolean producer) { this.epoch = epoch; this.producer = producer; }
            private ResourcePackLease owner() { return ResourcePackLease.this; }
            public boolean valid() {
                synchronized (ResourcePackLease.this) {
                    return !closed && state == State.ACTIVE && epoch == generation;
                }
            }
            @Override public void close() {
                Runnable notify;
                synchronized (ResourcePackLease.this) {
                    if (closed) return;
                    closed = true;
                    if (producer) producers--; else admissions--;
                    notify = progress;
                }
                notify.run();
            }
        }
        private final String configured;
        private final Path selectedRoot;
        private final java.util.UUID token = java.util.UUID.randomUUID();
        private final Object copying = new Object();
        private final java.util.Set<Path> files = new java.util.LinkedHashSet<>();
        private final java.util.Map<Path, Receipt> receipts = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Path> ready = new java.util.HashMap<>();
        /** Created-object evidence, never a late snapshot of a cleanup candidate. */
        private static final class Receipt {
            Path path, anchor, guard;
            Object key, anchorKey, guardKey;
            boolean anchorProven, witnessed, complete;
            Receipt(Path path) { this.path = path; }
            boolean proven() { return key != null || anchorProven; }
        }
        private Path root, task, witnesses;
        private Object taskKey, witnessesKey;
        private Receipt marker;
        private boolean cleanupUnprovable, identityFailed, moveUncertain;
        private Path moveSource, moveCandidate;
        private State state = State.ACTIVE;
        private long generation;
        private int producers, admissions;
        private boolean cleanupAttempted, cleanupAuthorized;
        private Throwable diagnostic;
        private Runnable progress = () -> { };
        private ResourcePackLease(String configured, Path root) { this.configured = configured; selectedRoot = root; }
        public synchronized long generation() { return generation; }
        public synchronized State state() { return state; }
        public synchronized boolean terminal() { return state != State.ACTIVE; }
        public synchronized Path taskDirectory() { return task; }
        public java.util.UUID token() { return token; }
        public synchronized Throwable diagnostic() { return diagnostic; }
        /** Seek invalidates packets, not this task. Terminal is never reversible. */
        public synchronized long advanceGeneration() {
            if (state != State.ACTIVE) throw new IllegalStateException("Resource-pack sender is terminal");
            return ++generation;
        }
        public Permit producer(long epoch) throws IOException { return acquire(epoch, true); }
        /** Hold until the queued runnable exits, or scheduling fails. */
        public Permit admission(long epoch) throws IOException { return acquire(epoch, false); }
        private synchronized Permit acquire(long epoch, boolean producer) throws IOException {
            if (state != State.ACTIVE || generation != epoch) throw problem("stale/terminal permit", null);
            if (producer) producers++; else admissions++;
            return new Permit(epoch, producer);
        }
        synchronized boolean quiescent() { return state != State.ACTIVE && producers == 0 && admissions == 0; }
        synchronized void waiting() { if (state == State.STOPPING) state = State.WAIT_RELEASE; }
        synchronized void onProgress(Runnable listener) { progress = java.util.Objects.requireNonNull(listener); }
        synchronized boolean isReady(Path path) { return ready.containsValue(path); }
        synchronized void retain(Throwable cause) { if (diagnostic == null) diagnostic = cause; }
        synchronized boolean seal() {
            if (!quiescent() || state == State.CLOSED || state == State.RETAINED) return false;
            state = State.SEALED;
            return true;
        }
        synchronized void authorizeCleanup() { cleanupAuthorized = true; }
        public Path publish(String sha1, InputOpener input) throws IOException {
            try (Permit permit = producer(generation())) { return publish(permit, sha1, input); }
        }
        /** Only the returned stream is closed, never its enclosing ReplayFile. */
        public Path publish(Permit permit, String sha1, InputOpener input) throws IOException {
            java.util.Objects.requireNonNull(input, "input opener");
            if (sha1 == null || !sha1.matches("[0-9a-fA-F]{40}")) throw problem("invalid SHA-1=" + sha1, null);
            String hash = sha1.toLowerCase(java.util.Locale.ROOT);
            synchronized (copying) {
                Path staging = null;
                String phase = "validate permit";
                try {
                    requireProducer(permit);
                    Path previous;
                    synchronized (this) {
                        previous = ready.get(hash);
                        if (previous == null && task != null) {
                            Path candidate = task.resolve(hash + ".zip");
                            Receipt saved = receipts.get(candidate);
                            // A previous generation may have completed its rename after losing
                            // admission. Only its already-created, hash-verified receipt can be
                            // reused by a NEW valid producer; never adopt an existing pathname.
                            if (saved != null && saved.complete) previous = candidate;
                        }
                    }
                    if (previous != null) {
                        checkFile(previous, false);
                        synchronized (this) { requireProducer(permit); ready.put(hash, previous); }
                        return previous;
                    }
                    phase = "allocate task";
                    allocate();
                    requireProducer(permit);
                    checkBoundary();
                    phase = "create staging";
                    staging = task.resolve(hash + "." + java.util.UUID.randomUUID() + ".part");
                    java.security.MessageDigest digest;
                    try { digest = java.security.MessageDigest.getInstance("SHA-1"); }
                    catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                    phase = "open/copy/close both streams";
                    // Acquired input first, output second: output closes first. Record creation
                    // before any fallible identity setup or write; both streams still use TWR.
                    try (java.io.InputStream in = java.util.Objects.requireNonNull(input.open(), "missing pack stream");
                         java.io.OutputStream out = Files.newOutputStream(staging,
                                 java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                        Receipt created = new Receipt(staging);
                        synchronized (this) { files.add(staging); receipts.put(staging, created); }
                        establish(created);
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            requireProducer(permit);
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                        }
                        out.flush();
                    }
                    phase = "verify completed bytes";
                    if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(hash)) {
                        throw new IOException("Resource-pack SHA-1 does not match replay index");
                    }
                    requireProducer(permit);
                    checkFile(staging, false);
                    Path completed = task.resolve(hash + ".zip");
                    phase = "publish complete ZIP";
                    Receipt receipt;
                    synchronized (this) {
                        requireProducer(permit);
                        receipt = receipts.get(staging);
                        moveSource = staging;
                        moveCandidate = completed; // Reservation only, NOT candidate ownership.
                    }
                    try {
                        // No ATOMIC_MOVE or overwrite. The producer spans this entire IO and
                        // reconciliation; stop never needs the copying lock or waits for move.
                        Files.move(staging, completed);
                    } catch (IOException | RuntimeException failure) {
                        synchronized (this) { moveUncertain = true; ready.clear(); }
                        retain(problem("move result unknown; source=" + staging + "; candidate=" + completed, failure));
                        throw failure;
                    }
                    synchronized (this) {
                        // A normal-return move is a real ownership transition EVEN IF terminal.
                        // Never orphan a final merely because stop ran during the move.
                        files.remove(staging);
                        files.add(completed);
                        receipts.remove(staging);
                        receipt.path = completed;
                        receipt.complete = true;
                        receipts.put(completed, receipt);
                        moveSource = null;
                        moveCandidate = null;
                    }
                    checkFile(completed, false);
                    synchronized (this) {
                        requireProducer(permit);
                        ready.put(hash, completed);
                    }
                    return completed;
                } catch (IOException | RuntimeException failure) {
                    failure.addSuppressed(problem(phase + "; staging=" + staging, null));
                    throw failure;
                }
            }
        }
        private synchronized void requireProducer(Permit permit) throws IOException {
            if (moveUncertain || permit == null || permit.owner() != this || !permit.producer || !permit.valid()) {
                throw problem("foreign/stale/terminal producer", null);
            }
        }
        private void allocate() throws IOException {
            synchronized (this) { if (task != null) return; }
            Path canonicalRoot, allocated;
            if (selectedRoot == null) {
                allocated = Files.createTempDirectory(".replaymod-packs-" + token + "-").toAbsolutePath().normalize();
                synchronized (this) { task = allocated; cleanupUnprovable = true; }
                canonicalRoot = allocated.getParent().toRealPath();
                allocated = canonicalRoot.resolve(allocated.getFileName());
            } else {
                Files.createDirectories(selectedRoot);
                canonicalRoot = selectedRoot.toRealPath();
                allocated = Files.createTempDirectory(canonicalRoot, ".replaymod-packs-" + token + "-");
            }
            synchronized (this) { root = canonicalRoot; task = allocated; cleanupUnprovable = true; }
            Object directoryKey = directory(allocated).fileKey();
            synchronized (this) { taskKey = directoryKey; }
            // Optional identity infrastructure, same canonical root/volume. It never changes
            // the selected storage root or makes hard links/fileKey a playback prerequisite.
            try {
                Path created = Files.createTempDirectory(canonicalRoot, ".replaymod-witness-" + token + "-");
                synchronized (this) { witnesses = created; }
                Object key = directory(created).fileKey();
                synchronized (this) { witnessesKey = key; }
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                retain(problem("optional witness directory unavailable", unavailable));
            }
            Receipt createdMarker = null;
            try {
                Path path = allocated.resolve(".owner-" + token);
                Files.createFile(path);
                createdMarker = new Receipt(path);
                synchronized (this) { marker = createdMarker; }
                establish(createdMarker);
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                // A successful creation was already ledgered. Missing effective evidence means
                // one-shot zero-delete retention, not an extra requirement to play a pack.
                retain(problem("optional task marker unavailable", unavailable));
            }
            synchronized (this) {
                if (identityFailed) throw problem("task creation identity mismatch", diagnostic);
                cleanupUnprovable = (taskKey == null && (marker == null || !marker.proven()))
                        || (marker != null && (!marker.proven() || !marker.witnessed
                        && (marker.anchor != null && marker.anchorKey == null || marker.guard != null && marker.guardKey == null)))
                        || (witnesses != null && witnessesKey == null && (marker == null || !marker.witnessed));
            }
            checkBoundary();
        }
        private java.nio.file.attribute.BasicFileAttributes regular(Path path) throws IOException {
            var a = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther() || !path.equals(path.toRealPath())) {
                throw problem("expected ordinary owned file=" + path, null);
            }
            return a;
        }
        private java.nio.file.attribute.BasicFileAttributes directory(Path path) throws IOException {
            var a = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!a.isDirectory() || a.isSymbolicLink() || a.isOther() || !path.equals(path.toRealPath())) {
                throw problem("expected ordinary owned directory=" + path, null);
            }
            return a;
        }
        private void equalKey(Object expected, Object actual, Path path) throws IOException {
            if (expected != null && !expected.equals(actual)) throw problem("created identity key changed=" + path, null);
        }
        /** Called immediately after CREATE_NEW, before data writes; never at cleanup time. */
        private void establish(Receipt receipt) throws IOException {
            Object key = regular(receipt.path).fileKey();
            Path w;
            synchronized (this) { receipt.key = key; w = witnesses; }
            if (w != null) {
                try {
                    Path first = w.resolve(java.util.UUID.randomUUID() + ".link");
                    Files.createLink(first, receipt.path);
                    synchronized (this) { receipt.anchor = first; }
                    Object firstKey = regular(first).fileKey();
                    synchronized (this) { receipt.anchorKey = firstKey; }
                    // Different path operands are essential: isSameFile(p,p) can fast-return.
                    if (first.equals(receipt.path) || !Files.isSameFile(receipt.path, first)) {
                        throw identityProblem("new witness does not identify created file=" + receipt.path, null);
                    }
                    synchronized (this) { receipt.anchorProven = true; }
                    // The first effective witness never becomes "unsupported" if obtaining
                    // the second guard fails. Uses still verify it; cleanup may retain.
                    Path second = w.resolve(java.util.UUID.randomUUID() + ".guard");
                    Files.createLink(second, receipt.path);
                    synchronized (this) { receipt.guard = second; }
                    Object secondKey = regular(second).fileKey();
                    synchronized (this) { receipt.guardKey = secondKey; }
                    if (second.equals(first) || !Files.isSameFile(first, second)
                            || !Files.isSameFile(receipt.path, second)) {
                        throw identityProblem("new guard does not identify created file=" + receipt.path, null);
                    }
                    synchronized (this) { receipt.witnessed = true; }
                } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                    retain(problem("optional identity witness unavailable; file=" + receipt.path, unavailable));
                    // Once mismatch is known, this is not an unsupported-capability fallback.
                    synchronized (this) { if (identityFailed) throw problem("created witness identity failed", unavailable); }
                }
            }
            synchronized (this) {
                if (!receipt.proven() || (!receipt.witnessed
                        && (receipt.anchor != null && receipt.anchorKey == null
                        || receipt.guard != null && receipt.guardKey == null))) cleanupUnprovable = true;
            }
        }
        private IOException identityProblem(String phase, Throwable cause) {
            IOException failure = problem(phase, cause);
            synchronized (this) { identityFailed = true; retain(failure); }
            return failure;
        }
        private void verify(Receipt receipt) throws IOException {
            equalKey(receipt.key, regular(receipt.path).fileKey(), receipt.path);
            verifyAnchors(receipt);
            if (receipt.anchorProven && !Files.isSameFile(receipt.path, receipt.anchor)
                    || receipt.witnessed && !Files.isSameFile(receipt.path, receipt.guard)) {
                throw problem("file no longer identifies creation witnesses=" + receipt.path, null);
            }
        }
        private void verifyAnchors(Receipt receipt) throws IOException {
            if (receipt.anchor != null && (receipt.anchorProven || receipt.anchorKey != null)) {
                equalKey(receipt.anchorKey, regular(receipt.anchor).fileKey(), receipt.anchor);
            }
            if (receipt.guard != null && (receipt.witnessed || receipt.guardKey != null)) {
                equalKey(receipt.guardKey, regular(receipt.guard).fileKey(), receipt.guard);
            }
            if (receipt.witnessed && (receipt.anchor.equals(receipt.guard)
                    || !Files.isSameFile(receipt.anchor, receipt.guard))) {
                throw problem("creation witness pair changed=" + receipt.path, null);
            }
        }
        void checkBoundary() throws IOException {
            Path r, t, w;
            Object tk, wk;
            Receipt m;
            synchronized (this) { r = root; t = task; w = witnesses; tk = taskKey; wk = witnessesKey; m = marker; }
            if (t == null) return;
            try {
                if (r == null || !r.equals(r.toRealPath()) || !r.equals(t.getParent())) {
                    throw problem("task/root boundary changed", null);
                }
                equalKey(tk, directory(t).fileKey(), t);
                if (w != null && (wk != null || m != null && m.anchorProven)) {
                    if (!r.equals(w.getParent())) throw problem("witness/root boundary changed", null);
                    equalKey(wk, directory(w).fileKey(), w);
                }
                // Optional infrastructure with no established evidence must not turn a
                // previously usable platform into an identity-capability playback failure.
                if (m != null && m.proven()) verify(m);
            } catch (IOException | RuntimeException failure) {
                throw identityProblem("task/marker boundary check failed", failure);
            }
        }
        void checkFile(Path path, boolean missingAllowed) throws IOException {
            // missingAllowed is intentionally NOT authority to ignore disappearance of an
            // established receipt. The ledger is creation evidence, not an exists() scan.
            checkBoundary();
            Path t;
            Receipt receipt;
            synchronized (this) { t = task; receipt = receipts.get(path); }
            try {
                if (t == null || !t.equals(path.getParent()) || receipt == null) {
                    throw problem("file outside creation ledger=" + path, null);
                }
                verify(receipt);
            } catch (IOException | RuntimeException failure) {
                throw identityProblem("created file check failed=" + path, failure);
            }
        }
        public void stop() {
            Runnable notify;
            synchronized (this) {
                if (state != State.ACTIVE) return;
                state = State.STOPPING;
                generation++;
                notify = progress;
            }
            notify.run();
        }
        /** One attempt, exact ledger and empty task only. Failed cleanup is never retried. */
        public void cleanupAfterSeal() throws IOException {
            java.util.List<Receipt> owned;
            Path t, w;
            Receipt m;
            boolean unprovable;
            synchronized (this) {
                if (cleanupAttempted) return;
                if (state != State.SEALED || !cleanupAuthorized || !quiescent()) {
                    throw problem("cleanup lacks release/first-close proof", null);
                }
                cleanupAttempted = true;
                owned = java.util.List.copyOf(receipts.values());
                t = task; w = witnesses; m = marker;
                unprovable = cleanupUnprovable || identityFailed || moveUncertain;
            }
            try {
                // Full-ledger preflight BEFORE the first delete. Unknown move candidates are
                // never claimed based on existence; unsupported ownership means ZERO deletes.
                if (t != null) {
                    if (unprovable) throw problem("cleanup ownership unprovable; zero delete; moveSource="
                            + moveSource + "; candidate=" + moveCandidate + "; witnesses=" + w, diagnostic());
                    checkBoundary();
                    for (Receipt receipt : owned) {
                        if (!receipt.proven()) throw problem("payload has no effective identity=" + receipt.path, null);
                        checkFile(receipt.path, false);
                    }
                    for (Receipt receipt : owned) {
                        checkFile(receipt.path, false);
                        Files.delete(receipt.path);
                    }
                    // Keep every file's witnesses until all payloads are removed. Two distinct
                    // anchors still prove each other after the payload directory entry is gone.
                    for (Receipt receipt : owned) {
                        checkBoundary();
                        deleteAnchors(receipt);
                    }
                    checkBoundary();
                    if (m != null) { verify(m); Files.delete(m.path); }
                    // Directory inode is key-checked when available. With null keys the last
                    // marker/layout check -> empty-directory delete is an exclusive-use window,
                    // NOT a hostile TOCTOU/marker-transplant/parent-cloning defense.
                    equalKey(taskKey, directory(t).fileKey(), t);
                    Files.delete(t); // Foreign/unregistered items prevent deletion; never recurse.
                    if (m != null) deleteAnchors(m);
                    if (w != null) {
                        equalKey(witnessesKey, directory(w).fileKey(), w);
                        Files.delete(w);
                    }
                }
                synchronized (this) { state = State.CLOSED; files.clear(); receipts.clear(); ready.clear(); }
            } catch (IOException | RuntimeException failure) {
                IOException reported = problem("one-shot cleanup; files/witnesses may remain=" + files + "; witnesses=" + w, failure);
                synchronized (this) { state = State.RETAINED; retain(reported); }
                throw reported;
            }
        }
        private void deleteAnchors(Receipt receipt) throws IOException {
            verifyAnchors(receipt);
            if (receipt.anchor != null) Files.delete(receipt.anchor);
            if (receipt.guard != null) {
                // Last pair-check -> delete window relies on the documented trusted parent /
                // non-malicious exclusive cleanup contract. fileKey is optional and reusable.
                equalKey(receipt.guardKey, regular(receipt.guard).fileKey(), receipt.guard);
                Files.delete(receipt.guard);
            }
        }
        private IOException problem(String phase, Throwable cause) {
            return new IOException("Replay resource-pack task; phase=" + phase + "; tempPath=" + configured
                    + "; root=" + root + "; selectedRoot=" + selectedRoot + "; task=" + task + "; token=" + token, cause);
        }
    }

    /** One registry read: callbacks never expose half of a storage draft to a new task. */
    public ReplayStorageLayout snapshotStorageLayout() throws IOException {
        java.util.Map<SettingsRegistry.SettingKey<?>, Object> snapshot = settings.snapshot();
        return ReplayStorageLayout.resolve(mcDir,
                (String) snapshot.get(Setting.RECORDING_PATH), (String) snapshot.get(Setting.RAW_PATH),
                (String) snapshot.get(Setting.RECORDING_WORK_PATH), (String) snapshot.get(Setting.TMP_PATH),
                (String) snapshot.get(Setting.CACHE_PATH), (String) snapshot.get(Setting.TEMP_PATH),
                (Boolean) snapshot.get(Setting.EPHEMERAL_CACHE), (Boolean) snapshot.get(Setting.RAW_BACKUP_ENABLED));
    }

    /** Native tasks snapshot only tempPath; this does not allocate an image/video task. */
    public Path snapshotNativeTempRoot() throws IOException {
        String value = (String) settings.snapshot().get(Setting.TEMP_PATH);
        return value == null || value.isBlank() ? null : ReplayStorageLayout.resolvePath(mcDir, value);
    }

    public Path getReplayFolder() throws IOException {
        return ensureDirectoryExists(snapshotStorageLayout().replayRoot());
    }

    /**
     * Folder into which replay backups are saved before the MarkerProcessor is unleashed.
     */
    public Path getRawReplayFolder() throws IOException {
        ReplayStorageLayout layout = snapshotStorageLayout();
        if (!layout.rawBackupEnabled()) throw new IOException("Raw backups are disabled for this task");
        return ensureDirectoryExists(layout.rawRoot());
    }

    /**
     * Folder into which replays are recorded.
     * Distinct from the main folder, so they cannot be opened while they are still saving.
     */
    public Path getRecordingFolder() throws IOException {
        return ensureDirectoryExists(snapshotStorageLayout().recordingRoot());
    }

    /**
     * Folder in which replay cache files are stored.
     * Distinct from the recording folder cause people kept confusing them with recordings.
     */
    public Path getCacheFolder() throws IOException {
        Path path = ensureDirectoryExists(snapshotStorageLayout().cacheRoot());
        try {
            Files.setAttribute(path, "dos:hidden", true);
        } catch (UnsupportedOperationException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }
        return path;
    }

    /** Identity container only. Payload access must hold an actual ReplayCacheLease. */
    public Path getCachePathForReplay(Path replay) throws IOException {
        return ReplayCacheLease.identityDirectory(snapshotStorageLayout(), replay);
    }

    /** Unknown/URL legacy names do not grant filesystem access or cleanup authority. */
    public Path getReplayPathForCache(Path cache) throws IOException {
        return ReplayCacheLease.replayForCache(snapshotStorageLayout(), cache);
    }
}
