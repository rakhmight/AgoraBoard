package dev.agoraboard;

import dev.agoraboard.gui.AdsMenu;
import dev.agoraboard.gui.ClaimMenu;
import dev.agoraboard.gui.MainMenu;
import dev.agoraboard.gui.TradeMenu;
import dev.agoraboard.model.Announcement;
import dev.agoraboard.model.OfferSpec;
import dev.agoraboard.model.Trade;
import dev.agoraboard.model.Want;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class BoardCommand implements CommandExecutor, TabCompleter {
    private static final List<String> NEGOTIABLE_WORDS = List.of("negotiable", "договорная", "договорной");
    private static final List<String> MONEY_WORDS = List.of("money", "деньги", "$");
    private static final List<String> AMOUNT_HINTS = List.of("1", "8", "16", "32", "64");
    private static final List<String> MONEY_HINTS = List.of("100", "500", "1000", "10000");

    private static volatile List<String> itemNames;

    private final AgoraBoardPlugin plugin;

    public BoardCommand(AgoraBoardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            dispatch(sender, args);
        } catch (BoardException ex) {
            plugin.messages().send(sender, ex.key(), ex.resolvers());
        }
        return true;
    }

    private void dispatch(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("admin")) {
            admin(sender, args);
            return;
        }
        if (!(sender instanceof Player p)) throw new BoardException("players-only");
        if (!p.hasPermission("agoraboard.use")) throw new BoardException("no-permission");

        switch (sub) {
            case "" -> new MainMenu(plugin, p).open();
            case "help", "?" -> {
                require(p, "agoraboard.command.help");
                help(p);
            }
            case "ads", "list" -> {
                require(p, "agoraboard.command.ads");
                new AdsMenu(plugin, p).open();
            }
            case "trades", "market" -> {
                require(p, "agoraboard.command.trades");
                new TradeMenu(plugin, p, null).open();
            }
            case "ad" -> createAd(p, args); // agoraboard.ad is checked by the service
            case "trade" -> createTrade(p, args); // agoraboard.trade is checked by the service
            case "find" -> {
                require(p, "agoraboard.command.find");
                if (args.length < 2) throw new BoardException("usage.find");
                new TradeMenu(plugin, p, parseMaterial(args[1])).open();
            }
            case "claim" -> {
                require(p, "agoraboard.command.claim");
                if (plugin.service().pendingItems(p.getUniqueId()) == 0) throw new BoardException("claim.none");
                new ClaimMenu(plugin, p).open();
            }
            default -> throw new BoardException("unknown-sub");
        }
    }

    private static void require(CommandSender sender, String node) {
        if (!sender.hasPermission(node)) throw new BoardException("no-permission");
    }

    private static boolean hasAnyAdmin(CommandSender s) {
        return s.hasPermission("agoraboard.admin") || s.hasPermission("agoraboard.admin.reload")
                || s.hasPermission("agoraboard.admin.list") || s.hasPermission("agoraboard.admin.selftest")
                || s.hasPermission(BoardService.PERM_REMOVE_AD) || s.hasPermission(BoardService.PERM_REMOVE_TRADE);
    }

    private void help(Player p) {
        Messages m = plugin.messages();
        Settings s = plugin.settings();
        for (String key : List.of("help.header", "help.open", "help.ad", "help.trade", "help.find", "help.claim")) {
            m.sendRaw(p, m.get(key,
                    Placeholder.unparsed("categories", String.join(", ", s.categories.keySet())),
                    Placeholder.unparsed("days", String.valueOf(s.adsDays))));
        }
        BoardService service = plugin.service();
        if (service.chatAvailable(p)) {
            m.sendRaw(p, m.get("help.chat"));
            m.sendRaw(p, m.get(service.chatPrice(p) > 0 ? "help.chat-paid" : "help.chat-free",
                    Placeholder.unparsed("price", service.money(service.chatPrice(p))),
                    Placeholder.unparsed("minutes", String.valueOf(s.chatCooldownMinutes))));
        }
        // prices are only mentioned when there is something to pay
        if (service.adPrice(p) > 0) {
            m.sendRaw(p, m.get("help.price-ad", Placeholder.unparsed("price", service.money(service.adPrice(p)))));
        }
        if (service.tradePrice(p) > 0) {
            m.sendRaw(p, m.get("help.price-trade", Placeholder.unparsed("price", service.money(service.tradePrice(p)))));
        }
        if (hasAnyAdmin(p)) m.sendRaw(p, m.get("help.admin"));
    }

    private static final List<String> CHAT_FLAGS = List.of("-c", "-chat", "--chat");

    private static boolean hasChatFlag(String[] args) {
        return args.length > 1 && CHAT_FLAGS.contains(args[1].toLowerCase(Locale.ROOT));
    }

    /** Removes the "-c" flag that follows the subcommand so the rest parses the same with or without it. */
    private static String[] withoutChatFlag(String[] args) {
        String[] out = new String[args.length - 1];
        out[0] = args[0];
        System.arraycopy(args, 2, out, 1, args.length - 2);
        return out;
    }

    // ---------------------------------------------------------------- /board ad

    private void createAd(Player p, String[] args) {
        boolean chat = hasChatFlag(args);
        if (chat) args = withoutChatFlag(args);
        if (args.length < 3) {
            plugin.messages().send(p, "usage.ad",
                    Placeholder.unparsed("categories", String.join(", ", plugin.settings().categories.keySet())));
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        plugin.service().createAd(p, args[1], text, chat);
    }

    // ---------------------------------------------------------------- /board trade

    /**
     * /board trade &lt;wanted item&gt; [&lt;amount&gt;] (&lt;offered item&gt; &lt;amount&gt; ... | negotiable) [description]
     * Offers are read as long as they look like "item amount"; everything after is the description.
     * "negotiable" stands in place of the offers and makes the listing a plain announcement.
     * A lone "|" can be used to force the description to start.
     */
    private void createTrade(Player p, String[] args) {
        Settings s = plugin.settings();
        boolean chat = hasChatFlag(args);
        if (chat) args = withoutChatFlag(args);
        if (args.length < 3) throw new BoardException("usage.trade");

        // the amount may be left out for a negotiable listing: "/board trade diamond negotiable ..."
        Material needed = parseMaterial(args[1]);
        int amount = 0;
        int i = 2;
        if (isInteger(args[2])) {
            amount = parseAmount(args[2], s.maxAmount);
            i = 3;
        }
        Want want = new Want(needed, amount);
        List<OfferSpec> offers = new ArrayList<>();
        double money = 0;
        boolean negotiable = false;
        while (i < args.length) {
            String tok = args[i];
            if (tok.equals("|")) {
                i++;
                break;
            }
            if (offers.isEmpty() && money <= 0 && NEGOTIABLE_WORDS.contains(tok.toLowerCase(Locale.ROOT))) {
                negotiable = true; // stands in place of the offers; the rest is the description
                i++;
                break;
            }
            if (money <= 0 && MONEY_WORDS.contains(tok.toLowerCase(Locale.ROOT))
                    && i + 1 < args.length && isNumber(args[i + 1])) {
                money = parseMoney(args[i + 1]);
                i += 2;
                continue;
            }
            Material m = tryMaterial(tok);
            if (m != null && i + 1 < args.length && isInteger(args[i + 1])) {
                offers.add(new OfferSpec(m, parseAmount(args[i + 1], s.maxAmount)));
                i += 2;
                continue;
            }
            break;
        }
        if (offers.isEmpty() && money <= 0 && !negotiable) throw new BoardException("usage.trade");
        String description = i < args.length ? String.join(" ", Arrays.copyOfRange(args, i, args.length)) : "";
        plugin.service().createTrade(p, want, offers, money, negotiable, description, chat);
    }

    private static boolean isInteger(String s) {
        return s.matches("\\d{1,9}");
    }

    /** A money amount such as 250, 12.5 or 12,50. */
    private static boolean isNumber(String s) {
        return s.matches("\\d{1,12}([.,]\\d{1,2})?");
    }

    private static double parseMoney(String s) {
        try {
            double value = Double.parseDouble(s.replace(',', '.'));
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new BoardException("trade.bad-money", Placeholder.unparsed("input", s));
    }

    private static Material tryMaterial(String input) {
        Material m = Material.matchMaterial(input);
        return m == null || m.isLegacy() || m.isAir() || !m.isItem() ? null : m;
    }

    private Material parseMaterial(String input) {
        Material m = tryMaterial(input);
        if (m == null) throw new BoardException("trade.unknown-item", Placeholder.unparsed("input", input));
        return m;
    }

    private int parseAmount(String input, int max) {
        try {
            int n = Integer.parseInt(input);
            if (n >= 1 && n <= max) return n;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new BoardException("trade.bad-amount", Placeholder.unparsed("input", input),
                Placeholder.unparsed("max", String.valueOf(max)));
    }

    // ---------------------------------------------------------------- /board admin

    private void admin(CommandSender sender, String[] args) {
        if (!hasAnyAdmin(sender)) throw new BoardException("no-permission");
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        BoardService service = plugin.service();
        switch (sub) {
            case "reload" -> {
                require(sender, "agoraboard.admin.reload");
                plugin.reloadAll();
                plugin.messages().send(sender, "admin.reloaded");
            }
            case "selftest" -> {
                require(sender, "agoraboard.admin.selftest");
                sender.sendMessage("AgoraBoard self-test (server " + plugin.getServer().getBukkitVersion() + "):");
                for (String line : dev.agoraboard.gui.SelfTest.run(plugin, sender)) sender.sendMessage("  " + line);
            }
            case "list" -> {
                require(sender, "agoraboard.admin.list");
                for (Announcement a : service.ads()) {
                    plugin.messages().send(sender, "admin.list-ad",
                            Placeholder.unparsed("id", String.valueOf(a.id)),
                            Placeholder.unparsed("player", a.ownerName),
                            Placeholder.unparsed("text", a.text));
                }
                for (Trade t : service.trades()) {
                    plugin.messages().send(sender, "admin.list-trade",
                            Placeholder.unparsed("id", String.valueOf(t.id)),
                            Placeholder.unparsed("player", t.ownerName),
                            Placeholder.unparsed("wants", t.want.amount() + "x " + t.want.material().getKey().getKey()));
                }
            }
            case "remove" -> {
                if (!(sender instanceof Player p)) throw new BoardException("players-only");
                if (args.length < 4) throw new BoardException("usage.admin");
                int id;
                try {
                    id = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    throw new BoardException("usage.admin");
                }
                switch (args[2].toLowerCase(Locale.ROOT)) { // the remove permissions are checked by the service
                    case "ad" -> service.removeAd(p, id);
                    case "trade" -> service.removeTrade(p, id);
                    default -> throw new BoardException("usage.admin");
                }
            }
            default -> throw new BoardException("usage.admin");
        }
    }

    // ---------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("agoraboard.use")) return List.of();
        String cur = args[args.length - 1].toLowerCase(Locale.ROOT);

        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            for (String s : List.of("ads", "trades", "find", "claim", "help")) {
                if (sender.hasPermission("agoraboard.command." + s)) subs.add(s);
            }
            if (sender.hasPermission("agoraboard.ad")) subs.add("ad");
            if (sender.hasPermission("agoraboard.trade")) subs.add("trade");
            if (hasAnyAdmin(sender)) subs.add("admin");
            return filter(subs.stream(), cur);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("ad") || sub.equals("trade")) && args.length > 2 && hasChatFlag(args)) {
            args = withoutChatFlag(args); // "-c" is optional: complete the rest as if it were absent
        }
        switch (sub) {
            case "ad":
                if (args.length != 2) return List.of();
                List<String> cats = new ArrayList<>(filter(plugin.settings().categories.keySet().stream(), cur));
                if (sender instanceof Player ap && plugin.service().chatAvailable(ap) && "-c".startsWith(cur)) cats.add("-c");
                return cats;
            case "find":
                return args.length == 2 ? items(cur) : List.of();
            case "trade": {
                // <wanted item> [<amount>] then offers (<item> <amount> ...) or "negotiable", then a free-text description
                int pos = args.length - 1; // index of the token being typed
                if (pos == 1) {
                    List<String> out = new ArrayList<>(items(cur));
                    if (sender instanceof Player tp && plugin.service().chatAvailable(tp) && "-c".startsWith(cur)) out.add("-c");
                    return out;
                }
                if (pos == 2) {
                    List<String> out = new ArrayList<>(filter(AMOUNT_HINTS.stream(), cur));
                    out.addAll(filter(NEGOTIABLE_WORDS.stream(), cur));
                    return out;
                }
                switch (tradeState(args, pos)) {
                    case OFFER: {
                        List<String> out = new ArrayList<>(items(cur));
                        boolean first = args.length == (isInteger(args[2]) ? 4 : 3); // nothing typed after the wanted item yet
                        if (!moneyUsed(args, pos) && plugin.service().moneyAvailable()) out.addAll(filter(Stream.of("money"), cur));
                        out.addAll(filter(first ? Stream.of("negotiable") : Stream.of("|"), cur));
                        return out;
                    }
                    case AMOUNT:
                        return filter(AMOUNT_HINTS.stream(), cur);
                    case MONEY_AMOUNT:
                        return filter(MONEY_HINTS.stream(), cur);
                    default:
                        return List.of();
                }
            }
            case "admin":
                if (!hasAnyAdmin(sender)) return List.of();
                if (args.length == 2) {
                    List<String> out = new ArrayList<>();
                    for (String s : List.of("reload", "list", "selftest")) {
                        if (sender.hasPermission("agoraboard.admin." + s)) out.add(s);
                    }
                    if (sender.hasPermission(BoardService.PERM_REMOVE_AD) || sender.hasPermission(BoardService.PERM_REMOVE_TRADE)) {
                        out.add("remove");
                    }
                    return filter(out.stream(), cur);
                }
                if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
                    List<String> out = new ArrayList<>();
                    if (sender.hasPermission(BoardService.PERM_REMOVE_AD)) out.add("ad");
                    if (sender.hasPermission(BoardService.PERM_REMOVE_TRADE)) out.add("trade");
                    return filter(out.stream(), cur);
                }
                return List.of();
            default:
                return List.of();
        }
    }

    private enum TradeState { OFFER, AMOUNT, MONEY_AMOUNT, DESCRIPTION }

    /** True if a "money <amount>" part was already typed before {@code pos}. */
    private static boolean moneyUsed(String[] args, int pos) {
        for (int i = 2; i < pos; i++) if (MONEY_WORDS.contains(args[i].toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** Walks the offer tokens before {@code pos} the same way {@link #createTrade} does. */
    private static TradeState tradeState(String[] args, int pos) {
        int start = isInteger(args[2]) ? 3 : 2;
        int i = start;
        boolean moneySeen = false;
        while (i < pos) {
            String tok = args[i];
            if (tok.equals("|")) return TradeState.DESCRIPTION;
            if (i == start && NEGOTIABLE_WORDS.contains(tok.toLowerCase(Locale.ROOT))) return TradeState.DESCRIPTION;
            if (!moneySeen && MONEY_WORDS.contains(tok.toLowerCase(Locale.ROOT))) {
                if (i + 1 >= pos) return TradeState.MONEY_AMOUNT;
                if (!isNumber(args[i + 1])) return TradeState.DESCRIPTION;
                moneySeen = true;
                i += 2;
                continue;
            }
            if (tryMaterial(tok) == null) return TradeState.DESCRIPTION;
            if (i + 1 >= pos) return TradeState.AMOUNT;
            if (!isInteger(args[i + 1])) return TradeState.DESCRIPTION;
            i += 2;
        }
        return TradeState.OFFER;
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static List<String> items(String prefix) {
        List<String> all = itemNames;
        if (all == null) {
            all = Arrays.stream(Material.values())
                    .filter(m -> !m.isLegacy() && !m.isAir() && m.isItem())
                    .map(m -> m.getKey().getKey())
                    .sorted()
                    .toList();
            itemNames = all;
        }
        String p = prefix.startsWith("minecraft:") ? prefix.substring("minecraft:".length()) : prefix;
        return all.stream().filter(n -> n.startsWith(p)).limit(100).toList();
    }
}
