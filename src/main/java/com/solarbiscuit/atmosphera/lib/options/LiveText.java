package com.solarbiscuit.atmosphera.lib.options;

import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Optional;
import java.util.function.Supplier;

/** Text recomputed every time it is drawn, so option names and tooltips can show live status. */
final class LiveText implements ComponentContents {
    private final Supplier<String> text;

    private LiveText(Supplier<String> text) {
        this.text = text;
    }

    static MutableComponent of(Supplier<String> text) {
        return MutableComponent.create(new LiveText(text));
    }

    private String current() {
        try {
            return text.get();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    @Override
    public <T> Optional<T> visit(FormattedText.StyledContentConsumer<T> consumer, Style style) {
        return consumer.accept(style, current());
    }

    @Override
    public <T> Optional<T> visit(FormattedText.ContentConsumer<T> consumer) {
        return consumer.accept(current());
    }
}
