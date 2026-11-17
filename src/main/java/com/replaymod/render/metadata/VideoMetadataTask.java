package com.replaymod.render.metadata;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.MovieBox;
import com.coremedia.iso.boxes.TrackBox;
import com.coremedia.iso.boxes.UserBox;
import com.googlecode.mp4parser.MemoryDataSourceImpl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Project-owned, single-purpose video transaction API (not an mp4parser API).
 * Input must be a closed, exclusively owned ordinary video in a trusted parent.
 * Supported input: tail moov, one track, nonfragmented subset only; bounded moov
 * via MemoryDataSourceImpl, never a mapped/full-video IsoFile. Preserve all prefix
 * bytes/media offsets; verify candidate, target-volume stage and fresh original
 * before one ATOMIC_MOVE. No truncate/delete/non-atomic replacement of the input.
 * A move return alone is not success: failed postcommit verification is INDETERMINATE.
 */
public final class VideoMetadataTask {
    public static final int MAX_MOOV_BYTES = 16 * 1024 * 1024;
    private VideoMetadataTask() { }

    public enum Status { APPLIED, ALREADY_PRESENT, UNCHANGED, INDETERMINATE }

    /**
     * Success is a verified receipt, never a request flag or an optimistic log.
     * UNCHANGED requires fresh original attributes/digest evidence; otherwise use
     * INDETERMINATE. tempRoot is the actual root when resolved, or the selected
     * snapshot before allocation (null is the default-selection marker). Candidate
     * and stage are payload paths, null before creation. Cleanup diagnostics must
     * include retained owned directories/witness paths and any cleanup failures.
     * A cleanup warning does not revoke already verified APPLIED metadata.
     */
    public record Result(Status status, String phase, Throwable cause, Path source,
                         Path tempRoot, Path candidate, Path stage,
                         List<String> cleanupDiagnostics) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(source, "source");
            cleanupDiagnostics = List.copyOf(cleanupDiagnostics);
        }
        public boolean metadataPresent() {
            return status == Status.APPLIED || status == Status.ALREADY_PRESENT;
        }
        public IOException asIOException() {
            return new IOException("Spherical metadata is not confirmed: " + this, cause);
        }
    }

    /**
     * New project API. selectedTempRoot is a single immutable settings snapshot;
     * null selects the JDK default overload, not a global tmpdir mutation. userType
     * must be the 16-byte spherical UUID, xmlUtf8 contains ONLY UTF-8 XML bytes.
     * Self-invocations are serialized; successful creations are ledgered immediately.
     * Handles are closed before verification/publication; uncertain IO retains materials.
     * Unsupported layouts may yield UNCHANGED only after fresh source verification.
     * Expected transaction failures belong in Result; preflight/setup may throw.
     */
    public static synchronized Result apply(Path source, Path selectedTempRoot,
                                             byte[] userType, byte[] xmlUtf8) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(userType, "userType");
        Objects.requireNonNull(xmlUtf8, "xmlUtf8");
        if (userType.length != 16) throw new IllegalArgumentException("Spherical UUID must be 16 bytes");
        if (xmlUtf8.length == 0 || xmlUtf8.length > 65536)
            throw new IllegalArgumentException("XML must contain 1..65536 UTF-8 bytes");
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(xmlUtf8));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Malformed UTF-8 metadata", e);
        }
        byte[] uuid = userType.clone(), xml = xmlUtf8.clone();
        Path absolute = source.toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(absolute.getParent(), "source parent").toRealPath();
        Path target = parent.resolve(absolute.getFileName());
        // No receipt without an initial stable, complete source fingerprint.
        Fingerprint original = fingerprint(target);
        Ledger ledger = new Ledger();
        Path root = selectedTempRoot, candidate = null, stage = null;
        String phase = "inspect-source";
        boolean handlesClosed = true, candidateComplete = false, stageComplete = false;
        boolean moveAttempted = false;
        try {
            Layout input = inspect(target, uuid, xml);
            // The original moov is independently validated before the lazy parser sees it.
            byte[] replacement = rewrite(input.moovBytes, input.metadataCount == 1, uuid, xml);
            if (input.metadataCount == 1) {
                requireSame(original, fingerprint(target));
                return new Result(Status.ALREADY_PRESENT, "verified-existing", null, target,
                        root, null, null, List.of());
            }
            phase = "allocate-candidate";
            Path task;
            if (selectedTempRoot == null) {
                // Deliberately use the JDK default-selection overload, without global mutation.
                task = ledger.directory(null);
                root = task.getParent().toRealPath();
            } else {
                Path selected = selectedTempRoot.toAbsolutePath().normalize();
                Files.createDirectories(selected);
                root = selected.toRealPath();
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Selected temp root is not a directory: " + root);
                task = ledger.directory(root);
            }
            ledger.anchor(task);
            phase = "write-candidate";
            Path next = task.resolve("candidate.mp4");
            handlesClosed = false;
            try (FileChannel in = openRead(target);
                 FileChannel out = ledger.payload(next)) {
                candidate = next; // only after successful CREATE_NEW (ledger retains early failures)
                transfer(in, out, input.moov.start);
                writeFully(out, ByteBuffer.wrap(replacement));
            } catch (IOException | RuntimeException e) {
                // A close exception is not distinguishable from an IO-body error here. Retain.
                throw e;
            }
            handlesClosed = true;
            phase = "verify-candidate";
            verifyOutput(candidate, target, input, replacement, uuid, xml);
            Fingerprint newVersion = fingerprint(candidate);
            candidateComplete = true;
            // Permit the large arrays/parser graph to die before stage verification.
            input = input.withoutBytes();
            replacement = null;

            phase = "allocate-stage";
            Path stageDirectory = ledger.directory(parent);
            ledger.anchor(stageDirectory);
            Path nextStage = stageDirectory.resolve("payload.mp4");
            phase = "copy-stage";
            handlesClosed = false;
            try (FileChannel in = openRead(candidate);
                 FileChannel out = ledger.payload(nextStage)) {
                stage = nextStage;
                transfer(in, out, newVersion.size);
            }
            handlesClosed = true;
            phase = "verify-stage";
            if (mismatch(candidate, stage) != -1) throw new IOException("Candidate/stage mismatch");
            requireContent(newVersion, fingerprint(stage));
            requireOutputLayout(stage, input, uuid, xml);
            stageComplete = true;
            phase = "fresh-original";
            ledger.requireIdentity(candidate);
            ledger.requireIdentity(stage);
            requireContent(newVersion, fingerprint(stage));
            // Last IO check before publishing: fresh complete original identity/content
            // evidence, still NOT an atomic check-and-replace or cross-process CAS.
            requireSame(original, fingerprint(target));
            phase = "atomic-move";
            moveAttempted = true;
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            phase = "postcommit-verify";
            requireContent(newVersion, fingerprint(target));
            requireOutputLayout(target, input, uuid, xml);
            ledger.moved(stage, target);
            List<String> diagnostics = ledger.cleanup(true);
            return new Result(Status.APPLIED, "postcommit-verified", null, target, root,
                    candidate, stage, diagnostics);
        } catch (IOException | RuntimeException failure) {
            Status status = Status.INDETERMINATE;
            try {
                requireSame(original, fingerprint(target));
                status = Status.UNCHANGED;
            } catch (IOException | RuntimeException uncertain) {
                if (failure != uncertain) failure.addSuppressed(uncertain);
            }
            // Even if move happened to install the new bytes, an exceptional move is NOT success.
            // Never reverse-overwrite, retry, or delete a complete new version on this path.
            if (candidate == null) candidate = ledger.createdPayload("candidate.mp4");
            if (stage == null) stage = ledger.createdPayload("payload.mp4");
            boolean disposableParts = status == Status.UNCHANGED && handlesClosed && !ledger.closeUncertain
                    && !candidateComplete && !stageComplete && !moveAttempted;
            List<String> diagnostics = ledger.cleanup(disposableParts);
            return new Result(status, phase, failure, target, root, candidate, stage, diagnostics);
        }
    }

    /* This is a bounded tail-moov editor, not a remuxer, durability protocol or
       security sandbox. Trusted, non-hostile parents and exclusive closed input
       remain required. Null fileKey is usable; without a key/witness the ledger
       retains uncertain objects. Hardlink/marker anchors aid ownership checks but
       do NOT prove a directory inode or close check->move/delete race windows.
       No cross-process CAS, hostile ancestor replacement, fsync-directory or
       power-loss recovery guarantee is made. There is no original-file hardlink
       witness: with a null original key, equal size/mtime/SHA-256 prove integrity,
       NOT object identity; same-content/same-timestamp replacement is unobservable
       and still depends on the exclusive-input contract. */
    private static final int BUFFER_BYTES = 256 * 1024;
    private static final int MAX_BOXES = 8192;
    private static final int MAX_ENTRIES = 1000000;
    private static final int MAX_ZERO_PROGRESS = 16;

    private record Fingerprint(long size, FileTime modified, Object key, byte[] digest) { }

    private static BasicFileAttributes attributes(Path p) throws IOException {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.isSymbolicLink()) throw new IOException("Not an ordinary file: " + p);
        return a;
    }

    private static Fingerprint fingerprint(Path p) throws IOException {
        BasicFileAttributes before = attributes(p);
        MessageDigest digest = sha256();
        long count = 0;
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
        try (FileChannel in = openRead(p)) {
            int zero = 0;
            for (;;) {
                buffer.clear();
                int n = in.read(buffer);
                if (n < 0) break;
                if (n == 0) {
                    if (++zero > MAX_ZERO_PROGRESS) throw new IOException("No read progress: " + p);
                    continue;
                }
                zero = 0;
                count = Math.addExact(count, n);
                buffer.flip();
                digest.update(buffer);
            }
        }
        BasicFileAttributes after = attributes(p);
        if (count != before.size() || !sameAttributes(before, after))
            throw new IOException("Source changed while fingerprinting: " + p);
        return new Fingerprint(count, after.lastModifiedTime(), after.fileKey(), digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static boolean sameAttributes(BasicFileAttributes a, BasicFileAttributes b) {
        return a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
                && Objects.equals(a.fileKey(), b.fileKey()) && a.isRegularFile() == b.isRegularFile();
    }

    private static void requireSame(Fingerprint expected, Fingerprint actual) throws IOException {
        requireContent(expected, actual);
        if (!expected.modified.equals(actual.modified) || !Objects.equals(expected.key, actual.key))
            throw new IOException("Original attributes/available fileKey changed; exclusive-input contract violated");
    }

    private static void requireContent(Fingerprint expected, Fingerprint actual) throws IOException {
        if (expected.size != actual.size || !MessageDigest.isEqual(expected.digest, actual.digest))
            throw new IOException("Full-file length/SHA-256 mismatch");
    }

    private static FileChannel openRead(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    private static void readFully(FileChannel in, ByteBuffer bytes) throws IOException {
        int zero = 0;
        while (bytes.hasRemaining()) {
            int n = in.read(bytes);
            if (n < 0) throw new IOException("Unexpected EOF");
            if (n == 0) {
                if (++zero > MAX_ZERO_PROGRESS) throw new IOException("No read progress");
            } else zero = 0;
        }
    }

    private static void writeFully(WritableByteChannel out, ByteBuffer bytes) throws IOException {
        int zero = 0;
        while (bytes.hasRemaining()) {
            int n = out.write(bytes);
            if (n < 0) throw new IOException("Negative write result");
            if (n == 0) {
                if (++zero > MAX_ZERO_PROGRESS) throw new IOException("No write progress");
            } else zero = 0;
        }
    }

    private static void transfer(FileChannel in, WritableByteChannel out, long length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
        long remaining = length;
        while (remaining > 0) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
            readFully(in, buffer);
            buffer.flip();
            writeFully(out, buffer);
            remaining -= buffer.limit();
        }
    }

    // Unlike Files.mismatch this loop explicitly bounds repeated zero-progress reads.
    private static long mismatch(Path first, Path second) throws IOException {
        long length = Files.size(first);
        if (length != Files.size(second)) return Math.min(length, Files.size(second));
        return comparePrefix(first, second, length);
    }

    private static long comparePrefix(Path first, Path second, long length) throws IOException {
        ByteBuffer a = ByteBuffer.allocate(BUFFER_BYTES), b = ByteBuffer.allocate(BUFFER_BYTES);
        try (FileChannel x = openRead(first); FileChannel y = openRead(second)) {
            long offset = 0;
            while (offset < length) {
                int n = (int) Math.min(BUFFER_BYTES, length - offset);
                a.clear().limit(n); b.clear().limit(n);
                readFully(x, a); readFully(y, b);
                a.flip(); b.flip();
                int mismatch = a.mismatch(b);
                if (mismatch >= 0) return offset + mismatch;
                offset += n;
            }
        }
        return -1;
    }

    private record Extent(long start, long size, int header, String type) {
        long end() { return start + size; } // constructed only after checked subtraction bounds
        long content() { return start + header; }
    }

    private record Layout(long length, Extent moov, List<Extent> media, byte[] moovBytes,
                          int metadataCount) {
        Layout withoutBytes() { return new Layout(length, moov, media, null, metadataCount); }
    }

    private static IOException unsupported(String why) {
        return new IOException("Unsupported or invalid single-track tail-moov MP4: " + why);
    }

    private static Layout inspect(Path path, byte[] uuid, byte[] xml) throws IOException {
        long length = attributes(path).size();
        List<Extent> media = new ArrayList<>();
        Extent moov = null;
        int count = 0, ftyp = 0;
        byte[] moovBytes;
        try (FileChannel in = openRead(path)) {
            ByteBuffer header = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
            long position = 0;
            while (position < length) {
                if (++count > MAX_BOXES) throw unsupported("top-level box count limit");
                if (length - position < 8) throw unsupported("truncated top-level header");
                header.clear().limit(8);
                readFully(in, header); header.flip();
                long size = Integer.toUnsignedLong(header.getInt());
                String type = fourCC(header.getInt());
                int headerSize = 8;
                if (size == 1) {
                    if (length - position < 16) throw unsupported("truncated extended header");
                    header.clear().limit(8); readFully(in, header); header.flip();
                    size = header.getLong(); headerSize = 16;
                }
                if (size == 0 || size < headerSize || size > length - position)
                    throw unsupported("box size/overflow/EOF at " + position);
                if (type.equals("uuid")) {
                    headerSize += 16;
                    if (size < headerSize) throw unsupported("truncated UUID header");
                }
                Extent box = new Extent(position, size, headerSize, type);
                switch (type) {
                    case "ftyp" -> {
                        if (++ftyp != 1 || position != 0 || size - headerSize < 8
                                || (size - headerSize) % 4 != 0) throw unsupported("ftyp layout");
                    }
                    case "mdat" -> {
                        if (size <= headerSize) throw unsupported("empty mdat");
                        media.add(box);
                    }
                    case "moov" -> {
                        if (moov != null || box.end() != length) throw unsupported("multiple/front moov");
                        if (size > MAX_MOOV_BYTES) throw unsupported("moov exceeds 16MiB budget");
                        moov = box;
                    }
                    case "free", "skip", "wide" -> { }
                    default -> throw unsupported("top-level " + type + " (including fragments) not supported");
                }
                position = box.end();
                in.position(position);
            }
            if (ftyp != 1 || moov == null || media.isEmpty()) throw unsupported("missing ftyp/moov/mdat");
            moovBytes = new byte[(int) moov.size];
            in.position(moov.start);
            readFully(in, ByteBuffer.wrap(moovBytes));
            if (in.size() != length) throw unsupported("file size changed during scan");
        }
        Validator validator = new Validator(moovBytes, media, uuid, xml);
        validator.validate();
        return new Layout(length, moov, List.copyOf(media), moovBytes, validator.metadataCount);
    }

    private static String fourCC(int n) {
        return new String(new byte[]{(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n},
                StandardCharsets.ISO_8859_1);
    }

    private static byte[] rewrite(byte[] bytes, boolean already, byte[] uuid, byte[] xml) throws IOException {
        // At most input+output moov arrays (~32MiB), bounded XML and <=8192 box nodes.
        // Parser internals/VM object overhead are not a hard process heap quota (target <=64MiB).
        try (MemoryDataSourceImpl data = new MemoryDataSourceImpl(bytes);
             IsoFile iso = new IsoFile(data)) {
            List<Box> boxes = iso.getBoxes();
            if (boxes.size() != 1 || !(boxes.get(0) instanceof MovieBox movie))
                throw unsupported("parser did not retain exactly one moov");
            ComparingChannel roundtrip = new ComparingChannel(bytes);
            iso.getBox(roundtrip);
            roundtrip.finish();
            if (already) return null;
            TrackBox track = null;
            for (Box box : movie.getBoxes()) if (box instanceof TrackBox t) {
                if (track != null) throw unsupported("parser track count");
                track = t;
            }
            if (track == null) throw unsupported("parser missing trak");
            UserBox metadata = new UserBox(uuid);
            metadata.setData(xml);
            track.addBox(metadata);
            long size = iso.getSize();
            if (size != (long) bytes.length + 24 + xml.length || size > MAX_MOOV_BYTES)
                throw unsupported("new moov size/16MiB budget");
            ArrayChannel output = new ArrayChannel((int) size);
            iso.getBox(output);
            byte[] rewritten = output.finish();
            verifyMoovDelta(bytes, rewritten, uuid, xml);
            return rewritten;
        }
    }

    private static void verifyMoovDelta(byte[] before, byte[] after, byte[] uuid, byte[] xml) throws IOException {
        ByteBuffer old = ByteBuffer.wrap(before), next = ByteBuffer.wrap(after);
        int movieHeader = old.getInt(0) == 1 ? 16 : 8;
        int trackStart = -1, trackEnd = -1;
        for (int p = movieHeader; p < before.length;) {
            int size = old.getInt(p) == 1 ? Math.toIntExact(old.getLong(p + 8)) : old.getInt(p);
            if (old.getInt(p + 4) == 0x7472616b) { trackStart = p; trackEnd = p + size; }
            p += size;
        }
        if (trackStart < 0) throw unsupported("missing track for delta proof");
        int delta = 24 + xml.length;
        int trackHeader = old.getInt(trackStart) == 1 ? 16 : 8;
        int movieSizeStart = movieHeader == 16 ? 8 : 0;
        int trackSizeStart = trackStart + (trackHeader == 16 ? 8 : 0);
        long movieSize = before.length + (long) delta;
        long trackSize = trackEnd - (long) trackStart + delta;
        for (int i = 0; i < before.length; i++) {
            byte expected = before[i];
            if (i >= movieSizeStart && i < movieSizeStart + (movieHeader == 16 ? 8 : 4)) {
                int shift = (movieSizeStart + (movieHeader == 16 ? 8 : 4) - i - 1) * 8;
                expected = (byte) (movieSize >>> shift);
            }
            if (i >= trackSizeStart && i < trackSizeStart + (trackHeader == 16 ? 8 : 4)) {
                int shift = (trackSizeStart + (trackHeader == 16 ? 8 : 4) - i - 1) * 8;
                expected = (byte) (trackSize >>> shift);
            }
            if (after[i < trackEnd ? i : i + delta] != expected)
                throw unsupported("rewrite changes unrelated moov/sample table bytes");
        }
        if (next.getInt(trackEnd) != delta || next.getInt(trackEnd + 4) != 0x75756964)
            throw unsupported("new UUID serialization size/type");
        for (int i = 0; i < 16; i++) if (after[trackEnd + 8 + i] != uuid[i])
            throw unsupported("new UUID userType");
        for (int i = 0; i < xml.length; i++) if (after[trackEnd + 24 + i] != xml[i])
            throw unsupported("new UUID XML-only data");
    }

    private static final class ComparingChannel implements WritableByteChannel {
        private final byte[] expected;
        private int position;
        ComparingChannel(byte[] expected) { this.expected = expected; }
        public int write(ByteBuffer src) throws IOException {
            int n = src.remaining();
            if (n > expected.length - position) throw unsupported("moov roundtrip grows");
            while (src.hasRemaining()) if (src.get() != expected[position++])
                throw unsupported("moov roundtrip changes original bytes");
            return n;
        }
        void finish() throws IOException {
            if (position != expected.length) throw unsupported("incomplete moov roundtrip");
        }
        public boolean isOpen() { return true; }
        public void close() { }
    }

    private static final class ArrayChannel implements WritableByteChannel {
        private final ByteBuffer bytes;
        ArrayChannel(int size) { bytes = ByteBuffer.allocate(size); }
        public int write(ByteBuffer src) throws IOException {
            int n = src.remaining();
            if (n > bytes.remaining()) throw unsupported("serialized moov exceeds bound");
            bytes.put(src); return n;
        }
        byte[] finish() throws IOException {
            if (bytes.hasRemaining()) throw unsupported("short serialized moov");
            return bytes.array();
        }
        public boolean isOpen() { return true; }
        public void close() { }
    }

    private static void verifyOutput(Path output, Path source, Layout input, byte[] replacement,
                                     byte[] uuid, byte[] xml) throws IOException {
        Layout actual = requireOutputLayout(output, input, uuid, xml);
        if (!Arrays.equals(actual.moovBytes, replacement)) throw new IOException("New moov bytes mismatch");
        if (comparePrefix(source, output, input.moov.start) != -1)
            throw new IOException("Original prefix/media bytes changed");
        // Raw prefix equality plus unchanged stco/co64/table bytes in the roundtrip
        // ensures every media byte and every original offset is retained.
    }

    private static Layout requireOutputLayout(Path output, Layout input, byte[] uuid, byte[] xml)
            throws IOException {
        Layout actual = inspect(output, uuid, xml);
        long delta = 24L + xml.length;
        if (actual.length != Math.addExact(input.length, delta)
                || actual.moov.start != input.moov.start
                || actual.moov.size != input.moov.size + delta
                || !actual.media.equals(input.media) || actual.metadataCount != 1)
            throw new IOException("Output layout/metadata/length increment differs from expected");
        return actual;
    }

    /** Independent byte/entry validator. No table-sized Java arrays or parser
        getEntries() calls: every declared count is checked against bytes first. */
    private static final class Validator {
        private final ByteBuffer b;
        private final List<Extent> media;
        private final byte[] uuid, xml;
        private int boxes, metadataCount;
        private record Node(int start, int end, int header, String type) {
            int p() { return start + header; }
            int bytes() { return end - p(); }
        }
        Validator(byte[] moov, List<Extent> media, byte[] uuid, byte[] xml) {
            b = ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN);
            this.media = media; this.uuid = uuid; this.xml = xml;
        }
        private List<Node> children(int start, int end) throws IOException {
            List<Node> result = new ArrayList<>();
            while (start < end) {
                if (++boxes > MAX_BOXES || end - start < 8) throw unsupported("nested box count/header");
                long size = u32(start);
                int header = 8;
                String type = fourCC(b.getInt(start + 4));
                if (size == 1) {
                    if (end - start < 16) throw unsupported("nested extended header");
                    size = b.getLong(start + 8); header = 16;
                }
                if (type.equals("uuid")) header += 16;
                if (size == 0 || size < header || size > end - start)
                    throw unsupported("nested size/overflow/EOF: " + type);
                int next = start + (int) size;
                result.add(new Node(start, next, header, type));
                start = next;
            }
            return result;
        }
        private long u32(int p) { return Integer.toUnsignedLong(b.getInt(p)); }
        private int u16(int p) { return Short.toUnsignedInt(b.getShort(p)); }
        private void atLeast(Node n, int size) throws IOException {
            if (n.bytes() < size) throw unsupported("short " + n.type);
        }
        private void exact(Node n, long size) throws IOException {
            if (n.bytes() != size) throw unsupported("length of " + n.type);
        }
        private int full(Node n, int minimum, boolean versionOne) throws IOException {
            atLeast(n, minimum);
            int vf = b.getInt(n.p()), version = vf >>> 24;
            if ((vf & 0xffffff) != 0 || (version != 0 && !(versionOne && version == 1)))
                throw unsupported("version/flags of " + n.type);
            return version;
        }
        private int count(Node n, int offset, int width, int fixed) throws IOException {
            atLeast(n, fixed);
            long count = u32(n.p() + offset);
            if (count > MAX_ENTRIES || (long) fixed + count * width != n.bytes())
                throw unsupported("declared count/byte extent: " + n.type);
            return (int) count;
        }
        private Node one(List<Node> nodes, String type) throws IOException {
            Node found = null;
            for (Node n : nodes) if (n.type.equals(type)) {
                if (found != null) throw unsupported("duplicate " + type);
                found = n;
            }
            if (found == null) throw unsupported("missing " + type);
            return found;
        }
        private Node optional(List<Node> nodes, String type) throws IOException {
            Node found = null;
            for (Node n : nodes) if (n.type.equals(type)) {
                if (found != null) throw unsupported("duplicate " + type);
                found = n;
            }
            return found;
        }
        private void allowed(List<Node> nodes, boolean track, String... types) throws IOException {
            List<String> accepted = Arrays.asList(types);
            for (Node n : nodes) {
                if (!accepted.contains(n.type)) throw unsupported("unsupported child " + n.type);
                if (n.type.equals("uuid")) metadata(n, track);
            }
        }
        private void metadata(Node n, boolean track) throws IOException {
            int user = n.p() - 16;
            boolean matches = true;
            for (int i = 0; i < 16; i++) if (b.get(user + i) != uuid[i]) matches = false;
            if (!matches) return;
            if (!track || ++metadataCount > 1 || n.bytes() != xml.length)
                throw unsupported("conflicting/duplicate/misplaced spherical UUID");
            for (int i = 0; i < xml.length; i++) if (b.get(n.p() + i) != xml[i])
                throw unsupported("spherical UUID contains different XML; not overwriting");
        }
        void validate() throws IOException {
            List<Node> top = children(0, b.limit());
            if (top.size() != 1 || !top.get(0).type.equals("moov")) throw unsupported("moov boundary");
            Node moov = top.get(0);
            List<Node> movie = children(moov.p(), moov.end);
            allowed(movie, false, "mvhd", "trak", "udta", "free", "uuid");
            Node mvhd = one(movie, "mvhd");
            headerTime(mvhd, 100, 112, true);
            for (Node n : movie) if (n.type.equals("udta")) userData(n);
            Node trak = one(movie, "trak");
            List<Node> track = children(trak.p(), trak.end);
            allowed(track, true, "tkhd", "mdia", "edts", "udta", "free", "uuid");
            trackHeader(one(track, "tkhd"));
            Node edts = optional(track, "edts");
            if (edts != null) edits(edts);
            for (Node n : track) if (n.type.equals("udta")) userData(n);
            Node mdia = one(track, "mdia");
            List<Node> md = children(mdia.p(), mdia.end);
            allowed(md, false, "mdhd", "hdlr", "minf", "elng", "free", "uuid");
            long duration = headerTime(one(md, "mdhd"), 24, 36, false);
            handler(one(md, "hdlr"), true);
            Node language = optional(md, "elng");
            if (language != null) {
                full(language, 5, false);
                if (b.get(language.end - 1) != 0) throw unsupported("unterminated extended language");
            }
            Node minf = one(md, "minf");
            List<Node> mi = children(minf.p(), minf.end);
            allowed(mi, false, "vmhd", "dinf", "stbl", "free", "uuid");
            Node vmhd = one(mi, "vmhd");
            exact(vmhd, 12);
            if (b.getInt(vmhd.p()) != 1) throw unsupported("vmhd version/flags");
            int references = dataReferences(one(mi, "dinf"));
            sampleTable(one(mi, "stbl"), references, duration);
        }
        private long headerTime(Node n, int v0Size, int v1Size, boolean movie) throws IOException {
            int version = full(n, 4, true);
            exact(n, version == 0 ? v0Size : v1Size);
            int p = n.p() + (version == 0 ? 12 : 20);
            long scale = u32(p);
            long duration = version == 0 ? u32(p + 4) : b.getLong(p + 4);
            if (scale == 0 || duration <= 0) throw unsupported("zero/overflow " + n.type + " timescale/duration");
            if (movie && u32(n.end - 4) < 2) throw unsupported("mvhd next track ID");
            return duration;
        }
        private void trackHeader(Node n) throws IOException {
            atLeast(n, 4);
            int version = b.getInt(n.p()) >>> 24;
            if (version != 0 && version != 1) throw unsupported("tkhd version");
            exact(n, version == 0 ? 84 : 96);
            int flags = b.getInt(n.p()) & 0xffffff;
            int p = n.p() + (version == 0 ? 12 : 20);
            if ((flags & ~15) != 0 || u32(p) == 0 || b.getInt(n.end - 8) <= 0 || b.getInt(n.end - 4) <= 0)
                throw unsupported("tkhd flags/track ID/visual dimensions");
        }
        private void handler(Node n, boolean video) throws IOException {
            full(n, 24, false);
            if (video && b.getInt(n.p() + 8) != 0x76696465) throw unsupported("non-video handler");
        }
        private void edits(Node n) throws IOException {
            List<Node> nodes = children(n.p(), n.end);
            allowed(nodes, false, "elst");
            Node elst = one(nodes, "elst");
            int version = full(elst, 8, true);
            int entries = count(elst, 4, version == 0 ? 12 : 20, 8);
            if (entries == 0 || entries > 16) throw unsupported("edit count");
            int p = elst.p() + 8;
            for (int i = 0; i < entries; i++) {
                long duration = version == 0 ? u32(p) : b.getLong(p);
                long time = version == 0 ? b.getInt(p + 4) : b.getLong(p + 8);
                int rate = b.getInt(p + (version == 0 ? 8 : 16));
                if (duration <= 0 || time < -1 || rate != 0x10000) throw unsupported("unsupported edit");
                p += version == 0 ? 12 : 20;
            }
        }
        private int dataReferences(Node n) throws IOException {
            List<Node> nodes = children(n.p(), n.end);
            allowed(nodes, false, "dref");
            Node dref = one(nodes, "dref");
            full(dref, 8, false);
            long declared = u32(dref.p() + 4);
            if (declared == 0 || declared > MAX_BOXES || declared > (dref.bytes() - 8) / 12)
                throw unsupported("data reference count");
            List<Node> entries = children(dref.p() + 8, dref.end);
            if (entries.size() != declared) throw unsupported("data reference extent");
            for (Node entry : entries) {
                exact(entry, 4);
                if (!entry.type.equals("url ") || b.getInt(entry.p()) != 1)
                    throw unsupported("external/non-self-contained media reference");
            }
            return entries.size();
        }
        private int descriptions(Node n, int references) throws IOException {
            full(n, 8, false);
            long declared = u32(n.p() + 4);
            if (declared == 0 || declared > 256 || declared > (n.bytes() - 8) / 86)
                throw unsupported("sample description count");
            List<Node> entries = children(n.p() + 8, n.end);
            if (entries.size() != declared) throw unsupported("sample description extent");
            for (Node entry : entries) {
                String config = switch (entry.type) {
                    case "avc1", "avc3" -> "avcC";
                    case "hvc1", "hev1" -> "hvcC";
                    case "vp09" -> "vpcC";
                    case "av01" -> "av1C";
                    case "mp4v" -> "esds";
                    default -> throw unsupported("unsupported visual sample entry " + entry.type);
                };
                atLeast(entry, 78);
                int p = entry.p();
                if (u16(p + 6) == 0 || u16(p + 6) > references || u16(p + 24) == 0 || u16(p + 26) == 0)
                    throw unsupported("visual description reference/dimensions");
                for (int i = 0; i < 6; i++) if (b.get(p + i) != 0) throw unsupported("sample entry reserved bytes");
                List<Node> extensions = children(p + 78, entry.end);
                allowed(extensions, false, "avcC", "hvcC", "vpcC", "av1C", "esds",
                        "btrt", "pasp", "colr", "clap", "fiel", "gama", "free", "uuid");
                Node configuration = one(extensions, config);
                atLeast(configuration, switch (config) {
                    case "avcC" -> 7;
                    case "hvcC" -> 23;
                    case "vpcC" -> 12;
                    case "av1C" -> 4;
                    default -> 6;
                });
                if (config.equals("avcC")) avcConfiguration(configuration);
                if (config.equals("hvcC")) hevcConfiguration(configuration);
                if (config.equals("esds")) {
                    full(configuration, 6, false);
                    descriptors(configuration.p() + 4, configuration.end, 0);
                }
                for (Node ext : extensions) switch (ext.type) {
                    case "btrt" -> exact(ext, 12);
                    case "pasp" -> exact(ext, 8);
                    case "clap" -> exact(ext, 32);
                    case "fiel" -> exact(ext, 2);
                    case "gama" -> exact(ext, 4);
                    case "colr" -> atLeast(ext, 4);
                    default -> { }
                }
            }
            return entries.size();
        }
        private void avcConfiguration(Node n) throws IOException {
            int p = n.p();
            if (b.get(p) != 1 || (b.get(p + 4) & 3) == 2) throw unsupported("avcC version/NAL length");
            int sps = b.get(p + 5) & 31;
            if (sps == 0) throw unsupported("avcC missing SPS");
            p = nalUnits(p + 6, n.end, sps);
            if (p >= n.end) throw unsupported("avcC missing PPS count");
            int pps = Byte.toUnsignedInt(b.get(p++));
            if (pps == 0) throw unsupported("avcC missing PPS");
            p = nalUnits(p, n.end, pps);
            // High-profile extension has three control bytes and a counted SPS-ext list.
            if (p < n.end) {
                int profile = Byte.toUnsignedInt(b.get(n.p() + 1));
                if (profile != 100 && profile != 110 && profile != 122 && profile != 144
                        && profile != 44 && profile != 83 && profile != 86 && profile != 118
                        && profile != 128 && profile != 138 && profile != 139 && profile != 134
                        && profile != 135) throw unsupported("avcC trailing bytes");
                if (n.end - p < 4) throw unsupported("avcC high-profile extension");
                p = nalUnits(p + 4, n.end, Byte.toUnsignedInt(b.get(p + 3)));
            }
            if (p != n.end) throw unsupported("avcC extent");
        }
        private void hevcConfiguration(Node n) throws IOException {
            if (b.get(n.p()) != 1) throw unsupported("hvcC version");
            int arrays = Byte.toUnsignedInt(b.get(n.p() + 22)), p = n.p() + 23;
            for (int i = 0; i < arrays; i++) {
                if (n.end - p < 3) throw unsupported("hvcC array count/extent");
                int units = u16(p + 1); p += 3;
                if (units > (n.end - p) / 3) throw unsupported("hvcC NAL count/extent");
                p = nalUnits(p, n.end, units);
            }
            if (p != n.end || arrays == 0) throw unsupported("hvcC extent/empty arrays");
        }
        private void descriptors(int p, int end, int depth) throws IOException {
            if (depth > 8) throw unsupported("esds descriptor depth");
            while (p < end) {
                if (++boxes > MAX_BOXES || end - p < 2) throw unsupported("esds count/header");
                int tag = Byte.toUnsignedInt(b.get(p++)), length = 0, octet, octets = 0;
                do {
                    if (p >= end || ++octets > 4) throw unsupported("esds length field");
                    octet = Byte.toUnsignedInt(b.get(p++));
                    length = (length << 7) | (octet & 127);
                } while ((octet & 128) != 0);
                if (length > end - p) throw unsupported("esds declared extent");
                int next = p + length;
                switch (tag) {
                    case 3 -> {
                        if (next - p < 3) throw unsupported("esds ES header");
                        int flags = Byte.toUnsignedInt(b.get(p + 2)); p += 3;
                        if ((flags & 128) != 0) p += 2;
                        if (p > next) throw unsupported("esds dependent stream extent");
                        if ((flags & 64) != 0) {
                            if (p >= next) throw unsupported("esds URL extent");
                            p += 1 + Byte.toUnsignedInt(b.get(p));
                        }
                        if ((flags & 32) != 0) p += 2;
                        if (p > next) throw unsupported("esds ES flags extent");
                        descriptors(p, next, depth + 1);
                    }
                    case 4 -> {
                        if (next - p < 13) throw unsupported("esds decoder header");
                        descriptors(p + 13, next, depth + 1);
                    }
                    case 5 -> { if (length == 0) throw unsupported("empty decoder-specific data"); }
                    case 6 -> {
                        if (length != 1 || b.get(p) != 2) throw unsupported("unsupported SL configuration");
                    }
                    default -> throw unsupported("unsupported esds descriptor tag " + tag);
                }
                p = next;
            }
        }
        private int nalUnits(int p, int end, int count) throws IOException {
            for (int i = 0; i < count; i++) {
                if (end - p < 2) throw unsupported("avcC NAL count/extent");
                int length = u16(p); p += 2;
                if (length == 0 || length > end - p) throw unsupported("avcC NAL size/extent");
                p += length;
            }
            return p;
        }
        private void sampleTable(Node n, int references, long duration) throws IOException {
            List<Node> nodes = children(n.p(), n.end);
            allowed(nodes, false, "stsd", "stts", "ctts", "stsc", "stsz", "stco", "co64",
                    "stss", "sdtp", "free", "uuid");
            int descriptions = descriptions(one(nodes, "stsd"), references);
            Node sz = one(nodes, "stsz");
            full(sz, 12, false);
            long fixedSize = u32(sz.p() + 4), samples = u32(sz.p() + 8);
            if (samples == 0 || samples > MAX_ENTRIES) throw unsupported("sample count budget");
            exact(sz, 12L + (fixedSize == 0 ? samples * 4 : 0));
            Node ts = one(nodes, "stts");
            full(ts, 8, false);
            int times = count(ts, 4, 8, 8);
            long timedSamples = 0, timedDuration = 0;
            for (int i = 0, p = ts.p() + 8; i < times; i++, p += 8) {
                long count = u32(p), delta = u32(p + 4);
                if (count == 0 || delta == 0) throw unsupported("empty time-to-sample run");
                timedSamples = Math.addExact(timedSamples, count);
                timedDuration = Math.addExact(timedDuration, Math.multiplyExact(count, delta));
            }
            if (timedSamples != samples || timedDuration != duration) throw unsupported("sample timing/count vs mdhd");
            Node ctts = optional(nodes, "ctts");
            if (ctts != null) {
                full(ctts, 8, true);
                int entries = count(ctts, 4, 8, 8);
                long total = 0;
                for (int i = 0, p = ctts.p() + 8; i < entries; i++, p += 8) {
                    long count = u32(p);
                    if (count == 0) throw unsupported("empty composition run");
                    total = Math.addExact(total, count);
                }
                if (total != samples) throw unsupported("composition sample count");
            }
            Node stss = optional(nodes, "stss");
            if (stss != null) {
                full(stss, 8, false);
                int entries = count(stss, 4, 4, 8);
                long previous = 0;
                for (int i = 0, p = stss.p() + 8; i < entries; i++, p += 4) {
                    long sample = u32(p);
                    if (sample <= previous || sample > samples) throw unsupported("sync sample index");
                    previous = sample;
                }
            }
            Node sdtp = optional(nodes, "sdtp");
            if (sdtp != null) { full(sdtp, 4, false); exact(sdtp, 4 + samples); }
            Node offsets = optional(nodes, "stco"), largeOffsets = optional(nodes, "co64");
            if ((offsets == null) == (largeOffsets == null)) throw unsupported("one stco or co64 required");
            boolean large = offsets == null;
            if (large) offsets = largeOffsets;
            full(offsets, 8, false);
            int chunks = count(offsets, 4, large ? 8 : 4, 8);
            if (chunks == 0) throw unsupported("zero chunks");
            Node sc = one(nodes, "stsc");
            full(sc, 8, false);
            int runs = count(sc, 4, 12, 8);
            if (runs == 0 || runs > chunks) throw unsupported("sample-to-chunk run count");
            long previous = 0;
            for (int i = 0, p = sc.p() + 8; i < runs; i++, p += 12) {
                long first = u32(p), perChunk = u32(p + 4), description = u32(p + 8);
                if (first <= previous || first > chunks || (i == 0 && first != 1)
                        || perChunk == 0 || perChunk > samples || description == 0 || description > descriptions)
                    throw unsupported("invalid sample-to-chunk entry");
                previous = first;
            }
            long sample = 0, previousEnd = -1;
            int run = 0;
            for (int chunk = 1; chunk <= chunks; chunk++) {
                if (run + 1 < runs && chunk == u32(sc.p() + 8 + (run + 1) * 12)) run++;
                long perChunk = u32(sc.p() + 12 + run * 12);
                if (perChunk > samples - sample) throw unsupported("chunks exceed sample count");
                long bytes = 0;
                for (int j = 0; j < perChunk; j++, sample++) {
                    long size = fixedSize != 0 ? fixedSize : u32(sz.p() + 12 + (int) sample * 4);
                    if (size == 0) throw unsupported("empty video sample");
                    bytes = Math.addExact(bytes, size);
                }
                int p = offsets.p() + 8 + (chunk - 1) * (large ? 8 : 4);
                long offset = large ? b.getLong(p) : u32(p);
                if (offset < 0 || offset < previousEnd || !insideMedia(offset, bytes))
                    throw unsupported("chunk/sample extent outside mdat or overlapping/out-of-order chunks");
                previousEnd = Math.addExact(offset, bytes);
            }
            if (sample != samples) throw unsupported("chunks do not cover declared samples");
        }
        private boolean insideMedia(long offset, long bytes) {
            for (Extent mdat : media)
                if (offset >= mdat.content() && offset <= mdat.end() && bytes <= mdat.end() - offset) return true;
            return false;
        }
        private void userData(Node n) throws IOException {
            List<Node> nodes = children(n.p(), n.end);
            allowed(nodes, false, "meta", "free", "uuid");
            Node meta = optional(nodes, "meta");
            if (meta == null) return;
            full(meta, 4, false);
            List<Node> entries = children(meta.p() + 4, meta.end);
            allowed(entries, false, "hdlr", "ilst", "free", "uuid");
            Node hdlr = optional(entries, "hdlr");
            if (hdlr != null) handler(hdlr, false);
            Node ilst = optional(entries, "ilst");
            if (ilst == null) return;
            for (Node item : children(ilst.p(), ilst.end)) {
                // Do not let a forged item name select an unrelated parser container
                // (e.g. stsd/moov) with a different child/count interpretation.
                if (!Arrays.asList("\u00a9too", "\u00a9nam", "\u00a9ART", "\u00a9alb", "\u00a9day", "\u00a9cmt", "\u00a9gen", "\u00a9grp",
                        "\u00a9wrt", "aART", "trkn", "disk", "covr", "cpil", "pgap", "tmpo", "rtng", "stik", "----").contains(item.type))
                    throw unsupported("unsupported iTunes metadata item " + item.type);
                List<Node> values = children(item.p(), item.end);
                allowed(values, false, "data", "mean", "name", "free", "uuid");
                for (Node value : values) {
                    if (value.type.equals("data")) atLeast(value, 8);
                    if (value.type.equals("mean") || value.type.equals("name")) full(value, 4, false);
                }
            }
        }
    }

    /** Immediate creation ledger, exact-path only, with optional hardlink identity
        witnesses. Directory anchors operate under the trusted-parent contract;
        they are not directory inode witnesses or a hostile-substitution sandbox. */
    private static final class Ledger {
        private static final class Owned {
            final Path path;
            final boolean directory;
            Object key;
            FileTime created;
            Path peer, movedTo;
            Owned anchor;
            boolean observed;
            Owned(Path path, boolean directory) { this.path = path; this.directory = directory; }
        }
        private final List<Owned> owned = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private boolean closeUncertain;
        Path createdPayload(String name) {
            for (Owned entry : owned) if (!entry.directory && entry.path.getFileName().toString().equals(name)) return entry.path;
            return null;
        }

        Path directory(Path parent) throws IOException {
            Path path = parent == null ? Files.createTempDirectory(".replaymod-video-")
                    : Files.createTempDirectory(parent, ".replaymod-video-");
            Owned entry = new Owned(path, true);
            owned.add(entry); // before toRealPath/readAttributes/anchor work can fail
            observe(entry);
            return path;
        }
        private void observe(Owned entry) throws IOException {
            BasicFileAttributes a = Files.readAttributes(entry.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isDirectory() != entry.directory
                    || (!entry.directory && !a.isRegularFile())) throw new IOException("Created object type changed: " + entry.path);
            entry.key = a.fileKey(); entry.created = a.creationTime(); entry.observed = true;
        }
        void anchor(Path directory) throws IOException {
            Path marker = directory.resolve(".owner");
            // External peer binds the marker to the original trusted parent. A
            // directory transplant with the same anchor remains outside our contract.
            try (FileChannel out = payload(marker, directory.getParent())) {
                writeFully(out, ByteBuffer.wrap(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII)));
            } catch (IOException | RuntimeException failure) {
                closeUncertain = true; // conservatively retain even when body vs close cannot be distinguished
                throw failure;
            }
            find(directory).anchor = find(marker);
        }
        FileChannel payload(Path path) throws IOException { return payload(path, path.getParent()); }
        private FileChannel payload(Path path, Path witnessParent) throws IOException {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            Owned entry = new Owned(path, false);
            owned.add(entry); // channel creation succeeded, so the path is ours even if later setup fails
            try {
                observe(entry);
                Path witness = witnessParent.resolve(".replaymod-video-witness-" + UUID.randomUUID());
                try {
                    Files.createLink(witness, path);
                    Owned link = new Owned(witness, false);
                    owned.add(link); // never claim a failed CREATE_NEW/link conflict
                    entry.peer = witness; link.peer = path;
                    observe(link);
                } catch (IOException | UnsupportedOperationException | SecurityException optional) {
                    notes.add("Optional hardlink witness unavailable for " + path + ": " + optional);
                }
                return channel;
            } catch (IOException | RuntimeException primary) {
                try { channel.close(); } catch (IOException | RuntimeException close) {
                    closeUncertain = true;
                    if (primary != close) primary.addSuppressed(close);
                }
                throw primary;
            }
        }
        private Owned find(Path path) throws IOException {
            for (Owned entry : owned) if (entry.path.equals(path)) return entry;
            throw new IOException("Missing own creation ledger entry: " + path);
        }
        void requireIdentity(Path path) throws IOException {
            Owned entry = find(path);
            // A missing key/witness is not a hard platform gate: the exclusive-input
            // contract and full closed-file checks still apply. Known replacements fail.
            if (!identity(entry, false)) throw new IOException("Known replacement of owned payload: " + path);
        }
        void moved(Path path, Path target) throws IOException {
            Owned entry = find(path);
            entry.movedTo = target;
            if (entry.peer != null) find(entry.peer).peer = target;
        }
        private boolean identity(Owned entry, boolean proofRequired) throws IOException {
            if (!entry.observed) return false;
            Path path = entry.movedTo != null ? entry.movedTo : entry.path;
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isDirectory() != entry.directory
                    || (!entry.directory && !a.isRegularFile())) return false;
            if (entry.key != null) return entry.key.equals(a.fileKey());
            // A newly available key is not evidence that the previous object survived.
            if (a.fileKey() != null || !entry.created.equals(a.creationTime())) return false;
            if (entry.directory) {
                if (entry.anchor == null) return !proofRequired;
                return identity(entry.anchor, proofRequired);
            }
            if (entry.peer != null) return Files.isSameFile(path, entry.peer);
            return !proofRequired;
        }
        List<String> cleanup(boolean disposable) {
            List<String> result = new ArrayList<>(notes);
            if (!disposable) {
                for (Owned entry : owned) result.add("Not cleaned; recorded " + (entry.directory ? "directory: " : "file: ")
                        + entry.path + (entry.movedTo == null ? "; existence/identity not assumed"
                        : "; published at " + entry.movedTo));
                return result;
            }
            // Validate ALL peers before deleting either name of a hardlink pair.
            // This is not an atomic delete-if-same-inode primitive: trusted parents
            // must remain free of external writers during this short cleanup window.
            List<Owned> safe = new ArrayList<>();
            List<Path> unsafeDirectories = new ArrayList<>();
            for (Owned entry : owned) if (entry.directory) {
                try {
                    if (!identity(entry, true)) unsafeDirectories.add(entry.path);
                } catch (IOException | RuntimeException e) {
                    unsafeDirectories.add(entry.path);
                    result.add("Directory identity check failed: " + entry.path + ": " + e);
                }
            }
            for (Owned entry : owned) {
                if (entry.movedTo != null) continue; // published target is NEVER a cleanup target
                boolean blocked = false;
                for (Path directory : unsafeDirectories) {
                    if (entry.path.startsWith(directory) || (entry.peer != null && entry.peer.startsWith(directory))) blocked = true;
                }
                try {
                    if (!blocked && identity(entry, true)) safe.add(entry);
                    else result.add("Retained; ownership identity not proven: " + entry.path);
                } catch (IOException | RuntimeException e) {
                    result.add("Retained; identity check failed: " + entry.path + ": " + e);
                }
            }
            // Exact files first, then only the directories that are now empty.
            for (int i = safe.size() - 1; i >= 0; i--) if (!safe.get(i).directory) delete(safe.get(i), result);
            for (int i = safe.size() - 1; i >= 0; i--) if (safe.get(i).directory) delete(safe.get(i), result);
            return result;
        }
        private void delete(Owned entry, List<String> diagnostics) {
            try { Files.delete(entry.path); }
            catch (IOException | RuntimeException e) { diagnostics.add("Cleanup failed; retained path " + entry.path + ": " + e); }
        }
    }
}
