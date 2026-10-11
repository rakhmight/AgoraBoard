package dev.agoraboard.gui;

import dev.agoraboard.AgoraBoardPlugin;
import dev.agoraboard.BoardException;
import dev.agoraboard.platform.Platform;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Cancels every interaction with a board GUI. Deliberately never calls {@code getView()}: InventoryView became an interface
 * in 1.21, so compiled references to it break across versions. {@code getInventory()} is the top (GUI) inventory.
 */
public final class MenuListener implements Listener {
    private final AgoraBoardPlugin plugin;

    public MenuListener(AgoraBoardPlugin plugin) {
        this.plugin = plugin;
    }

    private static Menu menuOf(Inventory top) {
        return Platform.text().holderOf(top) instanceof Menu menu ? menu : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        Menu menu = menuOf(top);
        if (menu == null) return;
        // Nothing may move in or out of a board GUI: not by click, shift-click, hotbar swap or double-click.
        e.setCancelled(true);
        if (e.getClickedInventory() != top || !(e.getWhoClicked() instanceof Player player)) return;
        try {
            menu.onClick(e);
        } catch (BoardException ex) {
            plugin.report(player, ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (menuOf(e.getInventory()) != null) e.setCancelled(true);
    }
}
