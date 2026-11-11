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

    public Path getReplayFolder() throws IOException {
        return ensureDirectoryExists(mcDir.resolve(settings.get(Setting.RECORDING_PATH)));
    }

    /**
     * Folder into which replay backups are saved before the MarkerProcessor is unleashed.
     */
    public Path getRawReplayFolder() throws IOException {
        return ensureDirectoryExists(getReplayFolder().resolve("raw"));
    }

    /**
     * Folder into which replays are recorded.
     * Distinct from the main folder, so they cannot be opened while they are still saving.
     */
    public Path getRecordingFolder() throws IOException {
        return ensureDirectoryExists(getReplayFolder().resolve("recording"));
    }

    /**
     * Folder in which replay cache files are stored.
     * Distinct from the recording folder cause people kept confusing them with recordings.
     */
    public Path getCacheFolder() throws IOException {
        Path path = ensureDirectoryExists(mcDir.resolve(settings.get(Setting.CACHE_PATH)));
        try {
            Files.setAttribute(path, "dos:hidden", true);
        } catch (UnsupportedOperationException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }
        return path;
    }

    private static final PercentEscaper CACHE_FILE_NAME_ENCODER = new PercentEscaper("-_ ", false);

    public Path getCachePathForReplay(Path replay) throws IOException {
        Path replayFolder = getReplayFolder();
        Path cacheFolder = getCacheFolder();
        Path relative = replayFolder.toAbsolutePath().relativize(replay.toAbsolutePath());
        return cacheFolder.resolve(CACHE_FILE_NAME_ENCODER.escape(relative.toString()));
    }

    public Path getReplayPathForCache(Path cache) throws IOException {
        String relative = URLDecoder.decode(cache.getFileName().toString(), "UTF-8");
        Path replayFolder = getReplayFolder();
        return replayFolder.resolve(relative);
    }
}
