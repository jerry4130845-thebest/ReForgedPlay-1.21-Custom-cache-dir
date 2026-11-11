package com.replaymod.core.files;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * File operations for complete, already closed replay files.
 */
public final class ReplayFileOperations {
    private ReplayFileOperations() {
    }

    /**
     * Moves a complete replay into the raw folder without replacing an existing entry.
     *
     * <p>The caller must have completely saved and closed the source and must ensure
     * exclusive use without further writes throughout this operation. Source and raw
     * parent directories must be trusted and must not be replaced during the task.
     * The source must pass a NOFOLLOW_LINKS regular-file check; reject symbolic links,
     * missing file names, and a raw folder aliasing the source parent. Resolve the
     * absolute source and its parent without silently changing path semantics. These
     * checks are not locks or a sandbox against hostile parent-path replacement.</p>
     *
     * <p>Always create a task-owned {@code .replaymod-anchor-} directory in the source
     * parent and a distinct, initially absent {@code source.link} hard link inside it.
     * Do not use the system temporary directory or raw folder for this source-volume
     * anchor. All source volumes must support creating and deleting this directory
     * and hard link; unsupported capability fails without deleting the source, with
     * no anchor-free fallback. Task ownership does not imply ACL isolation.</p>
     *
     * <p>Snapshot size, modification time and the optional file key before anchoring.
     * Every anchored validation must read NOFOLLOW_LINKS regular-file attributes of
     * both source and anchor, compare both sizes and modification times to the initial
     * snapshot, and require {@link Files#isSameFile(Path, Path)} to return true for
     * their distinct paths. Compare file keys only where both compared keys are
     * non-null; null alone is not failure and must never bypass anchor validation.
     * This does not prove immutable identity between the initial read and anchoring
     * when keys are unavailable; exclusive use remains a prerequisite.</p>
     *
     * <p>Only for equal providers, a narrowly scoped file-store query/comparison may
     * suggest the same-volume optimization. IOException or UnsupportedOperationException
     * in that query means use target staging, not fail the move. This exception
     * fallback must not include actual copy, publication, verification or deletion.
     * SecurityException remains a reported failure. A same-store hint is not a hard-link
     * capability guarantee; a subsequent link failure must not trigger fallback.</p>
     *
     * <p>The fast path uses the anchor as payload after anchored validation. Otherwise,
     * create a task-owned {@code .replaymod-move-} directory within the raw folder and
     * copy the anchor into its initially absent {@code payload.part}. Close the copy,
     * validate source and anchor, require {@code Files.mismatch(anchor, staged) == -1L},
     * then validate source and anchor again. Do not read the entire file into memory,
     * require attribute copying, or treat this as replay-container validation.</p>
     *
     * <p>Uses the original name, then numbered alternatives such as {@code name.1.mcpr}
     * and {@code name.2.mcpr}. Validate source and anchor immediately before publication.
     * Publish only by non-overwriting hard-link creation; only FileAlreadyExistsException
     * permits advancing the number, with overflow rejected and the same payload reused.
     * Reject a conflicting candidate aliasing either source or anchor; comparison
     * errors stop processing. Never delete a conflict or use an overwrite-prone move.
     * Mark publication immediately after successful link creation, confirm candidate
     * and payload identity, then validate source and anchor again.</p>
     *
     * <p>After publication, strictly delete the staged link and empty stage directory
     * if present, validate source against the still-present anchor, then delete the
     * anchor link and empty anchor directory. Only then reread the source's NOFOLLOW_LINKS
     * regular-file attributes and compare initial size/time and comparable non-null
     * keys. The fast path may additionally compare source and candidate identity;
     * cross-volume copies must not require that identity. Finally delete the source
     * and return immediately. No fallible cleanup, close or verification may follow
     * successful source deletion, including through finally or try-with-resources.</p>
     *
     * <p>Normal return requires complete raw publication, no overwritten entry, source
     * deletion, and no remaining task anchor or stage links/directories. Only then may
     * the caller write clipping output at the original source path. Before publication,
     * failures retain the source and conservatively retain any anchor/stage, including
     * partial payloads. Do not add automatic failure cleanup or recovery. An anchor is
     * another name for the source, not an immutable backup or disposable garbage.</p>
     *
     * <p>After publication, any confirmation, cleanup or deletion failure must report
     * {@code Raw published; move incomplete}, retain raw, leave any remaining source
     * untouched and stop clipping. Never delete published raw as rollback. Every failure
     * must report phase, source, rawFolder, anchorDir, anchor, stage, staged and candidate;
     * reported paths may already have been removed. UnsupportedOperationException and
     * SecurityException outside the optional query must be wrapped in IOException with
     * their cause; genuine copy/publication errors must not be swallowed.</p>
     *
     * <p>After anchor removal, a cross-volume source with a null key has only final
     * attribute checks, not final identity proof. Even non-null keys or fast-path
     * isSameFile checks leave a check-to-delete TOCTOU window. Public JDK APIs do not
     * provide conditional atomic deletion here. Correctness depends on the trusted
     * directories and closed-source exclusive-use contract; neither byte comparison
     * nor identity checks prevent all external mutation. No hostile-race protection,
     * crash durability, directory flush or power-loss guarantee is made.</p>
     *
     * @param source complete, closed replay file to move
     * @param rawFolder trusted destination folder for the original replay
     * @return the actual published raw path, only after the source has been deleted
     * @throws IOException if validation, copying, publication or cleanup fails
     */
    public static Path moveToRaw(Path source, Path rawFolder) throws IOException {
        String phase = "resolve absolute source";
        Path anchorDir = null;
        Path anchor = null;
        Path stage = null;
        Path staged = null;
        Path candidate = null;
        boolean published = false;
        try {
            // Do not normalize: removing path components can change symbolic-link semantics.
            source = source.toAbsolutePath();
            Path parent = source.getParent();
            Path fileName = source.getFileName();
            if (parent == null || fileName == null) {
                throw new IOException("Source has no parent or file name");
            }
            candidate = rawFolder.resolve(fileName.toString());
            phase = "validate source";
            BasicFileAttributes snapshot = readRegularFile(source);
            phase = "validate raw folder";
            // Reject parent-directory aliases before copying or publishing anything.
            if (Files.isSameFile(parent, rawFolder)) {
                throw new IOException("Raw folder is the source parent directory");
            }

            phase = "create source-volume anchor directory";
            anchorDir = Files.createTempDirectory(parent, ".replaymod-anchor-");
            anchor = anchorDir.resolve("source.link");
            phase = "create source-volume anchor link";
            Files.createLink(anchor, source);
            phase = "verify anchored source";
            verifyAnchoredSource(source, anchor, snapshot);

            phase = "query optional same-store hint";
            boolean sameStore = hasSameStoreHint(source, rawFolder);
            Path payload = anchor;
            if (!sameStore) {
                phase = "create target-volume stage";
                stage = Files.createTempDirectory(rawFolder, ".replaymod-move-");
                staged = stage.resolve("payload.part");
                phase = "copy anchor to stage";
                Files.copy(anchor, staged);
                phase = "verify anchored source after copy";
                verifyAnchoredSource(source, anchor, snapshot);
                phase = "verify staged bytes";
                if (Files.mismatch(anchor, staged) != -1L) {
                    throw new IOException("Staged bytes differ from anchor");
                }
                phase = "verify anchored source after byte comparison";
                verifyAnchoredSource(source, anchor, snapshot);
                payload = staged;
            }

            String originalName = fileName.toString();
            int extension = originalName.lastIndexOf('.');
            String baseName = extension < 0 ? originalName : originalName.substring(0, extension);
            int number = 0;
            while (true) {
                phase = "verify anchored source before publication";
                verifyAnchoredSource(source, anchor, snapshot);
                phase = "publish raw hard link";
                try {
                    Files.createLink(candidate, payload);
                    published = true;
                    break;
                } catch (FileAlreadyExistsException conflict) {
                    phase = "check conflicting raw alias";
                    if (Files.isSameFile(source, candidate) || Files.isSameFile(anchor, candidate)) {
                        throw new IOException("Raw candidate aliases the source", conflict);
                    }
                    if (number == Integer.MAX_VALUE) {
                        throw new IOException("Raw name numbering exhausted", conflict);
                    }
                    candidate = rawFolder.resolve(baseName + "." + ++number + ".mcpr");
                }
            }

            phase = "confirm published raw identity";
            if (!Files.isSameFile(candidate, payload)) {
                throw new IOException("Published raw does not identify the complete payload");
            }
            phase = "verify anchored source after publication";
            verifyAnchoredSource(source, anchor, snapshot);
            if (stage != null) {
                // No fallible cleanup may follow successful source deletion.
                phase = "remove staged payload link";
                Files.delete(staged);
                phase = "remove empty stage directory";
                Files.delete(stage);
            }
            phase = "verify anchored source before anchor cleanup";
            verifyAnchoredSource(source, anchor, snapshot);
            phase = "remove source anchor link";
            Files.delete(anchor);
            phase = "remove empty anchor directory";
            Files.delete(anchorDir);
            phase = "verify final source attributes";
            verifyAttributes(snapshot, readRegularFile(source));
            if (sameStore) {
                phase = "verify final fast-path source identity";
                if (!Files.isSameFile(source, candidate)) {
                    throw new IOException("Source no longer identifies the published raw");
                }
            }
            // The final check-to-delete window relies on the caller's exclusive-use contract.
            phase = "delete source";
            Files.delete(source);
            return candidate;
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            throw new IOException((published ? "Raw published; move incomplete" : "Raw move failed")
                    + "; phase=" + phase + "; source=" + source + "; rawFolder=" + rawFolder
                    + "; anchorDir=" + anchorDir + "; anchor=" + anchor
                    + "; stage=" + stage + "; staged=" + staged + "; candidate=" + candidate,
                    failure);
        }
    }

    private static BasicFileAttributes readRegularFile(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Expected a regular, non-symbolic-link file: " + path);
        }
        return attributes;
    }

    private static void verifyAttributes(BasicFileAttributes snapshot, BasicFileAttributes current)
            throws IOException {
        if (snapshot.size() != current.size()
                || !snapshot.lastModifiedTime().equals(current.lastModifiedTime())
                || (snapshot.fileKey() != null && current.fileKey() != null
                        && !snapshot.fileKey().equals(current.fileKey()))) {
            throw new IOException("File identity, size or modification time changed");
        }
    }

    private static void verifyAnchoredSource(Path source, Path anchor, BasicFileAttributes snapshot)
            throws IOException {
        BasicFileAttributes currentSource = readRegularFile(source);
        BasicFileAttributes currentAnchor = readRegularFile(anchor);
        verifyAttributes(snapshot, currentSource);
        verifyAttributes(snapshot, currentAnchor);
        // Also compare currently available keys when the initial snapshot had no key.
        verifyAttributes(currentSource, currentAnchor);
        if (source.equals(anchor) || !Files.isSameFile(source, anchor)) {
            throw new IOException("Source and its distinct anchor no longer identify the same file");
        }
    }

    private static boolean hasSameStoreHint(Path source, Path rawFolder) throws IOException {
        if (source.getFileSystem().provider() != rawFolder.getFileSystem().provider()) {
            return false;
        }
        try {
            return Files.getFileStore(source).equals(Files.getFileStore(rawFolder));
        } catch (IOException | UnsupportedOperationException unavailableHint) {
            // Only this optional query may degrade; real copy/link/delete failures must escape.
            return false;
        }
    }
}
