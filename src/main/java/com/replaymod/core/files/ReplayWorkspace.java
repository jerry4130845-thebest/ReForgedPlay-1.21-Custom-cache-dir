package com.replaymod.core.files;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** One locked replay, immutable private base, cumulative sidecar, and small fixed-game recovery index.
 * Trusted non-malicious parents are required; this is not an adversarial filesystem sandbox.
 */
public final class ReplayWorkspace {
    private final ReplayStorageLayout layout;
    private final String id;
    private final Path directory, record, output, physicalOutput, sidecar;
    private Path base, legacy, legacyCopy, stage, protectedBase, protectedSidecar;
    private Path identityDirectory, expectedAnchor, logicalInput, publicationTarget, legacyRetired, predecessor;
    private boolean committed;
    private final boolean readOnly;
    private final List<FileChannel> channels = new ArrayList<>();
    private final List<FileLock> locks = new ArrayList<>();
    private BasicFileAttributes expected;
    private String expectedHash;
    private boolean imported, released, protectedState;
    private String phase = "active", publicationState = "none", failedPhase;

    private ReplayWorkspace(ReplayStorageLayout layout, Path output, boolean readOnly, String id, Path directory) {
        this.layout = layout; this.output = output; this.readOnly = readOnly; this.id = id; this.directory = directory;
        this.record = layout.recoveryRoot().resolve(id + ".properties");
        this.physicalOutput = directory.resolve("output.mcpr");
        this.sidecar = directory.resolve("output.mcpr.tmp");
    }

    public static ReplayWorkspace create(ReplayStorageLayout layout, Path input, Path output,
            boolean readOnly) throws IOException {
        return create(layout, input, output, readOnly, null);
    }

    /** Import a legacy sidecar in place; never move a nonempty recording directory. */
    static ReplayWorkspace importLegacy(ReplayStorageLayout layout, Path original, Path output) throws IOException {
        Path source = ReplayStorageLayout.canonical(original);
        Path old = source.resolveSibling(source.getFileName() + ".tmp");
        if (!Files.exists(old, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing legacy recovery material: " + old);
        ReplayWorkspace result = create(layout, Files.exists(source, LinkOption.NOFOLLOW_LINKS) ? source : null,
                output, false, old);
        try {
            result.legacy = ReplayStorageLayout.canonical(old);
            result.writeRecord("dirty");
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            result.constructionFailed(failure);
            throw failure;
        }
    }

    private static ReplayWorkspace create(ReplayStorageLayout layout, Path input, Path output,
            boolean readOnly, Path importSidecar) throws IOException {
        Path target = ReplayStorageLayout.canonical(output);
        Path source = input == null ? null : ReplayStorageLayout.canonical(input);
        Path root = layout.tmpRoot() == null ? target.getParent() : layout.tmpRoot();
        Files.createDirectories(root);
        ReplayStorageLayout.canonical(root);
        Files.createDirectories(layout.recoveryRoot());
        ReplayStorageLayout.canonical(layout.recoveryRoot());
        String id = UUID.randomUUID().toString();
        Path directory = Files.createDirectory(root.resolve(".replaymod-work-" + id));
        ReplayWorkspace result = new ReplayWorkspace(layout, target, readOnly, id, directory);
        try {
            Files.writeString(directory.resolve("owner"), "replay-work-v1\n" + id + "\n", StandardOpenOption.CREATE_NEW);
            result.acquire(directory.resolve("live.lock"));
            Path lockRoot = layout.recoveryRoot().resolve("locks");
            Files.createDirectories(lockRoot);
            ReplayStorageLayout.canonical(lockRoot);
            TreeSet<String> identities = new TreeSet<>();
            identities.add(ReplayCacheLease.identityKey(target));
            if (source != null) identities.add(ReplayCacheLease.identityKey(source));
            for (String identity : identities) result.acquire(lockRoot.resolve(digest(identity) + ".lock"));
            Path old = importSidecar == null ? target.resolveSibling(target.getFileName() + ".tmp") : importSidecar;
            boolean hasOld = Files.exists(old, LinkOption.NOFOLLOW_LINKS);
            if (hasOld) {
                ReplayStorageLayout.canonical(old);
                requireDirectory(old);
                // Match the real constructor: source is consulted ONLY when input == null.
                if (source == null && Files.exists(old.resolve("source"), LinkOption.NOFOLLOW_LINKS)) {
                    source = ReplayStorageLayout.canonical(Path.of(readSmall(old.resolve("source"))));
                    requireRegular(source);
                    if (!identities.contains(ReplayCacheLease.identityKey(source))) result.acquire(identityLockPath(layout, source));
                }
            }
            result.logicalInput = source;
            if (source != null && Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                requireRegular(source);
                if (readOnly) result.base = source;
                else {
                    result.base = directory.resolve("base.mcpr");
                    copyVerified(source, result.base);
                }
                // Validate the complete input, including CRC, but do not require entries for an unfinished recovery.
                validateZip(result.base, false);
            }
            if (!readOnly && source != null && source.equals(target) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                result.rememberTarget();
                if (Files.mismatch(result.base, target) != -1) throw new IOException("Input changed after private snapshot: " + target);
            }
            if (hasOld) {
                result.legacyCopy = directory.resolve("imported-sidecar");
                copyTree(old, result.legacyCopy);
                copyTree(result.legacyCopy, result.sidecar);
                result.legacy = importSidecar == null ? old : null;
                result.imported = true;
                result.rewriteSource(result.sidecar, result.base);
            }
            result.writeRecord("active");
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            // Preserve imported/private data and an index if creation got far enough. Never destructive delegate.close here.
            try { result.writeRecord("construction-failed"); } catch (Throwable note) { failure.addSuppressed(note); }
            try { result.unlock(); } catch (Throwable close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    static Path identityLockPath(ReplayStorageLayout layout, Path replay) throws IOException {
        return layout.recoveryRoot().resolve("locks").resolve(digest(ReplayCacheLease.identityKey(replay)) + ".lock");
    }

    private void acquire(Path path) throws IOException {
        ReplayStorageLayout.canonical(path);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException occupied) { throw new IOException("Replay is locked: " + path, occupied); }
            if (lock == null) throw new IOException("Replay is locked: " + path);
            channels.add(channel); locks.add(lock);
        } catch (IOException | RuntimeException | Error failure) {
            try { channel.close(); } catch (Throwable close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    Path base() { return base; }
    Path physicalOutput() { return physicalOutput; }
    Path logicalOutput() { return output; }
    Path recordPath() { return record; }
    String describe() { return "phase=" + phase + "; publication=" + publicationState + "; record=" + record
            + "; workspace=" + directory + "; sidecar=" + sidecar + "; stage=" + stage; }
    boolean readOnly() { return readOnly; }
    boolean imported() { return imported; }

    void markDirty() throws IOException { committed = false; writeRecord("dirty"); }

    /** Conversion material never leaves this exact locked owner directory. */
    Path packetRecoveryFile() throws IOException {
        verifyOwner(directory, id);
        return Files.createTempFile(directory, "packet-recovery-", ".tmcpr");
    }

    /** Entry enumeration without delegate.getAll's exception-leaking partially opened map. */
    Set<String> entries() throws IOException {
        Set<String> entries = new TreeSet<>();
        if (base != null) try (ZipFile zip = new ZipFile(base.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) entries.add(all.nextElement().getName());
        }
        entries.removeAll(treeEntries(sidecar.resolve("removed")));
        entries.addAll(treeEntries(sidecar.resolve("changed")));
        return entries;
    }

    Path prepareStage(Path destination) throws IOException {
        Path target = ReplayStorageLayout.canonical(destination);
        if (target.startsWith(directory) || (identityDirectory != null && target.startsWith(identityDirectory))) {
            throw new IOException("Publication target is inside this replay's private materials: " + target);
        }
        Files.createDirectories(target.getParent());
        ReplayStorageLayout.canonical(target.getParent());
        Path folder = Files.createTempDirectory(target.getParent(), ".replaymod-publish-" + id + "-");
        Files.writeString(folder.resolve("owner"), "replay-stage-v1\n" + id + "\n", StandardOpenOption.CREATE_NEW);
        stage = folder.resolve("payload.mcpr");
        publicationTarget = target;
        publicationState = "not-published";
        writeRecord("staging");
        return stage;
    }

    void publish(Path destination, boolean export) throws IOException {
        Path target = ReplayStorageLayout.canonical(destination);
        Set<String> actual = validateZip(stage, true);
        if (!actual.equals(entries())) throw new IOException("ZIP entry set mismatch; stage retained: " + stage);
        String stagedHash = fileHash(stage);
        writeRecord("validated");
        if (export || expected == null) {
            // No-clobber, including a target created after acquisition; ATOMIC_MOVE is NOT no-clobber.
            publicationState = "attempting";
            writeRecord("publishing");
            Files.createLink(target, stage);
            if (!Files.isSameFile(target, stage)) throw new IOException("Published link identity uncertain: " + target);
        } else {
            verifyExpected();
            // Only an already confirmed same logical input/output may be atomically replaced.
            publicationState = "attempting";
            writeRecord("publishing");
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        publicationState = "published-unconfirmed";
        writeRecord("published-unconfirmed");
        validateZip(target, true);
        if (!stagedHash.equals(fileHash(target))) throw new IOException("Published replay mismatch: " + target + "; stage=" + stage);
        Path previousAnchor = expectedAnchor;
        if (!export) {
            rememberTarget();
            if (!stagedHash.equals(expectedHash)) throw new IOException("Target changed while establishing identity: " + target);
            try { retireLegacy(); }
            catch (IOException | RuntimeException cleanup) {
                System.err.println("Replay committed; legacy retirement retained " + legacy + ": " + cleanup);
            }
        }
        publicationState = "confirmed";
        writeRecord(export ? "exported" : "committed");
        if (!export) committed = true;
        if (!export && previousAnchor != null) {
            try { Files.delete(previousAnchor); }
            catch (IOException cleanup) { System.err.println("Previous replay identity retained: " + previousAnchor + ": " + cleanup); }
        }
        // Cleanup is optional after a confirmed publication; never turn cleanup failure into lost success.
        try {
            Files.deleteIfExists(stage);
            Files.delete(stage.getParent().resolve("owner"));
            Files.delete(stage.getParent());
            stage = null;
        } catch (IOException cleanup) { System.err.println("Replay publication confirmed; retained stage: " + stage + ": " + cleanup); }
    }

    private void rememberTarget() throws IOException {
        // Windows BasicFileAttributes.fileKey may be null. A retained hard-link witness supplies
        // a real isSameFile identity and keeps the previous version alive through replacement.
        if (identityDirectory == null) {
            identityDirectory = Files.createTempDirectory(output.getParent(), ".replaymod-identity-" + id + "-");
            Files.writeString(identityDirectory.resolve("owner"), "replay-identity-v1\n" + id + "\n", StandardOpenOption.CREATE_NEW);
        }
        Path anchor = identityDirectory.resolve(UUID.randomUUID() + ".mcpr");
        Files.createLink(anchor, output);
        if (!Files.isSameFile(anchor, output)) throw new IOException("Cannot establish replay target identity: " + output);
        expected = requireRegular(output);
        expectedHash = fileHash(output);
        expectedAnchor = anchor;
    }

    private void verifyExpected() throws IOException {
        BasicFileAttributes current = requireRegular(output);
        if (expectedAnchor == null || !Files.isSameFile(expectedAnchor, output)
                || (expected.fileKey() != null && current.fileKey() != null && !expected.fileKey().equals(current.fileKey()))
                || expected.size() != current.size()
                || !expected.lastModifiedTime().equals(current.lastModifiedTime())
                || !expectedHash.equals(fileHash(output))) {
            throw new IOException("Replay target changed; refusing replacement: " + output);
        }
    }

    private void retireLegacy() throws IOException {
        if (legacy == null) return;
        // Keep the old sidecar as evidence; only retire its scanner-visible name after verified commit.
        verifyTree(legacy, legacyCopy);
        Path retired = legacy.resolveSibling(legacy.getFileName() + ".imported-" + id);
        Files.move(legacy, retired, StandardCopyOption.ATOMIC_MOVE);
        legacyRetired = retired;
        legacy = null;
    }

    void saveFailed(Throwable failure) {
        failedPhase = phase;
        try { writeRecord("save-failed"); }
        catch (Throwable note) { failure.addSuppressed(note); }
    }

    /** An independent copy is mandatory before ZipReplayFile.close deletes its live sidecar. */
    void protect() throws IOException {
        if (protectedState) return;
        Path checkpoint = Files.createDirectory(directory.resolve("protected-" + UUID.randomUUID()));
        Path copyBase = null;
        if (base != null) {
            copyBase = checkpoint.resolve("base.mcpr");
            copyVerified(base, copyBase);
            validateZip(copyBase, false);
        }
        Path copySidecar = checkpoint.resolve("output.mcpr.tmp");
        if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) copyTree(sidecar, copySidecar);
        else Files.createDirectory(copySidecar);
        rewriteSource(copySidecar, copyBase);
        // copyVerified/verifyTree cover every changed/removed/source byte before source is deliberately rebound.
        protectedBase = copyBase; protectedSidecar = copySidecar;
        writeRecord("protected");
        protectedState = true;
        System.err.println("Uncommitted replay retained; recovery record=" + record + "; materials=" + checkpoint);
    }

    private void rewriteSource(Path tree, Path source) throws IOException {
        Path locator = tree.resolve("source");
        if (source == null) Files.deleteIfExists(locator);
        else Files.writeString(locator, source.toString(), StandardCharsets.UTF_8);
    }

    /** Actual delegate/stream closure was confirmed. Cleanup is restricted to this exact owner. */
    void finish(boolean retain) throws IOException {
        if (released) return;
        if (retain) writeRecord("closed-retained");
        else writeRecord("finished"); // seal before unlocking: no scanner may acquire a finished task
        if (!retain && committed && predecessor != null) {
            try { retirePredecessors(); }
            catch (IOException | RuntimeException uncertain) {
                System.err.println("Committed replay; predecessor retained: " + predecessor + ": " + uncertain);
            }
        }
        if (!retain && legacyRetired != null) {
            // Old ReplayStudio supplied no lifetime lock/owner. A matching copy is NOT proof of no old readers.
            System.err.println("Verified legacy sidecar retained (no old lifetime ownership proof): " + legacyRetired);
        }
        unlock();
        released = true;
        if (!retain && legacyRetired == null && legacy == null) {
            try {
                verifyOwner(directory, id);
                if (identityDirectory != null) {
                    ReplayStorageLayout.canonical(identityDirectory);
                    if (!readSmall(identityDirectory.resolve("owner")).equals("replay-identity-v1\n" + id + "\n")) throw new IOException("Unknown target identity owner: " + identityDirectory);
                    deleteOwnedTree(identityDirectory);
                }
                deleteOwnedTree(directory);
                Files.deleteIfExists(record);
            } catch (IOException cleanup) {
                System.err.println("Closed replay workspace retained: " + directory + "; record=" + record + ": " + cleanup);
            }
        }
    }

    /** A throwing external constructor supplied no real-release receipt. Keep its live/identity locks. */
    void constructionUnreleased(Throwable failure) {
        try { writeRecord("adapter-construction-unreleased"); } catch (Throwable note) { failure.addSuppressed(note); }
        System.err.println("Replay construction handle release unproven; retained until process exits: " + describe());
    }

    /** Construction failed before an unknown external handle was opened. */
    void constructionFailed(Throwable failure) {
        try { writeRecord("adapter-construction-failed"); } catch (Throwable note) { failure.addSuppressed(note); }
        try { unlock(); } catch (Throwable close) { failure.addSuppressed(close); }
    }

    private void unlock() throws IOException {
        IOException failure = null;
        for (int i = 0; i < channels.size(); i++) {
            try { if (locks.get(i).isValid()) locks.get(i).release(); channels.get(i).close(); }
            catch (IOException close) { if (failure == null) failure = close; else failure.addSuppressed(close); }
        }
        if (failure != null) throw failure;
    }

    private void writeRecord(String phase) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "1"); p.setProperty("id", id); p.setProperty("phase", phase);
        p.setProperty("publicationState", publicationState);
        if (failedPhase != null) p.setProperty("failedPhase", failedPhase);
        p.setProperty("workspace", directory.toString()); p.setProperty("output", output.toString());
        p.setProperty("physicalOutput", physicalOutput.toString()); p.setProperty("sidecar", sidecar.toString());
        p.setProperty("readOnly", Boolean.toString(readOnly)); p.setProperty("rejected", "false");
        if (logicalInput != null) p.setProperty("input", logicalInput.toString());
        if (publicationTarget != null) p.setProperty("stageTarget", publicationTarget.toString());
        if (legacyRetired != null) p.setProperty("legacyRetired", legacyRetired.toString());
        if (base != null) p.setProperty("base", base.toString());
        if (protectedBase != null) p.setProperty("recoveryBase", protectedBase.toString());
        if (protectedSidecar != null) p.setProperty("recoverySidecar", protectedSidecar.toString());
        if (stage != null) p.setProperty("stage", stage.toString());
        if (expectedHash != null) p.setProperty("expectedTargetSHA256", expectedHash);
        if (expectedAnchor != null) p.setProperty("expectedTargetAnchor", expectedAnchor.toString());
        if (identityDirectory != null) p.setProperty("identityDirectory", identityDirectory.toString());
        if (legacy != null) p.setProperty("legacy", legacy.toString());
        if (predecessor != null) p.setProperty("predecessor", predecessor.toString());
        writeProperties(record, p);
        this.phase = phase;
    }

    private static void writeProperties(Path record, Properties p) throws IOException {
        Path next = record.resolveSibling(record.getFileName() + ".next-" + UUID.randomUUID());
        try (OutputStream out = Files.newOutputStream(next, StandardOpenOption.CREATE_NEW)) { p.store(out, "ReplayMod recovery locator, not deletion authority"); }
        try (FileChannel file = FileChannel.open(next, StandardOpenOption.WRITE)) { file.force(true); }
        Files.move(next, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static ReplayWorkspace recover(ReplayStorageLayout layout, Path record) throws IOException {
        Properties p = readRecord(layout, record);
        if (Set.of("finished", "superseded", "construction-failed", "recovery-copy-failed").contains(p.getProperty("phase"))) throw new IOException("Recovery incomplete/transferred/finished: " + p.getProperty("successor"));
        if (Boolean.parseBoolean(p.getProperty("rejected"))) throw new IOException("Recovery was rejected: " + record);
        if (Boolean.parseBoolean(p.getProperty("readOnly"))) throw new IOException("Read-only workspace is not recovery material: " + record);
        Path old = Path.of(p.getProperty("workspace"));
        try (FileChannel channel = FileChannel.open(old.resolve("live.lock"), StandardOpenOption.WRITE)) {
            FileLock lock = tryRecoveryLock(channel, old);
            try (lock) {
                // Another prompt/process may have rejected or transferred it between lookup and lock admission.
                p = readRecord(layout, record);
                if (Set.of("finished", "superseded", "construction-failed", "recovery-copy-failed").contains(p.getProperty("phase"))
                        || Boolean.parseBoolean(p.getProperty("rejected")) || Boolean.parseBoolean(p.getProperty("readOnly"))) {
                    throw new IOException("Recovery state changed before admission: " + record);
                }
                Path base = recordChild(p, "recoveryBase", "base", old);
                Path sidecar = recordChild(p, "recoverySidecar", "sidecar", old);
                if (base != null && !Files.exists(base, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing recovery base: " + base);
                if (sidecar == null || !Files.isDirectory(sidecar, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing recovery sidecar: " + sidecar);
                ReplayWorkspace result = create(layout, base, Path.of(p.getProperty("output")), false, sidecar);
                try {
                    String anchorValue = p.getProperty("expectedTargetAnchor");
                    if (anchorValue != null) {
                        Path anchor = ReplayStorageLayout.canonical(Path.of(anchorValue));
                        Path identity = ReplayStorageLayout.canonical(Path.of(p.getProperty("identityDirectory")));
                        String oldId = p.getProperty("id");
                        if (!anchor.getParent().equals(identity)
                                || !identity.getParent().equals(result.output.getParent())
                                || !identity.getFileName().toString().startsWith(".replaymod-identity-" + oldId + "-")
                                || !readSmall(identity.resolve("owner")).equals("replay-identity-v1\n" + oldId + "\n")) {
                            throw new IOException("Unknown recovery target identity: " + anchor);
                        }
                        requireRegular(anchor);
                        if (!Files.isSameFile(anchor, result.output)
                                || !p.getProperty("expectedTargetSHA256", "").equals(fileHash(result.output))) {
                            throw new IOException("Recovery target changed; refusing replacement: " + result.output);
                        }
                        result.rememberTarget();
                        result.writeRecord("dirty");
                    }
                    // The new independent working copy and record exist before retiring the old prompt.
                    // Keep the old materials; this is a locator transition, not deletion authority.
                    result.predecessor = ReplayStorageLayout.canonical(record);
                    result.writeRecord("dirty");
                    p.setProperty("phase", "superseded");
                    p.setProperty("successor", result.record.toString());
                    writeProperties(record, p);
                    return result;
                } catch (IOException | RuntimeException | Error failure) {
                    // The old locator was NOT transferred. Keep the failed copy as evidence, not a duplicate prompt.
                    try { result.writeRecord("recovery-copy-failed"); } catch (Throwable note) { failure.addSuppressed(note); }
                    try { result.unlock(); } catch (Throwable close) { failure.addSuppressed(close); }
                    throw failure;
                }
            }
        }
    }

    public static List<Path> scanRecovery(ReplayStorageLayout layout) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.exists(layout.recoveryRoot(), LinkOption.NOFOLLOW_LINKS)) return result;
        ReplayStorageLayout.canonical(layout.recoveryRoot());
        try (DirectoryStream<Path> files = Files.newDirectoryStream(layout.recoveryRoot(), "*.properties")) {
            for (Path file : files) {
                try {
                    Properties p = readRecord(layout, file);
                    if (Set.of("finished", "superseded", "construction-failed", "recovery-copy-failed").contains(p.getProperty("phase")) || Boolean.parseBoolean(p.getProperty("readOnly"))
                            || Boolean.parseBoolean(p.getProperty("rejected"))) continue;
                    Path old = Path.of(p.getProperty("workspace"));
                    Path material = recordChild(p, "recoverySidecar", "sidecar", old);
                    if (material == null || !Files.isDirectory(material, LinkOption.NOFOLLOW_LINKS)) continue;
                    try (FileChannel channel = FileChannel.open(old.resolve("live.lock"), StandardOpenOption.WRITE)) {
                        try (FileLock lock = tryRecoveryLock(channel, old)) { result.add(file); }
                    }
                } catch (IOException | RuntimeException uncertain) {
                    System.err.println("Recovery record retained/skipped: " + file + ": " + uncertain);
                }
            }
        }
        return result;
    }

    /** Reading a locator is not an admission/release receipt; recover validates it again under its lock. */
    public static Path recoveryOutput(ReplayStorageLayout layout, Path record) throws IOException {
        return ReplayStorageLayout.canonical(Path.of(readRecord(layout, record).getProperty("output")));
    }

    static Set<Path> indexedLegacy(ReplayStorageLayout layout) throws IOException {
        Set<Path> result = new HashSet<>();
        if (!Files.exists(layout.recoveryRoot(), LinkOption.NOFOLLOW_LINKS)) return result;
        ReplayStorageLayout.canonical(layout.recoveryRoot());
        try (DirectoryStream<Path> records = Files.newDirectoryStream(layout.recoveryRoot(), "*.properties")) {
            for (Path record : records) try {
                Properties p = readRecord(layout, record);
                String legacy = p.getProperty("legacy");
                if (legacy != null) result.add(ReplayStorageLayout.canonical(Path.of(legacy)));
            } catch (IOException | RuntimeException uncertain) {
                System.err.println("Legacy index retained/skipped: " + record + ": " + uncertain);
            }
        }
        return result;
    }

    public static void rejectRecovery(ReplayStorageLayout layout, Path record) throws IOException {
        Properties p = readRecord(layout, record);
        if (Set.of("finished", "superseded").contains(p.getProperty("phase"))) throw new IOException("Recovery has moved: " + record);
        Path old = Path.of(p.getProperty("workspace"));
        try (FileChannel channel = FileChannel.open(old.resolve("live.lock"), StandardOpenOption.WRITE);
                FileLock lock = tryRecoveryLock(channel, old)) {
            p = readRecord(layout, record);
            if (Set.of("finished", "superseded").contains(p.getProperty("phase"))) throw new IOException("Recovery moved before rejection admission: " + record);
            // Idempotent rejection does not restart or shorten the retention clock.
            if (!Boolean.parseBoolean(p.getProperty("rejected"))) {
                p.setProperty("rejected", "true");
                p.setProperty("rejectedAt", Long.toString(System.currentTimeMillis()));
                writeProperties(record, p);
            }
        }
    }

    /** Only versioned rejection/confirmed-descendant receipts authorize fixed-index cleanup. */
    static void cleanupRecovery(ReplayStorageLayout layout) throws IOException {
        if (!Files.exists(layout.recoveryRoot(), LinkOption.NOFOLLOW_LINKS)) return;
        ReplayStorageLayout.canonical(layout.recoveryRoot());
        try (DirectoryStream<Path> records = Files.newDirectoryStream(layout.recoveryRoot(), "*.properties")) {
            for (Path record : records) try {
                Properties p = readRecord(layout, record);
                if (Boolean.parseBoolean(p.getProperty("rejected"))) {
                    if ("rejected-pruned".equals(p.getProperty("phase"))) continue;
                    long at = Long.parseLong(p.getProperty("rejectedAt", "-1")), now = System.currentTimeMillis();
                    if (at < 0 || at > now || now - at < 2L * 24 * 60 * 60 * 1000) continue;
                    deleteIndexedWorkspace(layout, record, null);
                } else if ("superseded".equals(p.getProperty("phase")) && "true".equals(p.getProperty("descendantReleased"))) {
                    String hash = p.getProperty("descendantSHA256", "");
                    if (!hash.matches("[0-9a-f]{64}") || !hash.equals(fileHash(Path.of(p.getProperty("output"))))) continue;
                    deleteIndexedWorkspace(layout, record, p.getProperty("descendant"));
                }
            } catch (IOException | RuntimeException uncertain) {
                System.err.println("Recovery cleanup retained/skipped: " + record + ": " + uncertain);
            }
        }
    }

    /** Called only after THIS descendant's complete stream/delegate close, never from a missing record. */
    private void retirePredecessors() throws IOException {
        if (!committed || !expectedHash.equals(fileHash(output))) throw new IOException("Committed descendant identity changed");
        Set<Path> visited = new HashSet<>();
        Path previous = predecessor, successor = record;
        for (int depth = 0; previous != null && depth < 32; depth++) {
            if (!visited.add(previous)) throw new IOException("Recovery ancestry cycle");
            Properties p = readRecord(layout, previous);
            if (!"superseded".equals(p.getProperty("phase")) || !successor.toString().equals(p.getProperty("successor"))
                    || !ReplayStorageLayout.canonical(Path.of(p.getProperty("output"))).equals(output)) {
                throw new IOException("Unproven recovery ancestry: " + previous);
            }
            Path old = Path.of(p.getProperty("workspace"));
            String ancestor = p.getProperty("predecessor");
            try (FileChannel channel = FileChannel.open(old.resolve("live.lock"), StandardOpenOption.WRITE);
                    FileLock lock = tryRecoveryLock(channel, old)) {
                readRecord(layout, previous);
                p.setProperty("descendant", id); p.setProperty("descendantReleased", "true");
                p.setProperty("descendantSHA256", expectedHash);
                writeProperties(previous, p); // durable positive receipt BEFORE any material is removed
            }
            deleteIndexedWorkspace(layout, previous, id);
            successor = previous;
            previous = ancestor == null ? null : Path.of(ancestor);
        }
        if (previous != null) throw new IOException("Recovery ancestry depth limit; earlier ancestors retained");
    }

    private static void deleteIndexedWorkspace(ReplayStorageLayout layout, Path record, String descendant) throws IOException {
        Properties p = readRecord(layout, record);
        Path old = Path.of(p.getProperty("workspace"));
        // Keep the locator and imported snapshot when old unowned legacy material is still outstanding.
        boolean keepLocator = p.getProperty("legacy") != null || p.getProperty("legacyRetired") != null;
        if (keepLocator && descendant != null) {
            throw new IOException("Old legacy ownership/lifetime unproven; keep locator " + record);
        }
        try (FileChannel channel = FileChannel.open(old.resolve("live.lock"), StandardOpenOption.WRITE);
                FileLock lock = tryRecoveryLock(channel, old)) {
            p = readRecord(layout, record);
            if (descendant != null) {
                UUID.fromString(descendant);
                if (!"superseded".equals(p.getProperty("phase")) || !descendant.equals(p.getProperty("descendant"))
                        || !"true".equals(p.getProperty("descendantReleased"))
                        || !p.getProperty("descendantSHA256", "").equals(fileHash(Path.of(p.getProperty("output"))))) {
                    throw new IOException("Missing positive descendant receipt");
                }
            } else {
                long at = Long.parseLong(p.getProperty("rejectedAt", "-1"));
                if (!"true".equals(p.getProperty("rejected")) || at < 0
                        || System.currentTimeMillis() - at < 2L * 24 * 60 * 60 * 1000) throw new IOException("Rejection receipt not expired");
            }
            // Independently validate owned stage/identity siblings; arbitrary input/base/source locators are NEVER deletion authority.
            List<Path> siblings = new ArrayList<>();
            String oldId = p.getProperty("id");
            String identityValue = p.getProperty("identityDirectory");
            if (identityValue != null) {
                Path identity = ReplayStorageLayout.canonical(Path.of(identityValue));
                if (Files.exists(identity, LinkOption.NOFOLLOW_LINKS)) {
                    if (!identity.getParent().equals(ReplayStorageLayout.canonical(Path.of(p.getProperty("output"))).getParent())
                            || !identity.getFileName().toString().startsWith(".replaymod-identity-" + oldId + "-")
                            || !readSmall(identity.resolve("owner")).equals("replay-identity-v1\n" + oldId + "\n")) throw new IOException("Unknown old identity owner");
                    siblings.add(identity);
                }
            }
            String stageValue = p.getProperty("stage");
            if (stageValue != null) {
                Path folder = ReplayStorageLayout.canonical(Path.of(stageValue)).getParent();
                if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
                    if (!folder.getParent().equals(ReplayStorageLayout.canonical(Path.of(p.getProperty("stageTarget"))).getParent())
                            || !folder.getFileName().toString().startsWith(".replaymod-publish-" + oldId + "-")
                            || !readSmall(folder.resolve("owner")).equals("replay-stage-v1\n" + oldId + "\n")) throw new IOException("Unknown old stage owner");
                    siblings.add(folder);
                }
            }
            treeEntries(old); // preflight ALL material before deleting any bytes
            for (Path sibling : siblings) treeEntries(sibling);
            for (Path sibling : siblings) deleteOwnedTree(sibling);
            Files.walkFileTree(old, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    ReplayStorageLayout.canonical(dir); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    requireRegular(file);
                    if (!file.equals(old.resolve("live.lock")) && !file.equals(old.resolve("owner"))) Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    if (!dir.equals(old)) Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (keepLocator) {
            // Retain only the small rejection/owner/lock locator for the UNOWNED legacy original.
            // The owned imported/protected copies above were independently covered by explicit rejection.
            p.setProperty("phase", "rejected-pruned");
            p.setProperty("prunedAt", Long.toString(System.currentTimeMillis()));
            writeProperties(record, p);
            System.err.println("Rejected owned copies pruned; legacy original and locator retained: " + record);
            return;
        }
        // No valid record can newly recover a superseded/rejected tree. Keep the record if cleanup fails.
        verifyOwner(old, p.getProperty("id"));
        Files.delete(old.resolve("live.lock")); Files.delete(old.resolve("owner")); Files.delete(old);
        Files.delete(record);
        // Logical input/output/raw and original legacy data were never included in deletion.
    }

    private static FileLock tryRecoveryLock(FileChannel channel, Path old) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Active recovery workspace: " + old);
            return lock;
        } catch (OverlappingFileLockException active) { throw new IOException("Active recovery workspace: " + old, active); }
    }

    private static Properties readRecord(ReplayStorageLayout layout, Path record) throws IOException {
        Path path = ReplayStorageLayout.canonical(record);
        if (!path.getParent().equals(layout.recoveryRoot())) throw new IOException("Record outside fixed recovery index: " + record);
        Properties p = new Properties();
        try (Reader in = new StringReader(readSmall(path))) { p.load(in); }
        String id = p.getProperty("id", "");
        try { UUID.fromString(id); } catch (IllegalArgumentException invalid) { throw new IOException("Invalid recovery id", invalid); }
        if (!"1".equals(p.getProperty("version")) || !path.getFileName().toString().equals(id + ".properties")) throw new IOException("Unknown recovery record: " + path);
        Path workspace = ReplayStorageLayout.canonical(Path.of(p.getProperty("workspace")));
        if (!workspace.getFileName().toString().equals(".replaymod-work-" + id)) throw new IOException("Workspace/record identity mismatch");
        verifyOwner(workspace, id);
        ReplayStorageLayout.canonical(workspace.resolve("live.lock"));
        requireRegular(workspace.resolve("live.lock"));
        return p;
    }

    private static Path recordChild(Properties p, String preferred, String fallback, Path workspace) throws IOException {
        String value = p.getProperty(preferred, p.getProperty(fallback));
        if (value == null) return null;
        Path path = ReplayStorageLayout.canonical(Path.of(value));
        if (!path.startsWith(workspace) || path.equals(workspace)) throw new IOException("Recovery material outside owner: " + path);
        return path;
    }

    private static void verifyOwner(Path directory, String id) throws IOException {
        ReplayStorageLayout.canonical(directory);
        if (!readSmall(directory.resolve("owner")).equals("replay-work-v1\n" + id + "\n")) throw new IOException("Unknown workspace owner: " + directory);
    }

    static String digest(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String fileHash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String readSmall(Path path) throws IOException {
        requireRegular(path);
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("Oversized recovery locator: " + path);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    private static BasicFileAttributes requireRegular(Path file) throws IOException {
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.isSymbolicLink()) throw new IOException("Not a regular replay file: " + file);
        return a;
    }
    private static void requireDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) throw new IOException("Not a real directory: " + directory);
    }
    private static void copyVerified(Path from, Path to) throws IOException {
        ReplayStorageLayout.canonical(from);
        BasicFileAttributes before = requireRegular(from);
        Files.copy(from, to);
        BasicFileAttributes after = requireRegular(from);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()) || Files.mismatch(from, to) != -1) {
            throw new IOException("Source changed/copy mismatch: " + from + "; retained copy=" + to);
        }
    }
    private static void copyTree(Path from, Path to) throws IOException {
        requireDirectory(from);
        ReplayStorageLayout.canonical(from);
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Linked sidecar: " + dir);
                ReplayStorageLayout.canonical(dir);
                Files.createDirectory(to.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                copyVerified(file, to.resolve(from.relativize(file))); return FileVisitResult.CONTINUE;
            }
        });
        verifyTree(from, to);
    }
    private static void verifyTree(Path from, Path to) throws IOException {
        Set<String> source = treeEntries(from), target = treeEntries(to);
        if (!source.equals(target)) throw new IOException("Sidecar entry mismatch: " + from);
        for (String entry : source) if (Files.mismatch(from.resolve(entry), to.resolve(entry)) != -1) throw new IOException("Sidecar bytes changed: " + entry);
    }
    private static Set<String> treeEntries(Path root) throws IOException {
        Set<String> result = new TreeSet<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return result;
        requireDirectory(root);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                ReplayStorageLayout.canonical(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                requireRegular(file); result.add(root.relativize(file).toString().replace('\\', '/')); return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }
    private static void deleteOwnedTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                ReplayStorageLayout.canonical(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                requireRegular(file); Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure; Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Stream all uncompressed bytes and independently check CRC (ZipFile alone does not verify it). */
    static Set<String> validateZip(Path path, boolean requiredEntries) throws IOException {
        requireRegular(path);
        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                safeEntry(name, entry.isDirectory());
                if (!names.add(name)) throw new IOException("Duplicate ZIP entry: " + name);
                CRC32 crc = new CRC32(); long count = 0;
                try (InputStream in = zip.getInputStream(entry)) {
                    for (int n; (n = in.read(buffer)) != -1;) { count += n; crc.update(buffer, 0, n); }
                }
                if (entry.getSize() != count || entry.getCrc() != crc.getValue()) throw new IOException("ZIP CRC/size mismatch: " + name);
            }
            if (requiredEntries && (!names.contains("metaData.json") || !names.contains("recording.tmcpr")
                    || zip.getEntry("metaData.json").getSize() == 0)) throw new IOException("Replay requires metaData.json and recording.tmcpr: " + path);
        }
        return names;
    }

    static void safeEntry(String entry, boolean directory) throws IOException {
        if (entry == null || entry.isEmpty() || entry.indexOf('\\') >= 0 || entry.indexOf(':') >= 0 || entry.startsWith("/")) throw new IOException("Unsafe replay entry: " + entry);
        String name = directory && entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe replay entry: " + entry);
            if (File.separatorChar == '\\' && (part.endsWith(".") || part.endsWith(" ")
                    || part.toUpperCase(Locale.ROOT).matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))) {
                throw new IOException("Unsafe Windows replay entry alias/device: " + entry);
            }
        }
    }
}
