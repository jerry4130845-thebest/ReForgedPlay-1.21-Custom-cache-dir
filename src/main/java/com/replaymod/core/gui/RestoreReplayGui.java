package com.replaymod.core.gui;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.files.ManagedReplayFile;
import com.replaymod.core.files.ReplayStorageLayout;
import com.replaymod.core.utils.Utils;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import de.johni0702.minecraft.gui.container.AbstractGuiScreen;
import de.johni0702.minecraft.gui.container.GuiPanel;
import de.johni0702.minecraft.gui.container.GuiScreen;
import de.johni0702.minecraft.gui.container.VanillaGuiScreen;
import de.johni0702.minecraft.gui.element.GuiButton;
import de.johni0702.minecraft.gui.element.GuiLabel;
import de.johni0702.minecraft.gui.element.advanced.GuiProgressBar;
import de.johni0702.minecraft.gui.layout.CustomLayout;
import de.johni0702.minecraft.gui.layout.HorizontalLayout;
import de.johni0702.minecraft.gui.layout.VerticalLayout;
import de.johni0702.minecraft.gui.utils.Colors;
import net.minecraft.util.crash.CrashReport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RestoreReplayGui extends AbstractGuiScreen<RestoreReplayGui> {
    private static final Logger LOGGER = LogManager.getLogger();
    public final GuiScreen parent;
    public final File file;
    public final GuiPanel textPanel = new GuiPanel().setLayout(new VerticalLayout().setSpacing(3));
    public final GuiPanel buttonPanel = new GuiPanel().setLayout(new HorizontalLayout().setSpacing(5));
    public final GuiPanel contentPanel = new GuiPanel(this).addElements(new VerticalLayout.Data(0.5),
            textPanel, buttonPanel).setLayout(new VerticalLayout().setSpacing(20));
    public final GuiButton yesButton = new GuiButton(buttonPanel).setSize(150, 20).setI18nLabel("gui.yes");
    public final GuiButton noButton = new GuiButton(buttonPanel).setSize(150, 20).setI18nLabel("gui.no");
    private final ReplayMod core;
    private final Path logicalOutput;
    private ReplayStorageLayout layout;
    private volatile Path recoveryRecord;
    private final AtomicBoolean submitted = new AtomicBoolean();

    /** Compatibility entry: the file is a legacy original, never a recovery-record filename. */
    public RestoreReplayGui(ReplayMod core, GuiScreen parent, File file) {
        this(core, parent, file, file.toPath(), null, null);
    }
    public RestoreReplayGui(ReplayMod core, GuiScreen parent, File original, Path output, ReplayStorageLayout layout) {
        this(core, parent, original, output, layout, null);
    }
    public RestoreReplayGui(ReplayMod core, GuiScreen parent, Path record, Path output, ReplayStorageLayout layout) {
        this(core, parent, output.toFile(), output, layout, record);
    }
    private RestoreReplayGui(ReplayMod core, GuiScreen parent, File file, Path output,
            ReplayStorageLayout layout, Path record) {
        this.core = core; this.parent = parent; this.file = file;
        this.logicalOutput = output; this.layout = layout; this.recoveryRecord = record;
        textPanel.addElements(new VerticalLayout.Data(0.5),
                new GuiLabel().setI18nText("replaymod.gui.restorereplay1"),
                new GuiLabel().setI18nText("replaymod.gui.restorereplay2", Files.getNameWithoutExtension(file.getName())),
                new GuiLabel().setI18nText("replaymod.gui.restorereplay3"));
        LOGGER.info("Offering recovery: {}; record={}", output, record);
        yesButton.onClick(() -> {
            if (!submitted.compareAndSet(false, true)) return;
            recoverInBackground();
            parent.display();
        });
        noButton.onClick(() -> {
            if (!submitted.compareAndSet(false, true)) return;
            parent.display();
            new Thread(() -> {
                try {
                    ReplayStorageLayout snapshot = taskLayout();
                    if (recoveryRecord != null) core.files.rejectRecovery(snapshot, recoveryRecord);
                    else core.files.rejectLegacyRecovery(snapshot, file.toPath(), logicalOutput);
                } catch (IOException | RuntimeException failure) { showFailure(failure); }
            }, "replaymod-reject-recovery").start();
        });
        setLayout(new CustomLayout<RestoreReplayGui>() {
            @Override protected void layout(RestoreReplayGui container, int width, int height) {
                pos(contentPanel, width / 2 - width(contentPanel) / 2, height / 2 - height(contentPanel) / 2);
            }
        });
    }

    @Override protected RestoreReplayGui getThis() { return this; }

    private ReplayStorageLayout taskLayout() throws IOException {
        if (layout == null) layout = core.files.snapshotStorageLayout();
        return layout;
    }
    private void showFailure(Throwable failure) {
        submitted.set(false);
        LOGGER.error("Recovery failed; output=" + logicalOutput + "; record=" + recoveryRecord, failure);
        CrashReport report = CrashReport.create(failure, "Recovering replay file");
        core.runLater(() -> Utils.error(LOGGER, VanillaGuiScreen.wrap(getMinecraft().currentScreen), report, this::display));
    }
    private void recoverInBackground() {
        GuiLabel label = new GuiLabel().setI18nText("replaymod.gui.replaysaving.title").setColor(Colors.BLACK);
        GuiProgressBar progressBar = new GuiProgressBar().setHeight(14);
        GuiPanel savingProcess = new GuiPanel().setLayout(new VerticalLayout())
                .addElements(new VerticalLayout.Data(0.5), label, progressBar);
        new Thread(() -> {
            core.runLater(() -> core.getBackgroundProcesses().addProcess(savingProcess));
            try { tryRecover(value -> core.runLater(() -> progressBar.setProgress(value))); }
            catch (IOException | RuntimeException failure) { showFailure(failure); }
            finally { core.runLater(() -> core.getBackgroundProcesses().removeProcess(savingProcess)); }
        }, "replaymod-recover-replay").start();
    }

    private void tryRecover(Consumer<Float> progress) throws IOException {
        ReplayStorageLayout snapshot = taskLayout();
        ReplayFile replay = recoveryRecord == null
                ? core.files.openLegacyRecovery(snapshot, file.toPath(), logicalOutput)
                : core.files.openRecovery(snapshot, recoveryRecord);
        try {
            if (!(replay instanceof ManagedReplayFile managed)) throw new IOException("Recovery requires owned transactional replay");
            // Follow the newly independent successor on a failed attempt, so Retry never reuses a superseded prompt.
            recoveryRecord = managed.recoveryRecord();
            replay.save();
            progress.accept(0.4f);
            ReplayMetaData metadata = replay.getMetaData();
            if (metadata != null && metadata.getDuration() == 0) {
                metadata.setDuration(managed.recoverPacketData());
                try (OutputStream out = replay.write("metaData.json")) {
                    metadata.setGenerator(metadata.getGenerator() + "(+ ReplayMod Replay Recovery)");
                    out.write(new Gson().toJson(metadata).getBytes(StandardCharsets.UTF_8));
                }
            }
            progress.accept(0.6f);
            replay.save();
            progress.accept(0.9f);
            replay.close();
            if (!managed.isReleased()) throw new IOException("Recovery actual close not complete; record=" + recoveryRecord);
            progress.accept(1f);
        } catch (IOException | RuntimeException | Error failure) {
            // Explicit failure path: the adapter first checkpoints recovery. No unconditional destructive finally-close.
            if (replay instanceof ManagedReplayFile owned) owned.preserveRecovery(failure);
            try { replay.close(); } catch (Throwable close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
}
