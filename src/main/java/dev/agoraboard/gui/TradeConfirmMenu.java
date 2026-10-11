package dev.agoraboard.gui;

import dev.agoraboard.BoardException;
import dev.agoraboard.AgoraBoardPlugin;
import dev.agoraboard.Messages;
import dev.agoraboard.model.Trade;
import dev.agoraboard.platform.Platform;
import dev.agoraboard.util.ItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** "You pay X, you get Y" window. Rewards are shown as the real stacks, so enchantments are visible. */
final class TradeConfirmMenu extends Menu {
    private static final int PAY = 11;
    private static final int ARROW = 13;
    private static final int[] REWARD_SLOTS = {15, 16, 17, 24, 25, 26};
    private static final int INFO = 4;
    private static final int CONFIRM = 29;
    private static final int CANCEL = 33;

    private final int tradeId;
    private final Supplier<Menu> back;

    TradeConfirmMenu(AgoraBoardPlugin plugin, Player viewer, int tradeId, Supplier<Menu> back) {
        super(plugin, viewer, 36, plugin.messages().get("gui.confirm.title"));
        this.tradeId = tradeId;
        this.back = back;
    }

    @Override
    protected void render() {
        Trade t = plugin.service().findTrade(tradeId);
        inventory.setItem(CANCEL, Gui.item(Material.RED_CONCRETE, msg().get("gui.confirm.cancel"), List.of()));
        if (t == null || !t.escrowed()) {
            inventory.setItem(22, Gui.item(Material.BARRIER, msg().get("gui.confirm.gone"), List.of()));
            fillAll();
            return;
        }

        ItemStack[] storage = viewer.getInventory().getStorageContents();
        int have = ItemUtil.count(storage, t.want.material());
        boolean ok = have >= t.want.amount();

        ItemStack pay = Gui.item(t.want.material(),
                msg().get("gui.confirm.pay",
                        Placeholder.component("item", ItemUtil.name(t.want.material())),
                        Placeholder.unparsed("amount", String.valueOf(t.want.amount()))),
                msg().lore(ok ? "gui.confirm.pay-lore-ok" : "gui.confirm.pay-lore-no",
                        Placeholder.unparsed("have", String.valueOf(have)),
                        Placeholder.unparsed("amount", String.valueOf(t.want.amount()))));
        pay.setAmount(Math.max(1, Math.min(t.want.amount(), t.want.material().getMaxStackSize())));
        inventory.setItem(PAY, pay);
        inventory.setItem(ARROW, Gui.item(Material.ARROW, msg().get("gui.confirm.arrow"), List.of()));

        List<ItemUtil.Group> groups = ItemUtil.group(t.escrow);
        for (int i = 0; i < groups.size() && i < REWARD_SLOTS.length; i++) {
            ItemUtil.Group g = groups.get(i);
            ItemStack shown = g.sample().clone();
            shown.setAmount(Math.max(1, Math.min(g.total(), shown.getMaxStackSize())));
            ItemMeta meta = shown.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.addAll(Platform.text().getLore(meta));
            lore.addAll(msg().lore("gui.confirm.reward-lore",
                    Placeholder.unparsed("amount", String.valueOf(g.total()))));
            Platform.text().setLore(meta, lore);
            shown.setItemMeta(meta);
            inventory.setItem(REWARD_SLOTS[i], shown);
        }
        if (groups.size() > REWARD_SLOTS.length) {
            inventory.setItem(23, Gui.item(Material.CHEST, msg().get("gui.confirm.more",
                    Placeholder.unparsed("count", String.valueOf(groups.size() - REWARD_SLOTS.length))), List.of()));
        }
        if (t.money > 0) { // money offered on top of (or instead of) items
            int slot = groups.size() < REWARD_SLOTS.length ? REWARD_SLOTS[groups.size()] : 14;
            inventory.setItem(slot, Gui.glow(Gui.item(Material.GOLD_INGOT,
                    msg().get("gui.confirm.reward-money", Placeholder.unparsed("price", plugin.service().money(t.money))),
                    msg().lore("gui.confirm.reward-money-lore"))));
        }

        List<Component> info = new ArrayList<>(msg().lore("gui.confirm.info-lore", Placeholder.unparsed("player", t.ownerName)));
        if (!t.description.isEmpty()) {
            info.add(Component.empty());
            for (String line : ItemUtil.wrap(t.description, 34)) {
                info.add(Messages.noItalic(Component.text(line, NamedTextColor.WHITE)));
            }
        }
        inventory.setItem(INFO, Gui.item(Material.BOOK, msg().get("gui.confirm.info"), info));

        ItemStack confirm = Gui.item(Material.LIME_CONCRETE, msg().get("gui.confirm.yes"),
                msg().lore(ok ? "gui.confirm.yes-lore" : "gui.confirm.yes-lore-no"));
        inventory.setItem(CONFIRM, ok ? Gui.glow(confirm) : confirm);
        fillAll();
    }

    private void fillAll() {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, Gui.filler());
        }
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int slot = e.getRawSlot();
        if (slot == CANCEL) {
            later(() -> back.get().open());
        } else if (slot == CONFIRM) {
            try {
                plugin.service().executeTrade(viewer, tradeId);
                later(viewer::closeInventory);
            } catch (BoardException ex) {
                plugin.report(viewer, ex);
                refresh();
            }
        }
    }
}
