package pl.wordcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

abstract class WordcraftScreen extends Screen {
    protected static final int BG = -15722201;
    protected static final int PANEL = -15194054;
    protected static final int BORDER = -13547171;
    protected static final int TEXT = -1182209;
    protected static final int MUTED = -5653041;
    protected static final int MINT = -9117498;
    protected static final int AMBER = -800132;
    protected static final int BLUE = -7487489;
    protected final Screen parent;
    protected int scroll;
    protected int contentHeight;

    protected WordcraftScreen(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
    }

    protected Button button(String label, int x, int y, int w, Runnable action) {
        return this.themedButton(label, x, y, w, action, WordcraftScreen.Tone.QUIET);
    }

    protected Button themedButton(String label, int x, int y, int w, Runnable action, WordcraftScreen.Tone tone) {
        return this.themedButton(label, x, y, w, action, tone, null);
    }

    protected Button themedButton(String label, int x, int y, int w, Runnable action, WordcraftScreen.Tone tone, Item icon) {
        return this.addRenderableWidget(new WordcraftScreen.ThemedButton(label, x, y, w, action, tone, icon));
    }

    protected static void setTone(Button button, WordcraftScreen.Tone tone) {
        if (button instanceof WordcraftScreen.ThemedButton styled) {
            styled.tone = tone;
        }
    }

    protected void card(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y + 2, x + w, y + h + 2, 1610612736);
        g.fill(x, y, x + w, y + h, -15194054);
        g.outline(x, y, w, h, -13547171);
    }

    protected void itemIcon(GuiGraphicsExtractor g, Item item, int x, int y) {
        drawItemIcon(g, item, x, y);
    }

    private static void drawItemIcon(GuiGraphicsExtractor g, Item item, int x, int y) {
        String path;
        if (item == Items.CLOCK) {
            path = "item/clock_00";
        } else if (item == Items.COMPASS) {
            path = "item/compass_16";
        } else if (item == Items.LEVER) {
            path = "block/lever";
        } else if (item == Items.REDSTONE_TORCH) {
            path = "block/redstone_torch";
        } else if (item == Items.CHEST) {
            path = "item/chest_minecart";
        } else if (item == Items.SHIELD) {
            path = "item/iron_chestplate";
        } else {
            path = "item/" + BuiltInRegistries.ITEM.getKey(item).getPath();
        }

        Identifier texture = Identifier.withDefaultNamespace("textures/" + path + ".png");
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, 16, 16, 16, 16);
    }

    protected void section(GuiGraphicsExtractor g, String label, int x, int y, int w) {
        g.text(this.font, label, x, y, -5653041, false);
        int start = x + this.font.width(label) + 8;
        if (start < x + w) {
            g.fill(start, y + 4, x + w, y + 5, -13547171);
        }
    }

    protected void backdrop(GuiGraphicsExtractor g, String subtitle) {
        g.fillGradient(0, 0, this.width, this.height, -15722201, -15457224);
        g.fill(0, 0, this.width, 38, -15457999);
        g.fill(0, 0, this.width, 2, -9117498);
        g.fill(0, 38, this.width, 39, -13547171);
        this.itemIcon(g, Items.KNOWLEDGE_BOOK, 12, 11);
        g.text(this.font, this.title, 34, 10, -1182209, false);
        if (!subtitle.isBlank()) {
            g.text(this.font, this.font.plainSubstrByWidth(subtitle, Math.max(30, this.width - 48)), 34, 24, -5653041, false);
        }

        g.fill(0, this.height - 31, this.width, this.height, -15721943);
        g.fill(0, this.height - 32, this.width, this.height - 31, -14402994);
    }

    protected int paragraph(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
        for (FormattedCharSequence line : this.font.split(Component.literal(text), Math.max(40, w))) {
            g.text(this.font, line, x, y, color);
            y += 9 + 2;
        }

        return y;
    }

    protected void scrollControls(int bottom) {
        this.button("↑", this.width - 24, 42, 20, () -> this.scroll = Math.max(0, this.scroll - 60));
        this.button("↓", this.width - 24, Math.max(64, bottom - 20), 20, () -> this.scroll = Math.min(this.maxScroll(bottom), this.scroll + 60));
    }

    protected int maxScroll(int bottom) {
        return Math.max(0, this.contentHeight - (bottom - 42));
    }

    protected void scrollBar(GuiGraphicsExtractor g, int bottom) {
        this.scroll = Math.min(this.scroll, this.maxScroll(bottom));
        if (this.contentHeight > bottom - 42) {
            int track = Math.max(12, bottom - 90);
            int thumb = Math.max(10, track * (bottom - 42) / this.contentHeight);
            int top = 66 + (track - thumb) * this.scroll / Math.max(1, this.maxScroll(bottom));
            g.fill(this.width - 17, 65, this.width - 11, bottom - 23, -14469045);
            g.fill(this.width - 16, top, this.width - 12, top + thumb, -9117498);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        this.scroll = Math.max(0, Math.min(Math.max(0, this.contentHeight), this.scroll - (int)(vertical * 24.0)));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private static final class ThemedButton extends Button {
        private WordcraftScreen.Tone tone;
        private final Item icon;

        ThemedButton(String label, int x, int y, int w, Runnable action, WordcraftScreen.Tone tone, Item item) {
            super(x, y, w, 20, Component.literal(label), b -> action.run(), DEFAULT_NARRATION);
            this.tone = tone;
            this.icon = item;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            Font font = Minecraft.getInstance().font;
            boolean hovered = this.isHoveredOrFocused();

            int accent = switch (this.tone) {
                case PRIMARY, SELECTED -> -9117498;
                case SECONDARY -> -7487489;
                case AMBER -> -800132;
                case QUIET -> -8874323;
                case DANGER -> -1009005;
            };

            int background = switch (this.tone) {
                case PRIMARY, SELECTED -> -14332084;
                case SECONDARY -> -14270629;
                case AMBER -> -11451348;
                case QUIET -> -14470072;
                case DANGER -> -11455177;
            };
            if (!this.active) {
                background = -14996676;
                accent = -11639431;
            } else if (hovered) {
                background = switch (this.tone) {
                    case PRIMARY, SELECTED -> -13473441;
                    case SECONDARY -> -13346951;
                    case AMBER -> -9742542;
                    case QUIET -> -13350045;
                    case DANGER -> -9354427;
                };
            }

            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            g.fill(x, y + 1, x + w, y + h + 1, 1426063360);
            g.fill(x, y, x + w, y + h, background);
            g.outline(x, y, w, h, !this.active || !hovered && this.tone != WordcraftScreen.Tone.SELECTED ? -12889498 : accent);
            if (this.tone != WordcraftScreen.Tone.QUIET) {
                g.fill(x + 1, y + 1, x + 3, y + h - 1, accent);
            }

            int start = x + 5;
            int remaining = w - 10;
            if (this.icon != null && w >= 95) {
                WordcraftScreen.drawItemIcon(g, this.icon, x + 5, y + 2);
                start = x + 25;
                remaining = w - 30;
            }

            String full = this.getMessage().getString();
            String label = font.width(full) > remaining ? font.plainSubstrByWidth(full, Math.max(1, remaining - font.width("…"))) + "…" : full;
            int tx = this.icon != null && w >= 95 ? start : x + (w - font.width(label)) / 2;
            g.text(font, label, tx, y + (h - 9) / 2, this.active ? -1182209 : -8875869, false);
            if (hovered && this.active && font.width(full) > remaining) {
                g.setTooltipForNextFrame(font, this.getMessage(), mx, my);
            }
        }
    }

    protected enum Tone {
        PRIMARY,
        SECONDARY,
        AMBER,
        QUIET,
        DANGER,
        SELECTED;
    }
}
