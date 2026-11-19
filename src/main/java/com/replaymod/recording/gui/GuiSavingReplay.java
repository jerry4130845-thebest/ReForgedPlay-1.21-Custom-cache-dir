package com.replaymod.recording.gui;

import com.replaymod.core.ReplayMod;
import com.replaymod.core.files.ReplayStorageLayout;
import com.replaymod.core.files.ReplayFileOperations;
import java.util.concurrent.atomic.AtomicBoolean;
import com.replaymod.core.utils.Utils;
import com.replaymod.recording.Setting;
import com.replaymod.replay.gui.screen.GuiReplayViewer;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import de.johni0702.minecraft.gui.container.AbstractGuiScreen;
import de.johni0702.minecraft.gui.container.GuiContainer;
import de.johni0702.minecraft.gui.container.GuiPanel;
import de.johni0702.minecraft.gui.container.GuiScreen;
import de.johni0702.minecraft.gui.container.VanillaGuiScreen;
import de.johni0702.minecraft.gui.element.GuiButton;
import de.johni0702.minecraft.gui.element.GuiLabel;
import de.johni0702.minecraft.gui.element.GuiTextField;
import de.johni0702.minecraft.gui.element.GuiTooltip;
import de.johni0702.minecraft.gui.element.advanced.GuiProgressBar;
import de.johni0702.minecraft.gui.function.Focusable;
import de.johni0702.minecraft.gui.layout.CustomLayout;
import de.johni0702.minecraft.gui.layout.HorizontalLayout;
import de.johni0702.minecraft.gui.layout.VerticalLayout;
import de.johni0702.minecraft.gui.utils.Colors;
import de.johni0702.minecraft.gui.utils.lwjgl.Dimension;
import de.johni0702.minecraft.gui.utils.lwjgl.ReadableDimension;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.crash.CrashReport;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.replaymod.core.versions.MCVer.getMinecraft;
import static de.johni0702.minecraft.gui.utils.Utils.link;

public class GuiSavingReplay {

    private static final MinecraftClient mc = getMinecraft();
    private static final Logger logger = LogManager.getLogger();

    private final GuiLabel label = new GuiLabel()
            .setI18nText("replaymod.gui.replaysaving.title")
            .setColor(Colors.BLACK);

    private final GuiProgressBar progressBar = new GuiProgressBar()
            .setHeight(14);

    private final GuiPanel panel = new GuiPanel()
            .setLayout(new VerticalLayout().setSpacing(2))
            .addElements(new VerticalLayout.Data(0.5), label, progressBar);

    private final ReplayMod core;
    private final ReplayStorageLayout layout;
    private final List<Output> outputs = new ArrayList<>();
    private final AtomicBoolean applying = new AtomicBoolean();

    private static final class Output {
        Path path;
        final GuiTextField field;
        boolean completed;
        IOException incomplete;
        Output(Path path, GuiTextField field) { this.path = path; this.field = field; }
    }
    private record Request(Output output, String name) {}

    public GuiSavingReplay(ReplayMod core) { this(core, snapshot(core)); }
    public GuiSavingReplay(ReplayMod core, ReplayStorageLayout layout) {
        this.core = core;
        this.layout = layout;
    }
    private static ReplayStorageLayout snapshot(ReplayMod core) {
        try { return core.folders.snapshotStorageLayout(); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    public void open() {
        core.getBackgroundProcesses().addProcess(panel);
    }

    public void close() {
        core.getBackgroundProcesses().removeProcess(panel);
        AbstractGuiScreen<?> currentScreen = GuiScreen.from(mc.currentScreen);
        if (currentScreen instanceof GuiReplayViewer) {
            ((GuiReplayViewer) currentScreen).list.load();
        }
    }

    public GuiProgressBar getProgressBar() {
        return progressBar;
    }

    public void presentRenameDialog(List<Pair<Path, ReplayMetaData>> outputPaths) {
        panel.removeElement(progressBar);

        link(outputPaths.stream().map(it -> addOutput(it.getKey(), it.getValue())).toArray(Focusable[]::new));

        GuiButton applyButton = new GuiButton()
                .setSize(150, 20)
                .setI18nLabel("replaymod.gui.done")
                .onClick(this::apply);

        panel.addElements(new VerticalLayout.Data(0.5), applyButton);

        if (!core.getSettingsRegistry().get(Setting.RENAME_DIALOG)) {
            apply();
        }
    }

    private GuiTextField addOutput(Path path, ReplayMetaData metaData) {
        String originalName = Utils.fileNameToReplayName(path.getFileName().toString());
        GuiTextField textField = new GuiTextField()
                .setSize(130, 20)
                .setText(originalName)
                .setI18nHint("replaymod.gui.delete")
                .setTextColorDisabled(Colors.RED)
                .onEnter(this::apply)
                .setTooltip(createTooltip(path, metaData));
        GuiButton clearButton = new GuiButton()
                .setSize(20, 20)
                .setLabel("X")
                .setTooltip(new GuiTooltip().setI18nText("replaymod.gui.delete"))
                .onClick(() -> textField.setText(""));
        GuiPanel row = new GuiPanel()
                .setLayout(new HorizontalLayout())
                .addElements(null, textField, clearButton);
        panel.addElements(new VerticalLayout.Data(0.5), row);

        outputs.add(new Output(path, textField));

        return textField;
    }

    private GuiPanel createTooltip(Path path, ReplayMetaData metaData) {
        GuiTooltip tooltip = new GuiTooltip();
        GuiReplayViewer.GuiReplayEntry entry = new GuiReplayViewer.GuiReplayEntry(path.toFile(), metaData, null, new ArrayList<>());
        return new GuiPanel().setLayout(new CustomLayout<GuiPanel>() {
            @Override
            protected void layout(GuiPanel container, int width, int height) {
                pos(entry, 4, 4);
                size(entry, width - 8, height - 8);
                size(tooltip, width, height);
            }

            @Override
            public ReadableDimension calcMinSize(GuiContainer<?> container) {
                ReadableDimension size = entry.calcMinSize();
                return new Dimension(size.getWidth() + 8, size.getHeight() + 8);
            }
        }).addElements(null, tooltip, entry);
    }

    private void apply() {
        if (!mc.isOnThread()) { core.runLater(this::apply); return; }
        if (!applying.compareAndSet(false, true)) return;
        // Snapshot every widget on the client thread. The worker never reads GUI state/settings.
        List<Request> requests = new ArrayList<>();
        for (Output output : outputs) if (!output.completed) requests.add(new Request(output, output.field.getText()));
        Runnable worker = () -> {
            Throwable failure = null;
            try {
                for (Request request : requests) {
                    Output output = request.output();
                    if (output.incomplete != null) throw output.incomplete; // never republish a partial move
                    if (request.name().isEmpty()) {
                        Files.delete(output.path); // only an explicitly empty snapshotted name deletes
                    } else {
                        Files.createDirectories(layout.replayRoot());
                        Path desired = Utils.replayNameToPath(layout.replayRoot(), request.name());
                        try {
                            output.path = ReplayFileOperations.publishReplay(output.path, layout.replayRoot(),
                                    desired.getFileName().toString());
                        } catch (ReplayFileOperations.IncompletePublicationException partial) {
                            output.path = partial.publishedPath();
                            output.incomplete = partial;
                            throw partial;
                        }
                    }
                    output.completed = true; // actual receipt; later retry skips already published/deleted items
                }
            } catch (Throwable problem) { failure = problem; logger.error("Publishing replay files:", problem); }
            Throwable result = failure;
            try {
                core.runLater(() -> {
                    applying.set(false);
                    if (result == null) close();
                    else Utils.error(logger, VanillaGuiScreen.wrap(mc.currentScreen),
                            CrashReport.create(result, "Publishing replay files"), () -> {});
                    // On failure keep this panel and its actual publication receipts for retry.
                });
            } catch (Throwable scheduling) {
                applying.set(false);
                logger.error("Replay publication GUI scheduling failed; actual path receipts retained for retry", scheduling);
            }
        };
        try { new Thread(worker, "replaymod-publish-replays").start(); }
        catch (Throwable failure) {
            applying.set(false);
            Utils.error(logger, VanillaGuiScreen.wrap(mc.currentScreen),
                    CrashReport.create(failure, "Starting replay publication"), () -> {});
        }
    }
}
