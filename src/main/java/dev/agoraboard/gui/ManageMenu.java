package dev.agoraboard.gui;

import dev.agoraboard.BoardException;
import dev.agoraboard.BoardService;
import dev.agoraboard.AgoraBoardPlugin;
import dev.agoraboard.model.Announcement;
import dev.agoraboard.model.Trade;
import dev.agoraboard.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** What you can do with your own listing: post it to the chat (again), or delete/cancel it. */
final class ManageMenu extends Menu {
    enum Kind { AD, TRADE }

    private static final int SUBJECT = 4;
    private static final int CHAT = 11;
    private static final int DELETE = 15;
    private static final int BACK = 22;

    private final Kind kind;
    private final int id;
    private final Supplier<ItemStack> subject;
    private final Supplier<Menu> back;

    ManageMenu(AgoraBoardPlugin plugin, Player viewer, Kind kind, int id, Supplier<ItemStack> subject, Supplier<Menu> back) {
        super(plugin, viewer, 27, plugin.messages().get("gui.manage.title"));
        this.kind = kind;
        this.id = id;
        this.subject = subject;
        this.back = back;
    }

    private boolean exists() {
        return kind == Kind.AD ? plugin.service().findAd(id) != null : plugin.service().findTrade(id) != null;
    }

    private UUID owner() {
        if (kind == Kind.AD) {
            Announcement a = plugin.service().findAd(id);
            return a == null ? null : a.owner;
        }
        Trade t = plugin.service().findTrade(id);
        return t == null ? null : t.owner;
    }

    private boolean canChat() {
        UUID owner = owner();
        return owner != null && plugin.service().canPostToChat(viewer, owner);
    }

    private boolean canDelete() {
        UUID owner = owner();
        return owner != null && plugin.service().canRemove(viewer, owner, kind == Kind.AD);
    }

    @Override
    protected void render() {
        BoardService service = plugin.service();
        if (!exists()) {
            inventory.setItem(SUBJECT, Gui.item(Material.BARRIER, msg().get("gui.manage.gone"), List.of()));
        } else {
            inventory.setItem(SUBJECT, subject.get());

            if (canChat()) {
                double price = service.chatPrice(viewer);
                long left = service.chatCooldownLeft(viewer);
                List<Component> lore;
                if (price > 0) {
                    lore = msg().lore("gui.manage.chat-lore-paid", Placeholder.unparsed("price", service.money(price)));
                } else if (left > 0) {
                    lore = msg().lore("gui.manage.chat-lore-cooldown", Placeholder.unparsed("time",
                            TimeUtil.left(System.currentTimeMillis() + left, msg())));
                } else {
                    lore = msg().lore("gui.manage.chat-lore-free", Placeholder.unparsed("minutes",
                            String.valueOf(plugin.settings().chatCooldownMinutes)));
                }
                ItemStack chat = Gui.item(Material.BELL, msg().get("gui.manage.chat"), lore);
                inventory.setItem(CHAT, price > 0 || left == 0 ? Gui.glow(chat) : chat);
            }

            if (canDelete()) {
                boolean held = kind == Kind.TRADE && plugin.service().findTrade(id).escrowed();
                inventory.setItem(DELETE, Gui.item(Material.TNT,
                        msg().get(held ? "gui.manage.cancel" : "gui.manage.delete"),
                        msg().lore(held ? "gui.manage.cancel-lore" : "gui.manage.delete-lore")));
            }
        }
        inventory.setItem(BACK, Gui.item(Material.DARK_OAK_DOOR, msg().get("gui.back-list"), List.of()));
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, Gui.filler());
        }
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        switch (e.getRawSlot()) {
            case CHAT -> {
                if (!exists() || !canChat()) return;
                try {
                    if (kind == Kind.AD) plugin.service().postAdToChat(viewer, id);
                    else plugin.service().postTradeToChat(viewer, id);
                    later(viewer::closeInventory); // so the post is visible in the chat right away
                } catch (BoardException ex) {
                    plugin.report(viewer, ex);
                    refresh();
                }
            }
            case DELETE -> {
                if (!exists() || !canDelete()) return;
                String title;
                Runnable action;
                if (kind == Kind.AD) {
                    title = "gui.ads.delete";
                    action = () -> plugin.service().removeAd(viewer, id);
                } else {
                    Trade t = plugin.service().findTrade(id);
                    title = t.escrowed() ? "gui.trade.delete-escrow" : "gui.trade.delete";
                    action = () -> plugin.service().removeTrade(viewer, id);
                }
                ItemStack shown = subject.get();
                later(() -> new ConfirmMenu(plugin, viewer, title, shown, action, back).open());
            }
            case BACK -> later(() -> back.get().open());
            default -> {
            }
        }
    }
}
