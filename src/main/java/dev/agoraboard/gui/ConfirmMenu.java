package dev.agoraboard.gui;

import dev.agoraboard.BoardException;
import dev.agoraboard.AgoraBoardPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Supplier;

/** Yes/No dialog showing the affected listing in the middle. */
final class ConfirmMenu extends Menu {
    private static final int YES = 11;
    private static final int SUBJECT = 13;
    private static final int NO = 15;

    private final ItemStack subject;
    private final String titleKey;
    private final Runnable onConfirm;
    private final Supplier<Menu> back;

    ConfirmMenu(AgoraBoardPlugin plugin, Player viewer, String titleKey, ItemStack subject, Runnable onConfirm, Supplier<Menu> back) {
        super(plugin, viewer, 27, plugin.messages().get(titleKey));
        this.titleKey = titleKey;
        this.subject = subject;
        this.onConfirm = onConfirm;
        this.back = back;
    }

    @Override
    protected void render() {
        inventory.setItem(YES, Gui.item(Material.LIME_CONCRETE, msg().get("gui.confirm.yes"), msg().lore(titleKey + "-lore")));
        inventory.setItem(SUBJECT, subject);
        inventory.setItem(NO, Gui.item(Material.RED_CONCRETE, msg().get("gui.confirm.no"), List.of()));
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, Gui.filler());
        }
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int slot = e.getRawSlot();
        if (slot == YES) {
            try {
                onConfirm.run();
            } catch (BoardException ex) {
                plugin.report(viewer, ex);
            }
            later(() -> back.get().open());
        } else if (slot == NO) {
            later(() -> back.get().open());
        }
    }
}
