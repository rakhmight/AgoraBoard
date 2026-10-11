package dev.agoraboard.gui;

import dev.agoraboard.AgoraBoardPlugin;
import dev.agoraboard.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import dev.agoraboard.platform.Platform;
import dev.agoraboard.platform.Sched;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.List;

/** Base for all board GUIs. Every click inside a Menu is cancelled by {@link MenuListener}. */
public abstract class Menu implements InventoryHolder {
    protected final AgoraBoardPlugin plugin;
    protected final Player viewer;
    protected final Inventory inventory;

    protected Menu(AgoraBoardPlugin plugin, Player viewer, int size, Component title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Platform.text().createInventory(this, size, title);
    }

    protected abstract void render();

    public abstract void onClick(InventoryClickEvent event);

    public void open() {
        refresh();
        viewer.openInventory(inventory);
    }

    protected void refresh() {
        inventory.clear();
        render();
    }

    protected Messages msg() {
        return plugin.messages();
    }

    /** Runs on the next tick: opening/closing inventories from inside a click handler is unreliable. */
    protected void later(Runnable task) {
        Sched.forPlayer(viewer, task);
    }

    /** Appends "Price: X" to a lore, but only when something has to be paid (price 0 is never shown). */
    protected List<Component> withPrice(List<Component> lore, double price) {
        if (price <= 0) return lore;
        List<Component> out = new ArrayList<>(lore);
        out.addAll(msg().lore("gui.price-line", Placeholder.unparsed("price", plugin.service().money(price))));
        return out;
    }

    protected void fillBottomRow() {
        for (int i = inventory.getSize() - 9; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, Gui.filler());
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
