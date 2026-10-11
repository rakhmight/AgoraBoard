package dev.agoraboard.gui;

import dev.agoraboard.AgoraBoardPlugin;
import dev.agoraboard.platform.Platform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Where payments from finished trades (and items that did not fit in an inventory) are collected, one click per stack. */
public final class ClaimMenu extends Menu {
    private static final int PER_PAGE = 45;
    private static final int PREV = 45, TAKE_ALL = 48, BACK = 49, PAGE = 50, NEXT = 53;

    private int page;
    /** slot -> the exact stack object in the claim box (identity is used to avoid stale clicks) */
    private final Map<Integer, ItemStack> slotToStack = new HashMap<>();

    public ClaimMenu(AgoraBoardPlugin plugin, Player viewer) {
        super(plugin, viewer, 54, plugin.messages().get("gui.claim.title"));
    }

    @Override
    protected void render() {
        slotToStack.clear();
        List<ItemStack> items = plugin.service().pending(viewer.getUniqueId());
        int pages = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(Math.max(page, 0), pages - 1);

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= items.size()) break;
            ItemStack stack = items.get(idx);
            ItemStack shown = stack.clone();
            ItemMeta meta = shown.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.addAll(Platform.text().getLore(meta));
            lore.addAll(msg().lore("gui.claim.entry-lore"));
            Platform.text().setLore(meta, lore);
            shown.setItemMeta(meta);
            inventory.setItem(i, shown);
            slotToStack.put(i, stack);
        }
        if (items.isEmpty()) {
            inventory.setItem(22, Gui.item(Material.BARRIER, msg().get("gui.claim.empty"), msg().lore("gui.claim.empty-lore")));
        }

        if (page > 0) inventory.setItem(PREV, Gui.item(Material.ARROW, msg().get("gui.prev"), List.of()));
        if (page < pages - 1) inventory.setItem(NEXT, Gui.item(Material.ARROW, msg().get("gui.next"), List.of()));
        inventory.setItem(PAGE, Gui.item(Material.PAPER, msg().get("gui.page",
                Placeholder.unparsed("page", String.valueOf(page + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages))), List.of()));
        inventory.setItem(BACK, Gui.item(Material.DARK_OAK_DOOR, msg().get("gui.back"), List.of()));
        if (!items.isEmpty()) {
            inventory.setItem(TAKE_ALL, Gui.glow(Gui.item(Material.HOPPER, msg().get("gui.claim.take-all"),
                    msg().lore("gui.claim.take-all-lore", Placeholder.unparsed("amount",
                            String.valueOf(plugin.service().pendingItems(viewer.getUniqueId())))))));
        }
        fillBottomRow();
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int slot = e.getRawSlot();
        if (slot < PER_PAGE) {
            ItemStack stack = slotToStack.get(slot);
            if (stack == null) return;
            try {
                plugin.service().claimOne(viewer, stack);
            } finally {
                refresh();
            }
            return;
        }
        switch (slot) {
            case PREV -> {
                if (page > 0) {
                    page--;
                    refresh();
                }
            }
            case NEXT -> {
                page++;
                refresh();
            }
            case TAKE_ALL -> {
                int got = plugin.service().claimAll(viewer);
                if (got > 0) plugin.messages().send(viewer, "claim.done", Placeholder.unparsed("amount", String.valueOf(got)));
                else plugin.messages().send(viewer, "claim.no-space");
                refresh();
            }
            case BACK -> later(() -> new MainMenu(plugin, viewer).open());
            default -> {
            }
        }
    }
}
