package com.tensec.aiscreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Provider + API-key settings, reached from the ⚙ Key button. Pick which AI the mod
 * talks to (Gemini / GPT / Claude); the key you paste saves to that provider's slot
 * in config/aiscreen.json. Each provider keeps its own key.
 *
 * Opened for one of two SEPARATE keysets: the G-menu panel's (MAIN) or the Custom-spawn
 * wish's (SPAWN) — editing one never touches the other. The spawn variant offers a
 * one-tap "copy my G-menu key here" for people who just use one key.
 */
public class ConfigScreen extends Screen {
    private final Screen parent;
    private final Config.Keyset keyset;
    private EditBox keyBox;

    public ConfigScreen(Screen parent) {
        this(parent, Config.Keyset.MAIN);
    }

    public ConfigScreen(Screen parent, Config.Keyset keyset) {
        super(Component.literal(keyset == Config.Keyset.SPAWN
                ? "Custom Spawn — its own AI key" : "AI Screen — Provider & API Key"));
        this.parent = parent;
        this.keyset = keyset;
    }

    @Override
    protected void init() {
        int w = Math.min(360, this.width - 40);
        int left = (this.width - w) / 2;
        int y = this.height / 2 - 10;

        Button providerButton = Button.builder(providerLabel(), b -> {
            Config.Provider next = switch (Config.getProvider(keyset)) {
                case GEMINI -> Config.Provider.OPENAI;
                case OPENAI -> Config.Provider.ANTHROPIC;
                default -> Config.Provider.GEMINI;
            };
            Config.setProvider(keyset, next);
            b.setMessage(providerLabel());
        }).bounds(left, y - 30, w, 20).build();
        addRenderableWidget(providerButton);

        keyBox = new EditBox(this.font, left, y, w, 20, Component.literal("key"));
        keyBox.setMaxLength(300);
        // MASKED WHILE TYPING, not only after saving. The box used to show the pasted key in full
        // until Save was pressed — on a stream, a recording, or a screenshot sent to the G panel.
        // Only the last four characters stay readable, which is enough to see the right key went in.
        keyBox.addFormatter((text, offset) -> {
            int visibleFrom = Math.max(0, keyBox.getValue().length() - 4);
            StringBuilder masked = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                masked.append(offset + i < visibleFrom ? '•' : text.charAt(i));
            }
            return net.minecraft.util.FormattedCharSequence.forward(masked.toString(), net.minecraft.network.chat.Style.EMPTY);
        });
        // Privacy: never prefill the saved key, so it can't be exposed by a screenshot
        // or stream of this screen. Leaving the box blank keeps the existing key.
        addRenderableWidget(keyBox);
        setInitialFocus(keyBox);

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
            String typed = keyBox.getValue().trim();
            if (!typed.isEmpty()) Config.setKey(keyset, typed); // blank = keep the current key
            this.minecraft.setScreen(parent);
        }).bounds(left, y + 30, w / 2 - 4, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreen(parent))
                .bounds(left + w / 2 + 4, y + 30, w / 2 - 4, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Clear this provider's key"), b -> {
            Config.setKey(keyset, "");
            keyBox.setValue("");
        }).bounds(left, y + 55, w, 20).build());

        // Spawn keyset only: most people have one key — let them reuse the G-menu one
        // with a tap instead of pasting it twice. Still two independent copies after.
        if (keyset == Config.Keyset.SPAWN && !Config.getKey(Config.Keyset.MAIN).isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("Copy my G-menu key + provider here"), b -> {
                Config.copyMainKeyToSpawn();
                providerButton.setMessage(providerLabel());
            }).bounds(left, y + 80, w, 20).build());
        }
    }

    private Component providerLabel() {
        return Component.literal("Provider: §b" + Config.getProvider(keyset).label + " §8(click to change)");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Config.Provider p = Config.getProvider(keyset);
        g.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 62, 0xFFFFFFFF);
        g.centeredText(this.font, Component.literal(Config.getKey(keyset).isEmpty()
                        ? KeySafe.unreadableCount() > 0
                                // TWO REASONS, TWO SENTENCES. "From another PC" is the usual one and is
                                // actionable; it is wrong when the Windows encryption itself could not be
                                // reached, because then the key may well have been saved right here.
                                ? KeySafe.dpapiMissing()
                                        ? "§eWindows key encryption isn't available in this install, so a saved key can't be"
                                                + " read. Paste it again — it will be stored unencrypted in your profile."
                                        : "§eA saved key came from another PC or account and can't be read here — paste it again."
                                : "§7Paste your " + p.label + " key (from " + p.keySite + "). Stored only on this PC."
                        : "§aA " + p.label + " key is saved (hidden). §7Leave blank to keep it, or paste to replace."),
                this.width / 2, this.height / 2 + 108, 0xFFAAAAAA);
        g.centeredText(this.font, Component.literal(keyset == Config.Keyset.SPAWN
                        ? "§8This key is ONLY for seed wishes — separate from the G-menu's key."
                        : "§8Stored on this PC only, " + KeySafe.protectionWords() + "."),
                this.width / 2, this.height / 2 + 122, 0xFF888888);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
