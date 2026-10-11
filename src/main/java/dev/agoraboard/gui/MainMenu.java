package dev.agoraboard.gui;

import dev.agoraboard.AgoraBoardPlugin;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/** Entry point: pick Announcements or Trades. */
public final class MainMenu extends Menu {
    private static final int ADS = 11;
    private static final int CLAIM = 13;
    private static final int TRADES = 15;

    public MainMenu(AgoraBoardPlugin plugin, Player viewer) {
        super(plugin, viewer, 27, plugin.messages().get("gui.main.title"));
    }

    @Override
    protected void render() {
        var service = plugin.service();
        var s = plugin.settings();
        long myAds = service.countAds(viewer.getUniqueId());
        long myTrades = service.countTrades(viewer.getUniqueId());

        inventory.setItem(ADS, Gui.item(Material.OAK_SIGN, msg().get("gui.main.ads.name"), withPrice(msg().lore("gui.main.ads.lore",
                Placeholder.unparsed("total", String.valueOf(service.ads().size())),
                Placeholder.unparsed("mine", String.valueOf(myAds)),
                Placeholder.unparsed("max", String.valueOf(s.adsMax))), service.adPrice(viewer))));

        inventory.setItem(TRADES, Gui.item(Material.EMERALD, msg().get("gui.main.trades.name"),
                withPrice(msg().lore(s.guiTrades ? "gui.main.trades.lore-gui" : "gui.main.trades.lore-natural",
                        Placeholder.unparsed("total", String.valueOf(service.trades().size())),
                        Placeholder.unparsed("mine", String.valueOf(myTrades)),
                        Placeholder.unparsed("max", String.valueOf(s.tradesMax))), service.tradePrice(viewer))));

        int pending = service.pendingItems(viewer.getUniqueId());
        if (pending > 0) {
            inventory.setItem(CLAIM, Gui.glow(Gui.item(Material.ENDER_CHEST, msg().get("gui.main.claim.name"),
                    msg().lore("gui.main.claim.lore", Placeholder.unparsed("amount", String.valueOf(pending))))));
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, Gui.filler());
        }
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        switch (e.getRawSlot()) {
            case ADS -> later(() -> new AdsMenu(plugin, viewer).open());
            case TRADES -> later(() -> new TradeMenu(plugin, viewer, null).open());
            case CLAIM -> {
                if (plugin.service().pendingItems(viewer.getUniqueId()) == 0) return;
                later(() -> new ClaimMenu(plugin, viewer).open());
            }
            default -> {
            }
        }
    }
}
