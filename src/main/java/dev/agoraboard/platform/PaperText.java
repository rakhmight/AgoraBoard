package dev.agoraboard.platform;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;

/**
 * Paper and its forks: the server already speaks Adventure, so components go straight to the API. This class is never
 * loaded on Spigot (it references Paper-only methods).
 */
final class PaperText implements TextOps {
    /** Paper 1.21+; older versions fall back to the display name or the translation key. */
    private static final Method EFFECTIVE_NAME = find(ItemStack.class, "effectiveName");

    private static Method find(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    @Override
    public void send(CommandSender to, Component message) {
        to.sendMessage(message);
    }

    @Override
    public void broadcast(Component message) {
        Bukkit.getServer().broadcast(message);
    }

    @Override
    public Inventory createInventory(InventoryHolder holder, int size, Component title) {
        return Bukkit.createInventory(holder, size, title);
    }

    @Override
    public InventoryHolder holderOf(Inventory inventory) {
        try {
            return inventory.getHolder(false); // Paper: does not snapshot block entities
        } catch (RuntimeException | LinkageError unsupported) {
            return inventory.getHolder(); // forks or builds without the flag
        }
    }

    @Override
    public void setName(ItemMeta meta, Component name) {
        meta.displayName(name);
    }

    @Override
    public void setLore(ItemMeta meta, List<Component> lore) {
        meta.lore(lore);
    }

    @Override
    public List<Component> getLore(ItemMeta meta) {
        List<Component> lore = meta.lore();
        return lore == null ? List.of() : lore;
    }

    @Override
    public Component stackName(ItemStack stack) {
        if (EFFECTIVE_NAME != null) {
            try {
                return (Component) EFFECTIVE_NAME.invoke(stack);
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return meta.displayName();
        return Component.translatable(translationKey(stack.getType()));
    }

    @Override
    public String translationKey(Material material) {
        return material.translationKey();
    }

    @Override
    public String serializeItem(ItemStack stack) {
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    @Override
    public ItemStack deserializeItem(String data) {
        if (data.startsWith(ItemYaml.PREFIX)) return ItemYaml.fromText(data);
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
    }

    @Override
    public String platformName() {
        return "Paper";
    }
}
