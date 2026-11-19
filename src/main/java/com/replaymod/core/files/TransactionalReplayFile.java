package com.replaymod.core.files;

import com.replaymod.replaystudio.Studio;
import com.replaymod.replaystudio.lib.guava.base.Optional;
import com.replaymod.replaystudio.replay.AbstractReplayFile;
import com.replaymod.replaystudio.replay.ZipReplayFile;

import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.regex.Pattern;

/** All inherited high-level APIs use these tracked basics. save never closes/reopens the private delegate. */
public final class TransactionalReplayFile extends AbstractReplayFile {
    private final ReplayWorkspace workspace;
    private final ReplayCacheLease cacheLease;
    private final ZipReplayFile delegate;
    private final Map<String, Integer> readers = new HashMap<>();
    private final Set<String> writers = new HashSet<>();
    private int streams;
    private boolean dirty, saveFailed, closing, delegateClosed, workspaceFinished, released, cacheMutation;
    private IOException writerFailure;
    private final List<Runnable> releaseCallbacks = new ArrayList<>();

    public TransactionalReplayFile(Studio studio, ReplayWorkspace workspace,
            ReplayCacheLease cacheLease) throws IOException {
        super(studio);
        this.workspace = Objects.requireNonNull(workspace);
        this.cacheLease = Objects.requireNonNull(cacheLease);
        this.dirty = workspace.imported() && !workspace.readOnly();
        cacheLease.beginMutation();
        try {
            this.delegate = new ZipReplayFile(studio, workspace.base() == null ? null : workspace.base().toFile(),
                    workspace.physicalOutput().toFile(), cacheLease.path().toFile());
            cacheLease.endMutation();
        } catch (IOException | RuntimeException | Error failure) {
            // A throwing external constructor supplies no object on which to confirm actual close.
            cacheLease.quarantine(failure);
            throw failure;
        }
    }

    synchronized void onReleased(Runnable callback) {
        if (released) callback.run(); else releaseCallbacks.add(Objects.requireNonNull(callback));
    }

    /** A close request with live streams is not an actual release receipt. */
    public synchronized boolean isReleased() { return released; }
    Path recoveryRecord() { return workspace.recordPath(); }

    private void admit() throws IOException {
        if (closing || released) throw new IOException("Replay is closing/closed; recovery=" + workspace.recordPath());
    }
    private void mutable() throws IOException {
        admit();
        if (workspace.readOnly()) throw new IOException("Read-only replay: " + workspace.logicalOutput());
    }
    private void entry(String name) throws IOException { ReplayWorkspace.safeEntry(name, false); }
    private String key(String name, boolean cache) {
        return (cache ? "cache:" : "data:") + (File.separatorChar == '\\' ? name.toLowerCase(Locale.ROOT) : name);
    }
    private void noCaseAlias(String name) throws IOException {
        if (File.separatorChar == '\\') for (String existing : workspace.entries()) {
            if (!existing.equals(name) && existing.equalsIgnoreCase(name)) throw new IOException("Case-aliased replay mutation is unsafe on this filesystem: " + name);
        }
    }

    @Override public synchronized Optional<InputStream> get(String entry) throws IOException { return read(entry, false); }
    @Override public synchronized Optional<InputStream> getCache(String entry) throws IOException { return read(entry, true); }

    private Optional<InputStream> read(String name, boolean cache) throws IOException {
        admit(); ReplayWorkspace.safeEntry(name, name != null && name.endsWith("/"));
        if (cache && cacheMutation) throw new IOException("Cache invalidation is in progress");
        String key = key(name, cache);
        if (writers.contains(key)) throw new IOException("Entry still has a writer: " + name);
        Optional<InputStream> input = cache ? delegate.getCache(name) : delegate.get(name);
        if (!input.isPresent()) return Optional.absent();
        InputStream real = input.get();
        readers.merge(key, 1, Integer::sum); streams++;
        return Optional.of(new InputStream() {
            private boolean closed;
            private void check() throws IOException { if (closed) throw new IOException("Replay stream closed"); }
            @Override public int read() throws IOException { synchronized (TransactionalReplayFile.this) { check(); return real.read(); } }
            @Override public int read(byte[] b, int off, int len) throws IOException { synchronized (TransactionalReplayFile.this) { check(); return real.read(b, off, len); } }
            @Override public long skip(long n) throws IOException { synchronized (TransactionalReplayFile.this) { check(); return real.skip(n); } }
            @Override public int available() throws IOException { synchronized (TransactionalReplayFile.this) { check(); return real.available(); } }
            @Override public void close() throws IOException {
                synchronized (TransactionalReplayFile.this) {
                    if (closed) return;
                    // Failure does not decrement usage; caller may retry the actual close.
                    real.close(); closed = true;
                    readers.compute(key, (k, count) -> count == 1 ? null : count - 1); streams--;
                    finishClose();
                }
            }
        });
    }

    @Override public synchronized Map<String, InputStream> getAll(Pattern pattern) throws IOException {
        admit(); Objects.requireNonNull(pattern);
        Map<String, InputStream> result = new LinkedHashMap<>();
        try {
            for (String name : workspace.entries()) {
                if (pattern.matcher(name).matches()) {
                    Optional<InputStream> in = read(name, false);
                    if (in.isPresent()) {
                        InputStream stream = in.get();
                        try { result.put(name, stream); }
                        catch (RuntimeException | Error failure) {
                            try { stream.close(); } catch (Throwable close) { failure.addSuppressed(close); }
                            throw failure;
                        }
                    }
                }
            }
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            for (InputStream in : result.values()) try { in.close(); } catch (Throwable close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    @Override public synchronized OutputStream write(String entry) throws IOException { return write(entry, false); }
    @Override public synchronized OutputStream writeCache(String entry) throws IOException { return write(entry, true); }

    private OutputStream write(String name, boolean cache) throws IOException {
        if (cache) admit(); else mutable();
        entry(name);
        if (!cache) noCaseAlias(name);
        String key = key(name, cache);
        // Real ZipReplayFile internally rewrites both CRC entries when recording.tmcpr opens/closes.
        // Reserve them for the entire packet-writer lifetime, including its close callback.
        List<String> writeKeys = !cache && name.equals("recording.tmcpr")
                ? List.of(key, key("recording.tmcpr.crc32", false), key("recording.tmcpr.crc32", true)) : List.of(key);
        for (String affected : writeKeys) if (writers.contains(affected) || readers.containsKey(affected)) {
            throw new IOException("Entry in use; refusing destructive rewrite: " + affected);
        }
        if (cache && cacheMutation) throw new IOException("Cache invalidation is in progress");
        boolean packet = !cache && name.equals("recording.tmcpr");
        if (packet) {
            if (readers.keySet().stream().anyMatch(k -> k.startsWith("cache:"))
                    || writers.stream().anyMatch(k -> k.startsWith("cache:"))) throw new IOException("Cache streams still active; packet rewrite refused");
            cacheLease.beginMutation(); cacheMutation = true;
        }
        try { if (!cache) { workspace.markDirty(); dirty = true; } }
        catch (IOException | RuntimeException | Error failure) {
            if (packet) { cacheLease.endMutation(); cacheMutation = false; }
            throw failure;
        }
        OutputStream real;
        try { real = cache ? delegate.writeCache(name) : delegate.write(name); }
        catch (IOException | RuntimeException | Error failure) { failedWriter(failure); throw failure; }
        streams++; writers.addAll(writeKeys);
        return new OutputStream() {
            private boolean closed;
            private void check() throws IOException { if (closed) throw new IOException("Replay writer closed"); }
            @Override public void write(int b) throws IOException {
                synchronized (TransactionalReplayFile.this) {
                    check(); try { real.write(b); } catch (IOException | RuntimeException | Error failure) { failedWriter(failure); throw failure; }
                }
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                synchronized (TransactionalReplayFile.this) {
                    check(); try { real.write(b, off, len); } catch (IOException | RuntimeException | Error failure) { failedWriter(failure); throw failure; }
                }
            }
            @Override public void flush() throws IOException {
                synchronized (TransactionalReplayFile.this) {
                    check(); try { real.flush(); } catch (IOException | RuntimeException | Error failure) { failedWriter(failure); throw failure; }
                }
            }
            @Override public void close() throws IOException {
                synchronized (TransactionalReplayFile.this) {
                    if (closed) return;
                    try { real.close(); } catch (IOException | RuntimeException | Error failure) { failedWriter(failure); throw failure; }
                    closed = true; writers.removeAll(writeKeys); streams--;
                    if (packet) { cacheLease.endMutation(); cacheMutation = false; }
                    finishClose();
                }
            }
        };
    }

    private void failedWriter(Throwable failure) {
        if (writerFailure == null) writerFailure = new IOException("Replay writer failed; save refused; recovery=" + workspace.recordPath(), failure);
    }

    @Override public synchronized void remove(String entry) throws IOException { remove(entry, false); }
    @Override public synchronized void removeCache(String entry) throws IOException { remove(entry, true); }
    private void remove(String name, boolean cache) throws IOException {
        if (cache) admit(); else mutable();
        entry(name); if (!cache) noCaseAlias(name); String key = key(name, cache);
        if (cache && cacheMutation) throw new IOException("Cache invalidation is in progress");
        if (readers.containsKey(key) || writers.contains(key)) throw new IOException("Entry in use; refusing removal: " + name);
        if (!cache) { workspace.markDirty(); dirty = true; }
        try { if (cache) delegate.removeCache(name); else delegate.remove(name); }
        catch (IOException | RuntimeException | Error failure) { failedWriter(failure); throw failure; }
    }

    @Override public synchronized void save() throws IOException {
        mutable();
        publish(workspace.logicalOutput(), false);
        dirty = false; saveFailed = false;
    }

    @Override public synchronized void saveTo(File target) throws IOException {
        mutable();
        publish(target.toPath(), true);
        // Export does not change the original save target, dirty state or a previous save failure.
    }

    private void publish(Path target, boolean export) throws IOException {
        // In particular, do not let ZipReplayFile.saveTo silently close a live ReplayOutputStream.
        if (!writers.isEmpty()) throw new IOException("Replay has active writers; publication refused: " + writers);
        if (writerFailure != null) throw new IOException("Replay has a failed writer; publication refused", writerFailure);
        try {
            if (getMetaData() == null) throw new IOException("Replay metadata is null; publication refused");
            Path staged = workspace.prepareStage(target);
            delegate.saveTo(staged.toFile());
            workspace.publish(target, export);
        } catch (IOException | RuntimeException | Error failure) {
            saveFailed = true; workspace.saveFailed(failure);
            if (failure instanceof IOException io) throw new IOException("Replay publication failed; target=" + target + "; " + workspace.describe(), io);
            throw failure;
        }
    }

    /**
     * Narrow recovery operation: raw packet framing is independent of protocol parsing/minimal mode.
     * Read/spool/close FIRST, then overwrite. Memory is fixed-size even for corrupt length fields.
     * The caller preserves the established save -> duration repair -> save -> actual close sequence.
     */
    public synchronized int recoverPacketData() throws IOException {
        mutable();
        if (!writers.isEmpty() || streams != 0) throw new IOException("Recovery requires exclusive, released streams");
        Path spool = workspace.packetRecoveryFile();
        long complete = 0;
        int duration = 0;
        boolean truncated = false;
        try {
            Optional<InputStream> packets = get("recording.tmcpr");
            if (!packets.isPresent()) throw new IOException("Missing recording.tmcpr; " + workspace.describe());
            try (InputStream in = new BufferedInputStream(packets.get());
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(spool)))) {
                byte[] buffer = new byte[65536];
                while (true) {
                    byte[] header = in.readNBytes(8);
                    if (header.length == 0) break;
                    if (header.length != 8) { truncated = true; break; }
                    DataInputStream fields = new DataInputStream(new ByteArrayInputStream(header));
                    int time = fields.readInt(), length = fields.readInt();
                    if (time < 0 || length < 0) throw new IOException("Illegal packet framing; original preserved; " + workspace.describe());
                    out.write(header);
                    int remaining = length;
                    while (remaining != 0) {
                        int n = in.read(buffer, 0, Math.min(remaining, buffer.length));
                        if (n == -1) { truncated = true; break; }
                        if (n == 0) throw new IOException("Packet input made no progress");
                        out.write(buffer, 0, n); remaining -= n;
                    }
                    if (truncated) break;
                    complete += 8L + length;
                    duration = Math.max(duration, time);
                }
            } // Real old packet reader is closed before acquiring the same-entry writer.
            try (FileChannel file = FileChannel.open(spool, StandardOpenOption.WRITE)) {
                file.truncate(complete); file.force(true);
            }
            // If the subsequent real write fails, close may only destroy the live sidecar AFTER this backup.
            workspace.protect();
            try (InputStream in = Files.newInputStream(spool); OutputStream out = write("recording.tmcpr")) {
                in.transferTo(out);
            }
            if (truncated) System.err.println("Dropped incomplete trailing packet; original checkpoint retained until save: " + workspace.describe());
            try { Files.delete(spool); }
            catch (IOException cleanup) { System.err.println("Packet recovery complete; spool retained: " + spool + ": " + cleanup); }
            return duration;
        } catch (IOException | RuntimeException | Error failure) {
            recoveryFailed(failure);
            System.err.println("Packet repair failed; spool=" + spool + "; " + workspace.describe());
            throw failure;
        }
    }

    synchronized void recoveryFailed(Throwable failure) {
        if (workspaceFinished || released) return;
        saveFailed = true;
        workspace.saveFailed(failure);
    }

    @Override public synchronized void close() throws IOException {
        closing = true;
        finishClose();
    }

    private void finishClose() throws IOException {
        if (!closing || released || streams != 0) return;
        boolean retain = dirty || saveFailed || writerFailure != null;
        try {
            if (!delegateClosed) {
                if (retain) workspace.protect();
                // Only after every real stream closed, and independent recovery protection succeeded.
                delegate.close();
                delegateClosed = true;
                if (cacheMutation) { cacheLease.endMutation(); cacheMutation = false; }
            }
            if (!workspaceFinished) { workspace.finish(retain); workspaceFinished = true; }
            cacheLease.close();
            released = true;
            List<Runnable> callbacks = new ArrayList<>(releaseCallbacks);
            releaseCallbacks.clear();
            for (Runnable callback : callbacks) callback.run();
        } catch (IOException failure) {
            throw new IOException("Replay actual close incomplete; retry close; recovery=" + workspace.recordPath(), failure);
        }
    }
}
