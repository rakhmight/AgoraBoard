package dev.agoraboard;

import dev.agoraboard.platform.Platform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Text lookup for plugins/AgoraBoard/lang/&lt;language&gt;.yml. Missing keys fall back to the bundled file of the
 * same language (or Russian), so a half-translated custom file keeps working.
 */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] BUNDLED = {"ru", "en"};
    private static final String FALLBACK = "ru";

    private final AgoraBoardPlugin plugin;
    private YamlConfiguration cfg = new YamlConfiguration();

    public Messages(AgoraBoardPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File dir = new File(plugin.getDataFolder(), "lang");
        for (String code : BUNDLED) {
            if (!new File(dir, code + ".yml").exists()) plugin.saveResource("lang/" + code + ".yml", false);
        }

        String code = plugin.settings().language;
        File file = new File(dir, code + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("Language file lang/" + code + ".yml not found, using " + FALLBACK + ".");
            code = FALLBACK;
            file = new File(dir, code + ".yml");
        }

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream in = plugin.getResource("lang/" + code + ".yml");
        if (in == null) in = plugin.getResource("lang/" + FALLBACK + ".yml");
        if (in != null) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        cfg = loaded;
    }

    public String raw(String key) {
        String s = cfg.getString(key);
        return s == null ? "<red>[missing message: " + key + "]" : s;
    }

    public Component get(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), resolvers);
    }

    /** Parses an arbitrary MiniMessage string (used for category names from config.yml). */
    public Component mini(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    public List<Component> lore(String key, TagResolver... resolvers) {
        // a single string counts as a one-line lore
        List<String> lines = cfg.get(key) instanceof String single ? List.of(single) : cfg.getStringList(key);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(noItalic(MM.deserialize(line, resolvers)));
        return out;
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        Platform.text().send(to, prefixed(get(key, resolvers)));
    }

    /** A ready component without the plugin prefix (help pages). */
    public void sendRaw(CommandSender to, Component component) {
        Platform.text().send(to, component);
    }

    /** A message by key without the prefix. */
    public void sendPlain(CommandSender to, String key, TagResolver... resolvers) {
        Platform.text().send(to, get(key, resolvers));
    }

    public Component prefixed(Component body) {
        return Component.text().append(MM.deserialize(raw("prefix"))).append(body).build();
    }

    public static Component noItalic(Component c) {
        // not decorationIfAbsent: that method is missing from the Adventure version shipped with 1.18
        return c.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET
                ? c.decoration(TextDecoration.ITALIC, false) : c;
    }
}
