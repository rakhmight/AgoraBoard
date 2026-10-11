package dev.agoraboard.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plain Spigot: no Adventure in the server, so chat goes through the BungeeCord chat API (the Adventure classes are
 * bundled in our jar) and item/inventory text becomes legacy §-coded strings. Only used when the server lacks Adventure.
 */
final class SpigotText implements TextOps {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private String legacy(Component component) {
        return LEGACY.serialize(flattenTranslatables(component));
    }

    /**
     * Item names are translatable components. Chat can keep them (the client translates), but a legacy string cannot,
     * so they become English names here ("item.minecraft.iron_ingot" -> "Iron Ingot").
     */
    private static Component flattenTranslatables(Component c) {
        Component result = c;
        if (c instanceof TranslatableComponent t) {
            String key = t.key();
            if (key.startsWith("item.minecraft.") || key.startsWith("block.minecraft.")) {
                result = Component.text(prettyName(key.substring(key.lastIndexOf('.') + 1))).style(t.style());
            }
        }
        if (result.children().isEmpty()) return result;
        List<Component> kids = new ArrayList<>(result.children().size());
        for (Component child : result.children()) kids.add(flattenTranslatables(child));
        return result.children(kids);
    }

    private static String prettyName(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    @Override
    public void send(CommandSender to, Component message) {
        if (to instanceof Player player) {
            player.spigot().sendMessage(BungeeComponentSerializer.get().serialize(message));
        } else {
            to.sendMessage(legacy(message));
        }
    }

    @Override
    public void broadcast(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) send(player, message);
        Bukkit.getConsoleSender().sendMessage(legacy(message));
    }

    @Override
    public Inventory createInventory(InventoryHolder holder, int size, Component title) {
        String text = legacy(title);
        try {
            return Bukkit.createInventory(holder, size, text);
        } catch (IllegalArgumentException tooLong) { // old servers cap titles at 32 characters
            return Bukkit.createInventory(holder, size, text.substring(0, Math.min(32, text.length())));
        }
    }

    @Override
    public InventoryHolder holderOf(Inventory inventory) {
        return inventory.getHolder();
    }

    @Override
    public void setName(ItemMeta meta, Component name) {
        meta.setDisplayName(legacy(name));
    }

    @Override
    public void setLore(ItemMeta meta, List<Component> lore) {
        List<String> lines = new ArrayList<>(lore.size());
        for (Component line : lore) lines.add(legacy(line));
        meta.setLore(lines);
    }

    @Override
    public List<Component> getLore(ItemMeta meta) {
        List<String> lines = meta.getLore();
        if (lines == null) return List.of();
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(LEGACY.deserialize(line));
        return out;
    }

    @Override
    public Component stackName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return LEGACY.deserialize(meta.getDisplayName());
        return Component.translatable(translationKey(stack.getType()));
    }

    @Override
    public String translationKey(Material material) {
        // Spigot has no stable translation-key API across versions; blocks and items follow this convention
        return (material.isBlock() ? "block" : "item") + ".minecraft." + material.getKey().getKey().toLowerCase(Locale.ROOT);
    }

    @Override
    public String serializeItem(ItemStack stack) {
        return ItemYaml.toText(stack);
    }

    @Override
    public ItemStack deserializeItem(String data) {
        if (!data.startsWith(ItemYaml.PREFIX)) {
            throw new IllegalStateException("this item was saved on Paper and cannot be read on Spigot");
        }
        return ItemYaml.fromText(data);
    }

    @Override
    public String platformName() {
        return "Spigot";
    }
}
