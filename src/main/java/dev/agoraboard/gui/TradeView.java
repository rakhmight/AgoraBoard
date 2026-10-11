package dev.agoraboard.gui;

import dev.agoraboard.AgoraBoardPlugin;
import dev.agoraboard.Messages;
import dev.agoraboard.model.Trade;
import dev.agoraboard.platform.Platform;
import dev.agoraboard.model.Want;
import dev.agoraboard.util.Compat;
import dev.agoraboard.util.ItemUtil;
import dev.agoraboard.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Builds the icon for a trade listing: what the owner needs, what they give, the description, time left. */
final class TradeView {
    private TradeView() {
    }

    static boolean canPay(Trade t, ItemStack[] viewerStorage) {
        return ItemUtil.count(viewerStorage, t.want.material()) >= t.want.amount();
    }

    static ItemStack entry(AgoraBoardPlugin plugin, Player viewer, ItemStack[] viewerStorage, Trade t) {
        Messages m = plugin.messages();
        boolean own = t.owner.equals(viewer.getUniqueId());

        // the icon is the item the owner NEEDS, so the list reads like a shopping list
        Want need = t.want;
        ItemStack icon = new ItemStack(need.material(),
                Math.max(1, Math.min(need.amount(), need.material().getMaxStackSize())));
        ItemMeta meta = icon.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.addAll(m.lore("gui.trade.seller", Placeholder.unparsed("player", t.ownerName)));

        // what the owner needs
        if (need.amount() < 1) {
            lore.addAll(m.lore("gui.trade.want-any", Placeholder.component("item", ItemUtil.name(need.material()))));
        } else {
            String wantKey = own || !t.escrowed() ? "gui.trade.want-neutral"
                    : canPay(t, viewerStorage) ? "gui.trade.want-ok" : "gui.trade.want-no";
            lore.addAll(m.lore(wantKey,
                    Placeholder.component("item", ItemUtil.name(need.material())),
                    Placeholder.unparsed("amount", String.valueOf(need.amount()))));
        }

        // what the owner gives
        if (t.offers.isEmpty() && t.money <= 0) {
            lore.addAll(m.lore("gui.trade.offers-open"));
        } else {
            lore.addAll(m.lore("gui.trade.offers-header"));
            for (Want w : t.offers) {
                lore.addAll(m.lore("gui.trade.offer-line",
                        Placeholder.component("item", ItemUtil.name(w.material())),
                        Placeholder.unparsed("amount", String.valueOf(w.amount()))));
            }
            if (t.money > 0) {
                lore.addAll(m.lore("gui.trade.offer-money",
                        Placeholder.unparsed("price", plugin.service().money(t.money))));
            }
        }
        if (t.negotiable) lore.addAll(m.lore("gui.trade.negotiable-note"));

        if (!t.description.isEmpty()) {
            lore.addAll(m.lore("gui.trade.description-header"));
            for (String line : ItemUtil.wrap(t.description, 34)) {
                lore.add(Messages.noItalic(Component.text(line, NamedTextColor.WHITE)));
            }
        }

        lore.addAll(t.expires > 0
                ? m.lore("gui.trade.expires", Placeholder.unparsed("time", TimeUtil.left(t.expires, m)))
                : m.lore("gui.trade.no-expiry"));
        lore.add(Component.empty());
        if (own) {
            lore.addAll(m.lore(t.escrowed() ? "gui.trade.action-own-escrow" : "gui.trade.action-own"));
        } else if (t.escrowed()) {
            lore.addAll(m.lore(canPay(t, viewerStorage) ? "gui.trade.action-exchange" : "gui.trade.action-missing"));
        } else {
            lore.addAll(m.lore("gui.trade.action-contact"));
        }
        if (!own && plugin.service().canModerate(viewer, false)) {
            lore.addAll(m.lore("gui.admin-hint", Placeholder.unparsed("id", String.valueOf(t.id))));
        }

        Platform.text().setLore(meta, lore);
        if (own) Compat.glow(meta);
        icon.setItemMeta(meta);
        return icon;
    }
}
