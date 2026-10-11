package dev.agoraboard.gui;

import dev.agoraboard.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;

/** Self-test pieces that only make sense on plain Spigot (they need the bundled BungeeCord serializer). */
final class SpigotChecks {
    private SpigotChecks() {
    }

    static void bungeeConversion(Messages m) throws ReflectiveOperationException {
        var parts = BungeeComponentSerializer.get().serialize(
                m.get("ad.contact", Placeholder.parsed("author", "Steve"), Placeholder.parsed("cmd", "msg")));
        if (parts.length == 0) throw new IllegalStateException("empty result");
        // the serializer returns lazy components; Bungee itself renders them to JSON when sending
        Class<?> bungeeJson = Class.forName("net.md_5.bungee.chat.ComponentSerializer");
        String json = (String) bungeeJson.getMethod("toString", net.md_5.bungee.api.chat.BaseComponent[].class)
                .invoke(null, (Object) parts);
        if (!json.contains("suggest_command") || !json.contains("/msg Steve")) {
            throw new IllegalStateException("click event lost: " + json);
        }
        if (!json.contains("hover")) throw new IllegalStateException("hover event lost: " + json);
    }
}