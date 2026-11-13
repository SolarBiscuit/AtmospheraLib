package com.solarbiscuit.atmosphera.lib.options;

import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.control.Control;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlElement;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarratableEntry.NarrationPriority;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.embeddedt.embeddium.gui.EmbeddiumVideoOptionsScreen;
import org.lwjgl.glfw.GLFW;

/**
 * The title row of a settings group in Video Settings (0.10.0): "▶ Title" while closed, "▼ Title" while open; a click
 * opens or closes the group (remembered, {@link GroupState}). When the group has a master switch, the row's option is
 * that switch and a tick box on the right toggles it (applied with the page's other changes).
 * <p>
 * Xenon is compiled against Minecraft's SRG names and this library against the official ones, so the element
 * implements Minecraft's interface methods itself (they are renamed to the SRG names when the jar is built).
 */
final class GroupHeaderControl implements Control<Boolean> {
    private final Option<Boolean> option;
    private final String groupId;
    private final Component title;
    private final boolean hasMaster;

    GroupHeaderControl(Option<Boolean> option, String groupId, Component title, boolean hasMaster) {
        this.option = option;
        this.groupId = groupId;
        this.title = title;
        this.hasMaster = hasMaster;
    }

    @Override
    public Option<Boolean> getOption() {
        return option;
    }

    @Override
    public ControlElement<Boolean> createElement(Dim2i dim) {
        return new Element(option, dim);
    }

    @Override
    public int getMaxWidth() {
        return 30;
    }

    /**
     * Re-lays the page on the next frame (not while the screen is dispatching this click). 0.10.1: Xenon opens either
     * Embeddium's screen or its Reese's-style one; both rebuild from the same pages. The old screen (Shift held while
     * opening) has no rebuild and shows every group open.
     */
    static void rebuildLater() {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.screen instanceof EmbeddiumVideoOptionsScreen screen) {
                screen.rebuildUI();
            } else if (mc.screen instanceof me.jellysquid.mods.sodium.client.gui.reesesoptions.client.gui.SodiumVideoOptionsScreen screen) {
                screen.rebuildUI();
            }
        });
    }

    private final class Element extends ControlElement<Boolean> {
        private boolean focus;

        Element(Option<Boolean> option, Dim2i dim) {
            super(option, dim);
        }

        private int boxX() {
            return dim.getLimitX() - 16;
        }

        private int boxY() {
            return dim.getCenterY() - 5;
        }

        private boolean inBox(double mouseX, double mouseY) {
            return hasMaster && mouseX >= boxX() && mouseX < boxX() + 10 && mouseY >= boxY() && mouseY < boxY() + 10;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            this.hovered = dim.containsCursor(mouseX, mouseY);
            drawRect(graphics, dim.x(), dim.y(), dim.getLimitX(), dim.getLimitY(), hovered ? 0xE0000000 : 0xB0101828);
            boolean open = GroupState.isOpen(groupId);
            Component label = Component.literal(open ? "▼ " : "▶ ").withStyle(ChatFormatting.GRAY)
                    .append(title.copy().withStyle(ChatFormatting.BOLD,
                            hasMaster && option.hasChanged() ? ChatFormatting.ITALIC : ChatFormatting.WHITE));
            drawString(graphics, label, dim.x() + 6, dim.getCenterY() - 4, 0xFFFFFFFF);
            if (hasMaster) {
                int x = boxX();
                int y = boxY();
                int color = option.isAvailable() ? 0xFFFFFFFF : 0xFFAAAAAA;
                if (Boolean.TRUE.equals(option.getValue())) {
                    drawRect(graphics, x + 2, y + 2, x + 8, y + 8, -3179338); // the tick box's own fill colour
                }
                drawBorder(graphics, x, y, x + 10, y + 10, color);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !dim.containsCursor(mouseX, mouseY)) {
                return false;
            }
            if (inBox(mouseX, mouseY)) {
                if (option.isAvailable()) {
                    option.setValue(!Boolean.TRUE.equals(option.getValue()));
                    playClickSound();
                }
                return true;
            }
            GroupState.toggle(groupId);
            playClickSound();
            rebuildLater();
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!focus) {
                return false;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                GroupState.toggle(groupId);
                playClickSound();
                rebuildLater();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_SPACE && hasMaster && option.isAvailable()) {
                option.setValue(!Boolean.TRUE.equals(option.getValue()));
                playClickSound();
                return true;
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            this.focus = focused;
            this.focused = focused;
        }

        @Override
        public boolean isFocused() {
            return focus;
        }

        @Override
        public NarrationPriority narrationPriority() {
            return focus ? NarrationPriority.FOCUSED : hovered ? NarrationPriority.HOVERED : NarrationPriority.NONE;
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, title);
        }
    }
}
