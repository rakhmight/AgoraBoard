package dev.agoraboard.platform;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/** Item stacks as YAML text, for servers without Paper's byte serialization (plain Spigot). */
final class ItemYaml {
    static final String PREFIX = "yml:";

    private ItemYaml() {
    }

    static String toText(ItemStack stack) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("item", stack);
        return PREFIX + Base64.getEncoder().encodeToString(y.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static ItemStack fromText(String data) {
        String yaml = new String(Base64.getDecoder().decode(data.substring(PREFIX.length())), java.nio.charset.StandardCharsets.UTF_8);
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(yaml);
        } catch (InvalidConfigurationException ex) {
            throw new IllegalStateException("broken item data: " + ex.getMessage(), ex);
        }
        ItemStack stack = y.getItemStack("item");
        if (stack == null) throw new IllegalStateException("item data has no item");
        return stack;
    }
}
