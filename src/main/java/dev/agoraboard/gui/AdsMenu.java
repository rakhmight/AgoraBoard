package dev.agoraboard.gui;

import dev.agoraboard.AgoraBoardPlugin;
import dev.agoraboard.Messages;
import dev.agoraboard.model.Announcement;
import dev.agoraboard.model.Category;
import dev.agoraboard.util.ItemUtil;
import dev.agoraboard.util.TimeUtil;
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

/** Paginated list of announcements with a category filter and a "mine only" toggle. */
public final class AdsMenu extends Menu {
    private static final int PER_PAGE = 45;
    private static final int PREV = 45, HELP = 46, FILTER = 47, MINE = 48, BACK = 49, PAGE = 50, NEXT = 53;

    private int page;
    private String category; // null = all categories
    private boolean mine;
    private final Map<Integer, Integer> slotToAd = new HashMap<>();

    public AdsMenu(AgoraBoardPlugin plugin, Player viewer) {
        super(plugin, viewer, 54, plugin.messages().get("gui.ads.title"));
    }

    @Override
    protected void render() {
        slotToAd.clear();
        List<Announcement> list = plugin.service().ads().stream()
                .filter(a -> category == null || a.categoryId.equals(category))
                .filter(a -> !mine || a.owner.equals(viewer.getUniqueId()))
                .sorted(Comparator.comparingLong((Announcement a) -> a.created).reversed())
                .toList();
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(Math.max(page, 0), pages - 1);

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) break;
            Announcement a = list.get(idx);
            inventory.setItem(i, adItem(a));
            slotToAd.put(i, a.id);
        }
        if (list.isEmpty()) {
            inventory.setItem(22, Gui.item(Material.BARRIER, msg().get("gui.ads.empty"), msg().lore("gui.ads.empty-lore")));
        }

        if (page > 0) inventory.setItem(PREV, Gui.item(Material.ARROW, msg().get("gui.prev"), List.of()));
        if (page < pages - 1) inventory.setItem(NEXT, Gui.item(Material.ARROW, msg().get("gui.next"), List.of()));
        inventory.setItem(PAGE, Gui.item(Material.PAPER, msg().get("gui.page",
                Placeholder.unparsed("page", String.valueOf(page + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages))), List.of()));
        inventory.setItem(BACK, Gui.item(Material.DARK_OAK_DOOR, msg().get("gui.back"), List.of()));
        inventory.setItem(HELP, Gui.item(Material.BOOK, msg().get("gui.ads.help.name"), withPrice(msg().lore("gui.ads.help.lore",
                Placeholder.unparsed("categories", String.join(", ", plugin.settings().categories.keySet())),
                Placeholder.unparsed("days", String.valueOf(plugin.settings().adsDays)),
                Placeholder.unparsed("max", String.valueOf(plugin.settings().adsMax))), plugin.service().adPrice(viewer))));
        inventory.setItem(FILTER, filterItem());
        ItemStack mineItem = Gui.item(Material.WRITABLE_BOOK,
                msg().get(mine ? "gui.ads.mine.on" : "gui.ads.mine.off"), msg().lore("gui.ads.mine.lore"));
        inventory.setItem(MINE, mine ? Gui.glow(mineItem) : mineItem);
        fillBottomRow();
    }

    private ItemStack filterItem() {
        List<Component> lore = new ArrayList<>();
        lore.add(optionLine(category == null, msg().get("gui.ads.filter.all")));
        for (Category c : plugin.settings().categories.values()) {
            lore.add(optionLine(c.id().equals(category), msg().mini(c.name())));
        }
        lore.addAll(msg().lore("gui.ads.filter.hint"));
        return Gui.item(Material.HOPPER, msg().get("gui.ads.filter.name"), lore);
    }

    private static Component optionLine(boolean selected, Component label) {
        Component marker = selected ? Component.text("▶ ", NamedTextColor.GREEN) : Component.text("   ");
        return Messages.noItalic(Component.text().append(marker).append(label).build());
    }

    private ItemStack adItem(Announcement a) {
        Category cat = plugin.settings().category(a.categoryId);
        Component catName = cat != null ? msg().mini(cat.name()) : Component.text(a.categoryId);
        boolean own = a.owner.equals(viewer.getUniqueId());

        List<Component> lore = new ArrayList<>();
        for (String line : ItemUtil.wrap(a.text, 36)) {
            lore.add(Messages.noItalic(Component.text(line, NamedTextColor.WHITE)));
        }
        lore.add(Component.empty());
        lore.addAll(msg().lore("gui.ads.entry",
                Placeholder.unparsed("author", a.ownerName),
                Placeholder.unparsed("time", TimeUtil.left(a.expires, msg()))));
        lore.add(Component.empty());
        lore.addAll(msg().lore(own ? "gui.ads.action-own" : "gui.ads.action-contact"));
        if (!own && plugin.service().canModerate(viewer, true)) {
            lore.addAll(msg().lore("gui.admin-hint", Placeholder.unparsed("id", String.valueOf(a.id))));
        }
        ItemStack item = Gui.item(cat != null ? cat.icon() : Material.PAPER, catName, lore);
        return own ? Gui.glow(item) : item;
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int slot = e.getRawSlot();
        if (slot < PER_PAGE) {
            Integer id = slotToAd.get(slot);
            if (id != null) clickAd(e, id);
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
            case MINE -> {
                mine = !mine;
                page = 0;
                refresh();
            }
            case FILTER -> {
                List<String> options = new ArrayList<>();
                options.add(null);
                options.addAll(plugin.settings().categories.keySet());
                int idx = options.indexOf(category);
                if (e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT) idx = 0;
                else if (e.getClick().isRightClick()) idx = (idx - 1 + options.size()) % options.size();
                else idx = (idx + 1) % options.size();
                category = options.get(idx);
                page = 0;
                refresh();
            }
            default -> {
            }
        }
    }

    private void clickAd(InventoryClickEvent e, int id) {
        Announcement a = plugin.service().findAd(id);
        if (a == null) {
            refresh();
            return;
        }
        boolean own = a.owner.equals(viewer.getUniqueId());
        boolean moderate = !own && plugin.service().canModerate(viewer, true) && e.getClick() == ClickType.SHIFT_RIGHT;
        if (own || moderate) {
            later(() -> new ManageMenu(plugin, viewer, ManageMenu.Kind.AD, id, () -> adItem(a), () -> this).open());
        } else {
            later(() -> {
                viewer.closeInventory();
                plugin.messages().send(viewer, "ad.contact", Placeholder.parsed("author", a.ownerName),
                        Placeholder.parsed("cmd", plugin.settings().messageCommand));
            });
        }
    }
}
