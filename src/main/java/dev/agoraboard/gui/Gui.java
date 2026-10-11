package dev.agoraboard.gui;

import dev.agoraboard.Messages;
import dev.agoraboard.platform.Platform;
import dev.agoraboard.util.Compat;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

final class Gui {
    private Gui() {
    }

    static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        Platform.text().setName(meta, Messages.noItalic(name));
        if (!lore.isEmpty()) Platform.text().setLore(meta, lore);
        stack.setItemMeta(meta);
        return stack;
    }

    static ItemStack filler() {
        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        Compat.hideTooltip(meta);
        stack.setItemMeta(meta);
        return stack;
    }

    static ItemStack glow(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        Compat.glow(meta);
        stack.setItemMeta(meta);
        return stack;
    }
}
