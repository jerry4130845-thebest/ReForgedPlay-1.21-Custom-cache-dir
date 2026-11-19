package com.replaymod.core.gui;

import com.replaymod.core.Setting;
import com.replaymod.core.SettingsRegistry;
import com.replaymod.core.files.ReplayStorageLayout;
import de.johni0702.minecraft.gui.container.AbstractGuiScreen;
import de.johni0702.minecraft.gui.container.GuiContainer;
import de.johni0702.minecraft.gui.container.GuiScrollable;
import de.johni0702.minecraft.gui.element.GuiLabel;
import de.johni0702.minecraft.gui.element.GuiButton;
import de.johni0702.minecraft.gui.element.GuiTextField;
import de.johni0702.minecraft.gui.element.GuiToggleButton;
import de.johni0702.minecraft.gui.function.Closeable;
import de.johni0702.minecraft.gui.function.Typeable;
import de.johni0702.minecraft.gui.layout.CustomLayout;
import de.johni0702.minecraft.gui.utils.lwjgl.Dimension;
import de.johni0702.minecraft.gui.utils.lwjgl.ReadableDimension;
import de.johni0702.minecraft.gui.utils.lwjgl.ReadablePoint;
import de.johni0702.minecraft.gui.versions.MCVer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Style;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A local draft: neither navigation nor toggling a widget updates the registry. */
public final class GuiStorageSettings extends AbstractGuiScreen<GuiStorageSettings> implements Typeable, Closeable {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PREFIX = "replaymod.gui.storage.";
    private static final List<Setting<String>> PATH_KEYS = List.of(Setting.RECORDING_PATH, Setting.CACHE_PATH,
            Setting.TEMP_PATH, Setting.RAW_PATH, Setting.RECORDING_WORK_PATH, Setting.TMP_PATH);
    private static final int RAW = 3;

    private final Screen parent;
    private final SettingsRegistry registry;
    private final Map<SettingsRegistry.SettingKey<?>, Object> initialValues;
    private final GuiScrollable contents = new GuiScrollable(this);
    private final GuiTextField[] paths = new GuiTextField[PATH_KEYS.size()];
    // In particular, never read a disabled raw widget to recover its stored value.
    private final String[] draftPaths = new String[PATH_KEYS.size()];
    private final GuiLabel[] pathLabels = new GuiLabel[PATH_KEYS.size()];
    private final GuiLabel status = new GuiLabel(contents);
    private final GuiLabel[] hints = new GuiLabel[5];
    private final GuiLabel rawLabel = new GuiLabel(contents).setI18nText(PREFIX + "rawBackupEnabled");
    private final GuiLabel ephemeralLabel = new GuiLabel(contents).setI18nText(PREFIX + "ephemeralCache");
    private final GuiToggleButton<String> raw = new GuiToggleButton<String>(contents);
    private final GuiToggleButton<String> ephemeral = new GuiToggleButton<String>(contents);
    private final GuiButton apply = new GuiButton(this).setI18nLabel(PREFIX + "apply");
    private final GuiButton cancel = new GuiButton(this).setI18nLabel("replaymod.gui.cancel");
    private final int[] fieldTop = new int[PATH_KEYS.size()];
    private int contentWidth = 300, viewportHeight = 100, contentHeight;
    private boolean validating, applied, closed;

    public GuiStorageSettings(Screen parent, SettingsRegistry registry) {
        this.parent = parent;
        this.registry = registry;
        suppressVanillaKeys = true; // Enter must never activate Apply via vanilla navigation.
        setTitle(new GuiLabel().setI18nText(PREFIX + "title"));
        initialValues = registry.snapshot();
        Map<SettingsRegistry.SettingKey<?>, Object> snapshot = initialValues;
        for (int i = 0; i < paths.length; i++) {
            Setting<String> key = PATH_KEYS.get(i);
            Object value = snapshot.get(key);
            draftPaths[i] = value instanceof String text ? text : key.getDefault();
            if (!(value instanceof String)) status.setI18nText(PREFIX + "invalidType", key.getKey());
            pathLabels[i] = new GuiLabel(contents).setI18nText(PREFIX + key.getKey());
            final int index = i;
            paths[i] = new GuiTextField(contents).setMaxLength(Integer.MAX_VALUE).setText(draftPaths[i]);
            paths[i].onTextChanged(old -> draftPaths[index] = paths[index].getText());
            paths[i].onFocusChange(focused -> { if (focused) reveal(index); });
        }
        String[] hintKeys = {"hint.paths", "hint.tasks", "hint.native", "hint.ramdisk", "hint.raw"};
        for (int i = 0; i < hints.length; i++) hints[i] = new GuiLabel(contents).setI18nText(PREFIX + hintKeys[i]);
        raw.setValues(I18n.translate("options.on"), I18n.translate("options.off"))
                .setSelected(Boolean.FALSE.equals(snapshot.get(Setting.RAW_BACKUP_ENABLED)) ? 1 : 0);
        ephemeral.setValues(I18n.translate("options.on"), I18n.translate("options.off"))
                .setSelected(Boolean.TRUE.equals(snapshot.get(Setting.EPHEMERAL_CACHE)) ? 0 : 1);
        for (Setting<Boolean> key : List.of(Setting.RAW_BACKUP_ENABLED, Setting.EPHEMERAL_CACHE)) {
            if (!(snapshot.get(key) instanceof Boolean)) status.setI18nText(PREFIX + "invalidType", key.getKey());
        }
        raw.onClick(this::updateRawInput);
        updateRawInput();
        apply.onClick(this::applyDraft);
        cancel.onClick(this::leave);
        contents.setLayout(new CustomLayout<GuiScrollable>() {
            @Override
            protected void layout(GuiScrollable container, int width, int height) {
                int y = 2;
                if (!status.getText().isEmpty()) {
                    int h = labelHeight(status);
                    set(status, 2, y, contentWidth - 4, h);
                    y += h + 8;
                } else set(status, 2, y, contentWidth - 4, 0);
                int h = labelHeight(hints[0]);
                set(hints[0], 2, y, contentWidth - 4, h);
                y += h + 8;
                for (int i = 0; i < paths.length; i++) {
                    h = labelHeight(pathLabels[i]);
                    set(pathLabels[i], 2, y, contentWidth - 4, h);
                    fieldTop[i] = y + h + 2;
                    set(paths[i], 2, fieldTop[i], contentWidth - 4, 20);
                    y = fieldTop[i] + 28;
                }
                h = labelHeight(rawLabel);
                set(rawLabel, 2, y, contentWidth - 4, h);
                y += h + 2;
                set(raw, 2, y, Math.min(100, contentWidth - 4), 20);
                y += 28;
                h = labelHeight(ephemeralLabel);
                set(ephemeralLabel, 2, y, contentWidth - 4, h);
                y += h + 2;
                set(ephemeral, 2, y, Math.min(100, contentWidth - 4), 20);
                y += 28;
                for (int i = 1; i < hints.length; i++) {
                    h = labelHeight(hints[i]);
                    set(hints[i], 2, y, contentWidth - 4, h);
                    y += h + 6;
                }
                contentHeight = y;
            }

            @Override
            public ReadableDimension calcMinSize(GuiContainer<?> container) {
                return new Dimension(contentWidth, contentHeight);
            }
        });
        setLayout(new CustomLayout<GuiStorageSettings>() {
            @Override
            protected void layout(GuiStorageSettings container, int width, int height) {
                contentWidth = Math.max(8, Math.min(440, width - 16));
                viewportHeight = Math.max(1, height - 60);
                int x = (width - contentWidth) / 2;
                set(contents, x, 26, contentWidth, viewportHeight);
                int buttonWidth = (contentWidth - 6) / 2;
                set(apply, x, height - 26, buttonWidth, 20);
                set(cancel, x + buttonWidth + 6, height - 26, buttonWidth, 20);
                contents.setOffsetY(Math.max(0, Math.min(contents.getOffsetY(), contentHeight - viewportHeight)));
            }
        });
    }

    private int labelHeight(GuiLabel label) {
        var font = MCVer.getFontRenderer();
        return Math.max(1, font.getTextHandler().wrapLines(MCVer.literalText(label.getText()),
                Math.max(1, contentWidth - 4), Style.EMPTY).size()) * font.fontHeight;
    }

    private void reveal(int index) {
        int top = fieldTop[index];
        int offset = contents.getOffsetY();
        if (top < offset) contents.setOffsetY(top);
        else if (top + 20 > offset + viewportHeight) contents.setOffsetY(Math.max(0, top + 20 - viewportHeight));
    }

    private void updateRawInput() {
        if (raw.getSelected() != 0 && paths[RAW].isEnabled()) draftPaths[RAW] = paths[RAW].getText();
        if (raw.getSelected() == 0) paths[RAW].setText(draftPaths[RAW]);
        paths[RAW].setEnabled(raw.getSelected() == 0 && !validating && !applied);
        if (!paths[RAW].isEnabled()) paths[RAW].setFocused(false);
    }

    private void setEditing(boolean enabled) {
        for (GuiTextField field : paths) {
            field.setEnabled(enabled);
            if (!enabled) field.setFocused(false);
        }
        paths[RAW].setEnabled(enabled && raw.getSelected() == 0);
        raw.setEnabled(enabled);
        ephemeral.setEnabled(enabled);
        apply.setEnabled(enabled);
    }

    private void showStatus(String key, Object... args) {
        status.setI18nText(PREFIX + key, args);
        contents.setOffsetY(0);
    }

    private void applyDraft() {
        if (closed || validating || applied) return;
        Map<SettingsRegistry.SettingKey<?>, Object> draft = new LinkedHashMap<>();
        for (int i = 0; i < paths.length; i++) {
            if (i != RAW || raw.getSelected() == 0) draftPaths[i] = paths[i].getText();
            draft.put(PATH_KEYS.get(i), draftPaths[i]);
        }
        draft.put(Setting.RAW_BACKUP_ENABLED, raw.getSelected() == 0);
        draft.put(Setting.EPHEMERAL_CACHE, ephemeral.getSelected() == 0);
        // Check the key/value contract before any path IO or update; setAll also validates it.
        Map<SettingsRegistry.SettingKey<?>, Object> current = registry.snapshot();
        for (var entry : draft.entrySet()) {
            if (!current.containsKey(entry.getKey()) || !entry.getKey().getDefault().getClass().isInstance(entry.getValue())) {
                showStatus("invalidType", entry.getKey().getKey());
                return;
            }
        }
        Map<SettingsRegistry.SettingKey<?>, Object> changes = new LinkedHashMap<>(draft);
        changes.entrySet().removeIf(entry -> java.util.Objects.equals(initialValues.get(entry.getKey()), entry.getValue())
                || java.util.Objects.equals(current.get(entry.getKey()), entry.getValue()));
        if (changes.isEmpty()) {
            leave(); // An untouched or reverted draft must not validate paths, notify, or save.
            return;
        }
        Map<SettingsRegistry.SettingKey<?>, Object> validationBase = new LinkedHashMap<>();
        for (var key : draft.keySet()) validationBase.put(key, current.get(key));
        draft.putAll(validationBase);
        draft.putAll(changes);
        Path gameDir = getMinecraft().runDirectory.toPath();
        validating = true;
        setEditing(false);
        showStatus("validating");
        // Canonical ancestor checks can touch an offline filesystem; never perform them on the game thread.
        Thread worker = new Thread(() -> {
            Exception failure = null;
            try {
                ReplayStorageLayout.resolve(gameDir, (String) draft.get(Setting.RECORDING_PATH),
                        (String) draft.get(Setting.RAW_PATH), (String) draft.get(Setting.RECORDING_WORK_PATH),
                        (String) draft.get(Setting.TMP_PATH), (String) draft.get(Setting.CACHE_PATH),
                        (String) draft.get(Setting.TEMP_PATH), (Boolean) draft.get(Setting.EPHEMERAL_CACHE),
                        (Boolean) draft.get(Setting.RAW_BACKUP_ENABLED));
            } catch (Exception e) { failure = e; }
            Exception result = failure;
            try { getMinecraft().send(() -> finishApply(changes, validationBase, result)); }
            catch (RuntimeException e) { LOGGER.warn("Storage validation result could not be scheduled; no settings applied", e); }
        }, "replaymod-storage-validation");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException e) {
            validating = false;
            setEditing(true);
            showStatus("invalid", diagnostic(e));
        }
    }

    private void finishApply(Map<SettingsRegistry.SettingKey<?>, Object> draft,
            Map<SettingsRegistry.SettingKey<?>, Object> validationBase, Exception validationFailure) {
        if (closed) return; // Cancel/Esc/removed while the filesystem check was in flight.
        validating = false;
        if (validationFailure != null) {
            setEditing(true);
            showStatus("invalid", diagnostic(validationFailure));
            return;
        }
        Map<SettingsRegistry.SettingKey<?>, Object> current = registry.snapshot();
        if (validationBase.entrySet().stream().anyMatch(entry ->
                !java.util.Objects.equals(current.get(entry.getKey()), entry.getValue()))) {
            setEditing(true);
            showStatus("invalid", "Storage settings changed during validation; apply again.");
            return;
        }
        RuntimeException callbackFailure = null;
        try { registry.setAll(draft); }
        catch (RuntimeException e) {
            // setAll updates the whole map BEFORE callbacks. Do not call this a validation rollback.
            Map<SettingsRegistry.SettingKey<?>, Object> after = registry.snapshot();
            boolean batchPresent = draft.entrySet().stream().allMatch(entry ->
                    java.util.Objects.equals(after.get(entry.getKey()), entry.getValue()));
            if (!batchPresent) {
                applied = true;
                showStatus("updateUncertain", diagnostic(e));
                cancel.setI18nLabel("replaymod.gui.close");
                return;
            }
            callbackFailure = e;
            LOGGER.warn("Storage batch is in the registry, but a settings callback failed", e);
        }
        applied = true;
        cancel.setI18nLabel("replaymod.gui.close");
        try {
            registry.save(); // Existing void backend only logs IO failures; not a disk transaction receipt.
            if (callbackFailure == null) showStatus("applied");
            else showStatus("callbackFailed", diagnostic(callbackFailure));
        } catch (RuntimeException e) {
            showStatus("saveFailed", diagnostic(e));
        }
    }

    private static String diagnostic(Exception failure) {
        return failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
    }

    private void leave() {
        closed = true;
        getMinecraft().setScreen(parent);
    }

    @Override
    public boolean typeKey(ReadablePoint mouse, int keyCode, char keyChar, boolean ctrlDown, boolean shiftDown) {
        if (keyCode == MCVer.Keyboard.KEY_ESCAPE) { leave(); return true; }
        if (keyCode == MCVer.Keyboard.KEY_TAB || keyCode == MCVer.Keyboard.KEY_RETURN) {
            if (!validating && !applied) {
                int focused = -1;
                for (int i = 0; i < paths.length; i++) if (paths[i].isFocused()) focused = i;
                int direction = shiftDown ? -1 : 1;
                int next = focused < 0 ? (shiftDown ? paths.length - 1 : 0)
                        : Math.floorMod(focused + direction, paths.length);
                while (!paths[next].isEnabled()) next = Math.floorMod(next + direction, paths.length);
                for (GuiTextField field : paths) field.setFocused(false);
                paths[next].setFocused(true);
                reveal(next);
            }
            return true;
        }
        return false;
    }

    @Override
    public void close() { closed = true; }

    @Override
    protected GuiStorageSettings getThis() { return this; }
}
