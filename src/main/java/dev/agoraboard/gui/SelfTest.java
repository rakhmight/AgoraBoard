package dev.agoraboard.gui;

import dev.agoraboard.AgoraBoardPlugin;
import dev.agoraboard.platform.Platform;
import dev.agoraboard.platform.Sched;
import dev.agoraboard.platform.TextOps;
import dev.agoraboard.util.Compat;
import dev.agoraboard.util.ItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "/board admin selftest": builds every kind of item, text and inventory the GUIs rely on, so a server of any supported
 * version shows immediately whether something is incompatible (instead of failing when a player clicks).
 */
public final class SelfTest {
    private SelfTest() {
    }

    private interface Check {
        void run() throws Exception;
    }

    public static List<String> run(AgoraBoardPlugin plugin, CommandSender sender) {
        List<String> report = new ArrayList<>();
        var m = plugin.messages();
        TextOps ops = Platform.text();

        report.add("INFO  platform: " + Platform.name() + " (" + (Platform.NATIVE_ADVENTURE ? "native Adventure" : "bundled Adventure")
                + "), Adventure classes from: " + codeSource(Component.class));

        check(report, "filler item (hidden tooltip)", () -> {
            if (Gui.filler().getType() != Material.GRAY_STAINED_GLASS_PANE) throw new IllegalStateException("wrong type");
        });
        check(report, "glowing item", () -> {
            ItemStack glowing = Gui.glow(new ItemStack(Material.DIAMOND));
            if (glowing.getType() != Material.DIAMOND) throw new IllegalStateException("wrong type");
        });
        check(report, "menu button with name and lore", () -> {
            ItemStack button = Gui.item(Material.OAK_SIGN, m.get("gui.main.title"), m.lore("gui.main.ads.lore",
                    Placeholder.unparsed("total", "1"), Placeholder.unparsed("mine", "1"), Placeholder.unparsed("max", "2")));
            if (ops.getLore(button.getItemMeta()).isEmpty()) throw new IllegalStateException("lore missing");
        });
        check(report, "vanilla item name (translatable)", () -> {
            if (ItemUtil.name(Material.DIAMOND) == null) throw new IllegalStateException("null");
        });
        check(report, "custom item name kept", () -> {
            ItemStack named = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = named.getItemMeta();
            ops.setName(meta, Component.text("Excalibur"));
            named.setItemMeta(meta);
            if (!Compat.itemName(named).toString().contains("Excalibur")) throw new IllegalStateException("custom name lost");
        });
        check(report, "plain English item name", () -> {
            if (!ItemUtil.plainName(Material.IRON_INGOT).equals("Iron Ingot")) throw new IllegalStateException("bad name");
        });
        check(report, "item save/load (escrow storage)", () -> {
            ItemStack original = new ItemStack(Material.DIAMOND, 5);
            ItemStack copy = ops.deserializeItem(ops.serializeItem(original));
            if (!copy.isSimilar(original) || copy.getAmount() != 5) throw new IllegalStateException("mismatch");
        });
        check(report, "plain-item matching and inventory math", () -> {
            ItemStack[] inv = new ItemStack[36];
            inv[0] = new ItemStack(Material.DIAMOND, 64);
            inv[1] = new ItemStack(Material.DIAMOND, 10);
            if (ItemUtil.count(inv, Material.DIAMOND) != 74) throw new IllegalStateException("count");
            if (!ItemUtil.remove(inv, Material.DIAMOND, 70) || ItemUtil.count(inv, Material.DIAMOND) != 4) {
                throw new IllegalStateException("remove");
            }
            if (ItemUtil.add(inv, new ItemStack(Material.IRON_INGOT, 100)) != 0) throw new IllegalStateException("add");
        });
        check(report, "custom inventory, holder lookup", () -> {
            InventoryHolder holder = new InventoryHolder() {
                private Inventory inv;

                @Override
                public Inventory getInventory() {
                    return inv;
                }

                {
                    inv = ops.createInventory(this, 54, m.get("gui.trade.title"));
                }
            };
            Inventory inv = holder.getInventory();
            inv.setItem(0, Gui.filler());
            inv.setItem(53, Gui.glow(new ItemStack(Material.EMERALD)));
            if (ops.holderOf(inv) != holder) throw new IllegalStateException("holder not found again");
        });
        check(report, "all texts parse and click/hover tags work", () -> {
            m.get("ad.contact", Placeholder.parsed("author", "Steve"), Placeholder.parsed("cmd", "msg"));
            m.get("trade.broadcast", Placeholder.unparsed("player", "Steve"), Placeholder.component("need", Component.text("x")),
                    Placeholder.component("offers", Component.text("y")), Placeholder.unparsed("description", ""));
            m.get("claim.join", Placeholder.unparsed("amount", "3"));
        });
        check(report, "send a rich message (click + hover)", () -> m.send(sender, "ad.contact",
                Placeholder.parsed("author", "Steve"), Placeholder.parsed("cmd", plugin.settings().messageCommand)));
        if (!Platform.NATIVE_ADVENTURE) {
            // the path used to send chat to a PLAYER on plain Spigot (the console path above is different)
            check(report, "BungeeCord chat conversion (player messages on Spigot)", () -> SpigotChecks.bungeeConversion(m));
        }
        check(report, "economy hook", () -> report.add("INFO  economy: "
                + (plugin.economy().available() ? "Vault found, 12.5 shows as " + plugin.economy().format(12.5) : "none (prices off)")));

        // scheduler checks finish a moment later, so they report on their own
        AtomicBoolean global = new AtomicBoolean();
        AtomicBoolean async = new AtomicBoolean();
        Sched.global(() -> global.set(true));
        Sched.async(() -> async.set(true));
        Sched.globalLater(() -> sender.sendMessage("  " + (global.get() && async.get() ? "OK    " : "FAIL  ")
                + "scheduler (global, async, delayed) on " + Platform.name()), 20L);
        return report;
    }

    private static String codeSource(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            return source == null ? "the server" : source.getLocation().toString();
        } catch (SecurityException ex) {
            return "unknown";
        }
    }

    private static void check(List<String> report, String what, Check check) {
        try {
            check.run();
            report.add("OK    " + what);
        } catch (Throwable t) {
            report.add("FAIL  " + what + " -> " + t);
        }
    }
}
