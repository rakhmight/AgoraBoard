package dev.agoraboard.gui;

import dev.agoraboard.AgoraBoardPlugin;
import dev.agoraboard.Messages;
import dev.agoraboard.model.Trade;
import dev.agoraboard.model.Want;
import dev.agoraboard.util.ItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Paginated list of trades with view filters (all / I can pay / mine) and an optional item search. */
public final class TradeMenu extends Menu {
    private enum View { ALL, AFFORDABLE, MINE }

    private static final int PER_PAGE = 45;
    private static final int PREV = 45, HELP = 46, VIEW = 47, ITEM_FILTER = 48, BACK = 49, PAGE = 50, NEXT = 53;

    private int page;
    private View view = View.ALL;
    private Material itemFilter;
    private final Map<Integer, Integer> slotToTrade = new HashMap<>();

    public TradeMenu(AgoraBoardPlugin plugin, Player viewer, Material itemFilter) {
        super(plugin, viewer, 54, plugin.messages().get("gui.trade.title"));
        this.itemFilter = itemFilter;
    }

    @Override
    protected void render() {
        slotToTrade.clear();
        ItemStack[] storage = viewer.getInventory().getStorageContents();
        List<Trade> list = plugin.service().trades().stream()
                .filter(t -> itemFilter == null || matches(t, itemFilter))
                .filter(t -> switch (view) {
                    case ALL -> true;
                    case MINE -> t.owner.equals(viewer.getUniqueId());
                    case AFFORDABLE -> t.escrowed() && !t.owner.equals(viewer.getUniqueId()) && TradeView.canPay(t, storage);
                })
                .sorted(Comparator.comparingLong((Trade t) -> t.created).reversed())
                .toList();
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(Math.max(page, 0), pages - 1);

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) break;
            Trade t = list.get(idx);
            inventory.setItem(i, TradeView.entry(plugin, viewer, storage, t));
            slotToTrade.put(i, t.id);
        }
        if (list.isEmpty()) {
            inventory.setItem(22, Gui.item(Material.BARRIER, msg().get("gui.trade.empty"), msg().lore("gui.trade.empty-lore")));
        }

        if (page > 0) inventory.setItem(PREV, Gui.item(Material.ARROW, msg().get("gui.prev"), List.of()));
        if (page < pages - 1) inventory.setItem(NEXT, Gui.item(Material.ARROW, msg().get("gui.next"), List.of()));
        inventory.setItem(PAGE, Gui.item(Material.PAPER, msg().get("gui.page",
                Placeholder.unparsed("page", String.valueOf(page + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages))), List.of()));
        inventory.setItem(BACK, Gui.item(Material.DARK_OAK_DOOR, msg().get("gui.back"), List.of()));
        inventory.setItem(HELP, Gui.item(Material.BOOK, msg().get("gui.trade.help.name"),
                withPrice(msg().lore(plugin.settings().guiTrades ? "gui.trade.help.lore-gui" : "gui.trade.help.lore-natural",
                        Placeholder.unparsed("max", String.valueOf(plugin.settings().tradesMax)),
                        Placeholder.unparsed("offers", String.valueOf(plugin.settings().maxOffers)),
                        Placeholder.unparsed("days", String.valueOf(plugin.settings().tradesDays))),
                        plugin.service().tradePrice(viewer))));

        List<Component> viewLore = new ArrayList<>();
        for (View v : View.values()) {
            Component label = msg().get("gui.trade.view." + v.name().toLowerCase());
            Component marker = v == view ? Component.text("▶ ", NamedTextColor.GREEN) : Component.text("   ");
            viewLore.add(Messages.noItalic(Component.text().append(marker).append(label).build()));
        }
        viewLore.addAll(msg().lore("gui.trade.view.hint"));
        inventory.setItem(VIEW, Gui.item(Material.COMPASS, msg().get("gui.trade.view.name"), viewLore));

        if (itemFilter != null) {
            inventory.setItem(ITEM_FILTER, Gui.glow(Gui.item(itemFilter, msg().get("gui.trade.item-filter.name",
                    Placeholder.component("item", ItemUtil.name(itemFilter))), msg().lore("gui.trade.item-filter.lore"))));
        }
        fillBottomRow();
    }

    private static boolean matches(Trade t, Material m) {
        if (t.want.material() == m) return true;
        for (Want w : t.offers) if (w.material() == m) return true;
        return false;
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int slot = e.getRawSlot();
        if (slot < PER_PAGE) {
            Integer id = slotToTrade.get(slot);
            if (id != null) clickTrade(e, id);
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
            case BACK -> later(() -> new MainMenu(plugin, viewer).open());
            case VIEW -> {
                View[] all = View.values();
                int idx = view.ordinal();
                idx = e.getClick().isRightClick() ? (idx - 1 + all.length) % all.length : (idx + 1) % all.length;
                view = all[idx];
                page = 0;
                refresh();
            }
            case ITEM_FILTER -> {
                if (itemFilter != null) {
                    itemFilter = null;
                    page = 0;
                    refresh();
                }
            }
            default -> {
            }
        }
    }

    private void clickTrade(InventoryClickEvent e, int id) {
        Trade t = plugin.service().findTrade(id);
        if (t == null) {
            refresh();
            return;
        }
        boolean own = t.owner.equals(viewer.getUniqueId());
        boolean moderate = !own && plugin.service().canModerate(viewer, false) && e.getClick() == ClickType.SHIFT_RIGHT;
        if (own || moderate) {
            later(() -> new ManageMenu(plugin, viewer, ManageMenu.Kind.TRADE, id,
                    () -> TradeView.entry(plugin, viewer, viewer.getInventory().getStorageContents(), t), () -> this).open());
            return;
        }
        if (!t.escrowed()) {
            later(() -> {
                viewer.closeInventory();
                plugin.messages().send(viewer, "trade.contact", Placeholder.parsed("author", t.ownerName),
                        Placeholder.parsed("cmd", plugin.settings().messageCommand));
            });
            return;
        }
        if (plugin.settings().confirmGui) {
            later(() -> new TradeConfirmMenu(plugin, viewer, id, () -> this).open());
        } else {
            plugin.service().executeTrade(viewer, id);
            later(this::open);
        }
    }
}
