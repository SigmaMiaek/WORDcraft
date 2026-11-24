package pl.wordcraft.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

final class TextScreen extends WordcraftScreen {
    private final List<String> lines;
    private List<FormattedCharSequence> wrapped = List.of();

    TextScreen(String title, List<String> lines, Screen parent) {
        super(title, parent);
        this.lines = List.copyOf(lines);
    }

    @Override
    protected void init() {
        List<FormattedCharSequence> prepared = new ArrayList<>();

        for (String line : this.lines) {
            prepared.addAll(this.font.split(Component.literal(line), Math.max(40, this.width - 64)));
            prepared.add(Component.empty().getVisualOrderText());
        }

        this.wrapped = List.copyOf(prepared);
        this.contentHeight = this.wrapped.size() * (9 + 2) + 14;
        this.button("Wróć", this.width / 2 - 60, this.height - 26, 120, this::onClose);
        this.scrollControls(this.height - 36);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.backdrop(g, "Przewijanie: kółko myszy lub strzałki");
        this.card(g, 12, 44, this.width - 42, this.height - 84);
        g.enableScissor(18, 48, this.width - 35, this.height - 40);
        int lineHeight = 9 + 2;
        int first = Math.max(0, (this.scroll - 2) / lineHeight);
        int last = Math.min(this.wrapped.size(), first + (this.height - 78) / lineHeight + 3);

        for (int i = first; i < last; i++) {
            g.drawString(this.font, this.wrapped.get(i), 21, 51 + i * lineHeight - this.scroll, -1182209, false);
        }

        g.disableScissor();
        this.scrollBar(g, this.height - 36);
        super.render(g, mx, my, delta);
    }
}
