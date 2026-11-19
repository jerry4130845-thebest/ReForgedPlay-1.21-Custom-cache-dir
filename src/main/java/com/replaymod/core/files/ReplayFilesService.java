package com.replaymod.core.files;

import com.replaymod.core.ReplayMod;
import com.replaymod.core.gui.RestoreReplayGui;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.studio.ReplayStudio;
import de.johni0702.minecraft.gui.container.GuiScreen;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class ReplayFilesService {
    private final ReplayFoldersService folders;
    // The monitor makes reservations of input+output and raw cleanup one atomic admission operation.
    private final Set<Path> lockedPaths = new HashSet<>();
    // Strongly retain unknown external-constructor handles/locks until process exit, not GC-as-release.
    private final Map<Path, ReplayWorkspace> unreleasedConstructions = new HashMap<>();

    public ReplayFilesService(ReplayFoldersService folders) { this.folders = folders; }
    public ReplayFile open(Path path) throws IOException { return open(path, path); }
    public ReplayFile open(Path input, Path output) throws IOException {
        return openTask(folders.snapshotStorageLayout(), input, output, false, null, false);
    }
    public ReplayFile open(ReplayStorageLayout layout, Path input, Path output) throws IOException {
        return openTask(layout, input, output, false, null, false);
    }
    /** Naming hint only: new recording publication must still use input=null and no-clobber save. */
    public static Path selectNewRecordingPath(Path requested) throws IOException {
        Path name = requested.getFileName();
        if (name == null || !name.toString().endsWith(".mcpr")) throw new IOException("Expected a replay file name");
        String stem = name.toString().substring(0, name.toString().length() - 5);
        for (int suffix = 0; ; suffix++) {
            Path candidate = suffix == 0 ? requested : requested.resolveSibling(stem + " (" + suffix + ").mcpr");
            Path sidecar = candidate.resolveSibling(candidate.getFileName() + ".tmp");
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return candidate;
            if (suffix == Integer.MAX_VALUE) throw new IOException("No unused recording name: " + requested);
        }
    }

    public ReplayFile openReadOnly(Path path) throws IOException {
        return openTask(folders.snapshotStorageLayout(), path, path, true, null, false);
    }
    public ReplayFile openReadOnly(ReplayStorageLayout layout, Path path) throws IOException {
        return openTask(layout, path, path, true, null, false);
    }
    public ReplayFile openRecovery(Path record) throws IOException {
        return openRecovery(folders.snapshotStorageLayout(), record);
    }
    public ReplayFile openRecovery(ReplayStorageLayout layout, Path record) throws IOException {
        return openTask(layout, null, ReplayWorkspace.recoveryOutput(layout, record), false, record, false);
    }
    public ReplayFile openLegacyRecovery(ReplayStorageLayout layout, Path original, Path output) throws IOException {
        return openTask(layout, original, output, false, null, true);
    }

    private ReplayFile openTask(ReplayStorageLayout layout, Path input, Path output, boolean readOnly,
            Path record, boolean legacy) throws IOException {
        Path realInput = input == null ? null : ReplayStorageLayout.canonical(input);
        Path realOutput = ReplayStorageLayout.canonical(output);
        synchronized (lockedPaths) {
            if (realInput != null && lockedPaths.contains(realInput)) throw new FileLockedException(realInput);
            if (lockedPaths.contains(realOutput)) throw new FileLockedException(realOutput);
            if (realInput != null) lockedPaths.add(realInput);
            lockedPaths.add(realOutput);
        }
        Runnable onClose = () -> { synchronized (lockedPaths) {
            if (realInput != null) lockedPaths.remove(realInput);
            lockedPaths.remove(realOutput);
        } };
        ReplayWorkspace workspace = null;
        ReplayCacheLease cache = null;
        TransactionalReplayFile adapter = null;
        try {
            workspace = record != null ? ReplayWorkspace.recover(layout, record)
                    : legacy ? ReplayWorkspace.importLegacy(layout, realInput, realOutput)
                    : ReplayWorkspace.create(layout, realInput, realOutput, readOnly);
            cache = ReplayCacheLease.acquire(layout, realOutput);
            adapter = new TransactionalReplayFile(new ReplayStudio(), workspace, cache);
            return new ManagedReplayFile(adapter, onClose);
        } catch (IOException | RuntimeException | Error failure) {
            if (adapter != null) {
                adapter.onReleased(onClose);
                try { adapter.close(); } catch (Throwable close) { failure.addSuppressed(close); }
            } else if (cache != null && cache.isQuarantined()) {
                if (workspace != null) {
                    workspace.constructionUnreleased(failure);
                    synchronized (lockedPaths) { unreleasedConstructions.put(realOutput, workspace); }
                }
                // No delegate object was returned, so no actual close receipt can be fabricated.
                // Cache and service/workspace locks remain held; the diagnostic identifies the recovery location.
            } else {
                if (workspace != null) workspace.constructionFailed(failure);
                if (cache != null) try { cache.close(); } catch (Throwable close) { failure.addSuppressed(close); }
                onClose.run();
            }
            throw failure;
        }
    }

    public ReplayStorageLayout snapshotStorageLayout() throws IOException { return folders.snapshotStorageLayout(); }

    public void rejectRecovery(ReplayStorageLayout layout, Path record) throws IOException {
        ReplayWorkspace.rejectRecovery(layout, record);
    }

    /** Rejection never overwrites an existing .del or destroys the only legacy sidecar. */
    public void rejectLegacyRecovery(ReplayStorageLayout layout, Path original, Path output) throws IOException {
        ReplayWorkspace workspace = ReplayWorkspace.importLegacy(layout, original, output);
        try {
            workspace.protect();
            workspace.finish(true);
            ReplayWorkspace.rejectRecovery(layout, workspace.recordPath());
        } catch (IOException | RuntimeException | Error failure) {
            workspace.constructionFailed(failure);
            throw failure;
        }
    }

    public void initialScan(ReplayMod core) {
        final ReplayStorageLayout layout;
        try { layout = folders.snapshotStorageLayout(); }
        catch (IOException failure) { failure.printStackTrace(); return; }
        GuiScreen prompts = GuiScreen.wrap(core.getMinecraft().currentScreen);
        boolean found = false;
        try {
            Set<Path> indexed = ReplayWorkspace.indexedLegacy(layout);
            for (Path record : ReplayWorkspace.scanRecovery(layout)) {
                try {
                    prompts = GuiScreen.wrap(new RestoreReplayGui(core, prompts, record,
                            ReplayWorkspace.recoveryOutput(layout, record), layout).toMinecraft());
                    found = true;
                } catch (IOException uncertain) { System.err.println("Recovery prompt retained/skipped: " + record + ": " + uncertain); }
            }
            // Fixed index plus ONLY these two current, explicit roots; never a recursive disk search.
            Set<Path> roots = new LinkedHashSet<>(Arrays.asList(layout.replayRoot(), layout.recordingRoot()));
            for (Path root : roots) {
                if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
                ReplayStorageLayout.canonical(root);
                try (DirectoryStream<Path> paths = Files.newDirectoryStream(root, "*.mcpr.tmp")) {
                    for (Path sidecar : paths) {
                        try {
                            Path path = ReplayStorageLayout.canonical(sidecar);
                            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || indexed.contains(path)) continue;
                            String name = path.getFileName().toString();
                            Path original = path.resolveSibling(name.substring(0, name.length() - 4));
                            Path output = root.equals(layout.recordingRoot())
                                    ? layout.replayRoot().resolve(original.getFileName()) : original;
                            // .no_recover is not a complete raw publication receipt. Leave it and all data intact.
                            prompts = GuiScreen.wrap(new RestoreReplayGui(core, prompts, original.toFile(), output, layout).toMinecraft());
                            found = true;
                        } catch (IOException | RuntimeException uncertain) {
                            System.err.println("Legacy recovery retained/skipped: " + sidecar + ": " + uncertain);
                        }
                    }
                } catch (IOException uncertain) { System.err.println("Legacy root retained/skipped: " + root + ": " + uncertain); }
            }
        } catch (IOException uncertain) { System.err.println("Recovery index retained/skipped: " + uncertain); }
        // A parent chain avoids several prompts racing to replace the same current screen/state.
        if (found) prompts.display();
        new Thread(() -> cleanup(layout), "replaymod-cleanup").start();
    }

    private void cleanup(ReplayStorageLayout layout) {
        try { ReplayCacheLease.cleanupStale(layout); }
        catch (IOException | RuntimeException failure) { System.err.println("Replay cache cleanup retained: " + failure); }
        try { ReplayWorkspace.cleanupRecovery(layout); }
        catch (IOException | RuntimeException failure) { System.err.println("Recovery cleanup retained: " + failure); }
        // Pure JDK owner/lock cleanup only; never initialize LWJGL during startup scanning.
        try { com.replaymod.render.utils.LwjglNativeWorkspace.cleanupStale(layout.tempRoot()); }
        catch (IOException | RuntimeException failure) { System.err.println("EXR native cleanup retained: " + failure); }
        // A disabled raw backup must not even probe the old raw volume or call getRawReplayFolder.
        if (!layout.rawBackupEnabled()) return;
        try {
            Path root = ReplayStorageLayout.canonical(layout.rawRoot());
            for (Path protectedRoot : new Path[] { layout.replayRoot(), layout.recordingRoot(), layout.recoveryRoot(),
                    layout.tmpRoot(), layout.cacheRoot(), layout.tempRoot() }) {
                if (root.equals(protectedRoot)) {
                    System.err.println("Raw retention skipped: raw root is also a protected storage root: " + root);
                    return;
                }
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(root)) {
                for (Path path : paths) try { cleanupRaw(layout, path); }
                catch (IOException | RuntimeException uncertain) { System.err.println("Raw retained/skipped: " + path + ": " + uncertain); }
            }
        } catch (IOException | RuntimeException uncertain) { System.err.println("Raw cleanup retained/skipped: " + uncertain); }
        // Unknown .del/.no_recover/legacy cache/native trees carry no deletion authority.
    }

    private void cleanupRaw(ReplayStorageLayout layout, Path path) throws IOException {
        if (!path.getFileName().toString().endsWith(".mcpr")) return;
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()
                || System.currentTimeMillis() - attrs.lastModifiedTime().toMillis() < 21L * 24 * 60 * 60 * 1000) return;
        Path identity = ReplayStorageLayout.canonical(path);
        synchronized (lockedPaths) {
            if (lockedPaths.contains(identity)) return;
            Path lockPath = ReplayWorkspace.identityLockPath(layout, identity);
            Files.createDirectories(lockPath.getParent());
            ReplayStorageLayout.canonical(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock;
                try { lock = channel.tryLock(); } catch (OverlappingFileLockException active) { return; }
                if (lock == null) return;
                try (lock) {
                    BasicFileAttributes current = Files.readAttributes(identity, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (current.isRegularFile() && !current.isSymbolicLink() && !current.isOther()
                            && attrs.size() == current.size() && Objects.equals(attrs.fileKey(), current.fileKey())
                            && attrs.lastModifiedTime().equals(current.lastModifiedTime())) Files.delete(identity);
                }
            }
        }
    }

    public static class FileLockedException extends IOException {
        public FileLockedException(Path path) { super(path.toString()); }
    }
}
