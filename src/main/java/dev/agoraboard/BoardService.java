package dev.agoraboard;

import dev.agoraboard.economy.EconomyBridge;
import dev.agoraboard.model.Announcement;
import dev.agoraboard.model.Category;
import dev.agoraboard.model.OfferSpec;
import dev.agoraboard.model.Trade;
import dev.agoraboard.model.Want;
import dev.agoraboard.platform.Platform;
import dev.agoraboard.platform.Sched;
import dev.agoraboard.util.ItemUtil;
import dev.agoraboard.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * All board rules. User-facing failures are thrown as {@link BoardException}.
 * <p>
 * Threading: on Folia different regions call in concurrently, so every operation that changes board state is
 * {@code synchronized} on this service, and it only touches the inventory of the player who made the call. Anything meant
 * for another player (returned items, payments) goes through the claim box, notices go through that player's own scheduler.
 */
public final class BoardService {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    public static final String PERM_REMOVE_AD = "agoraboard.remove.ad";
    public static final String PERM_REMOVE_TRADE = "agoraboard.remove.trade";
    public static final String PERM_CHAT_OTHERS = "agoraboard.chat.others";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern TOKEN = Pattern.compile("\\{(player|category|text|message)}");

    private final AgoraBoardPlugin plugin;
    private final DataStore store;
    private final Map<UUID, Long> lastCreated = new ConcurrentHashMap<>();

    public BoardService(AgoraBoardPlugin plugin, DataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // ---------------------------------------------------------------- queries

    public List<Announcement> ads() {
        return Collections.unmodifiableList(store.ads);
    }

    public List<Trade> trades() {
        return Collections.unmodifiableList(store.trades);
    }

    public Announcement findAd(int id) {
        for (Announcement a : store.ads) if (a.id == id) return a;
        return null;
    }

    public Trade findTrade(int id) {
        for (Trade t : store.trades) if (t.id == id) return t;
        return null;
    }

    public long countAds(UUID owner) {
        return store.ads.stream().filter(a -> a.owner.equals(owner)).count();
    }

    public long countTrades(UUID owner) {
        return store.trades.stream().filter(t -> t.owner.equals(owner)).count();
    }

    /** Items waiting for the player (read-only view). */
    public List<ItemStack> pending(UUID player) {
        List<ItemStack> list = store.claims.get(player);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    public int pendingItems(UUID player) {
        return pending(player).stream().mapToInt(ItemStack::getAmount).sum();
    }

    // ---------------------------------------------------------------- announcements

    public synchronized Announcement createAd(Player p, String categoryInput, String rawText, boolean toChat) {
        Settings s = plugin.settings();
        requirePermission(p, "agoraboard.ad");
        Category cat = s.category(categoryInput);
        if (cat == null) {
            throw new BoardException("ad.category-unknown",
                    Placeholder.unparsed("input", categoryInput),
                    Placeholder.unparsed("list", String.join(", ", s.categories.keySet())));
        }
        String text = sanitize(rawText);
        if (text.length() < s.adsMinLength) {
            throw new BoardException("ad.too-short", Placeholder.unparsed("min", String.valueOf(s.adsMinLength)));
        }
        if (text.length() > s.adsMaxLength) {
            throw new BoardException("ad.too-long", Placeholder.unparsed("max", String.valueOf(s.adsMaxLength)));
        }
        checkBlocked(text);
        checkCooldown(p);
        if (!p.hasPermission("agoraboard.unlimited") && countAds(p.getUniqueId()) >= s.adsMax) {
            throw new BoardException("ad.limit", Placeholder.unparsed("max", String.valueOf(s.adsMax)));
        }

        double price = adPrice(p);
        charge(p, price); // everything that can fail has been checked already

        long now = System.currentTimeMillis();
        Announcement ad = new Announcement(store.nextAdId(), p.getUniqueId(), p.getName(), cat.id(), text,
                now, now + s.adsDays * DAY_MS);
        store.ads.add(ad);
        lastCreated.put(p.getUniqueId(), now);
        store.saveSoon();

        plugin.messages().send(p, "ad.created",
                Placeholder.unparsed("id", String.valueOf(ad.id)),
                Placeholder.parsed("category", cat.name()),
                Placeholder.unparsed("days", String.valueOf(s.adsDays)));
        announceCharge(p, price);
        if (toChat) tryPostToChat(p, () -> postAdToChat(p, ad.id));
        return ad;
    }

    public synchronized void removeAd(Player actor, int id) {
        Announcement ad = findAd(id);
        if (ad == null) throw new BoardException("ad.gone");
        boolean own = ad.owner.equals(actor.getUniqueId());
        if (!own && !actor.hasPermission(PERM_REMOVE_AD)) throw new BoardException("no-permission");
        store.ads.remove(ad);
        store.saveSoon();
        plugin.messages().send(actor, "ad.removed", Placeholder.unparsed("id", String.valueOf(id)));
        if (!own) notifyPlayer(ad.owner, "ad.removed-by-admin", Placeholder.unparsed("id", String.valueOf(id)));
    }

    // ---------------------------------------------------------------- trades

    /**
     * Creates a listing: the player NEEDS {@code want} and gives {@code specs} (items) and/or {@code money} in return.
     * GUI mode: the offered items are taken from the player and the offered money is reserved (withdrawn) right now, both
     * held by the board. NATURAL mode and negotiable listings: nothing is taken, the offer is only displayed.
     */
    public synchronized Trade createTrade(Player p, Want want, List<OfferSpec> specs, double money, boolean negotiable,
                                          String rawDescription, boolean toChat) {
        Settings s = plugin.settings();
        requirePermission(p, "agoraboard.trade");
        // a negotiable listing is only an announcement: nothing is taken, the amount may be omitted, offers are optional
        if (specs.isEmpty() && money <= 0 && !negotiable) throw new BoardException("usage.trade");
        if (!negotiable && want.amount() < 1) throw new BoardException("trade.need-amount");
        boolean hold = s.guiTrades && !negotiable;
        if (specs.size() > s.maxOffers) {
            throw new BoardException("trade.too-many-offers", Placeholder.unparsed("max", String.valueOf(s.maxOffers)));
        }
        money = checkMoney(money, hold);
        String description = sanitize(rawDescription);
        if (description.length() > s.maxDescription) {
            throw new BoardException("trade.desc-too-long", Placeholder.unparsed("max", String.valueOf(s.maxDescription)));
        }
        if (!description.isEmpty()) checkBlocked(description);
        checkCooldown(p);
        if (!p.hasPermission("agoraboard.unlimited") && countTrades(p.getUniqueId()) >= s.tradesMax) {
            throw new BoardException("trade.limit", Placeholder.unparsed("max", String.valueOf(s.tradesMax)));
        }

        ItemStack[] work = ItemUtil.cloneContents(p.getInventory().getStorageContents());
        List<ItemStack> taken = new ArrayList<>();
        Map<Material, Integer> offered = new LinkedHashMap<>();

        for (OfferSpec spec : specs) {
            offered.merge(spec.material(), spec.amount(), Integer::sum);
            if (!hold) continue;
            List<ItemStack> part = ItemUtil.take(work, spec.material(), spec.amount());
            if (part == null) {
                throw new BoardException("trade.not-enough-offer",
                        Placeholder.component("item", ItemUtil.name(spec.material())),
                        Placeholder.unparsed("amount", String.valueOf(spec.amount())),
                        Placeholder.unparsed("have", String.valueOf(countAny(p, spec.material()))));
            }
            taken.addAll(part);
        }
        if (offered.containsKey(want.material())) {
            throw new BoardException("trade.same-item", Placeholder.component("item", ItemUtil.name(want.material())));
        }

        List<Want> offers = new ArrayList<>();
        offered.forEach((m, n) -> offers.add(new Want(m, n)));

        long now = System.currentTimeMillis();
        long expires = s.tradesDays > 0 ? now + s.tradesDays * DAY_MS : 0;
        List<ItemStack> escrow = hold ? taken : List.of();
        Trade trade = new Trade(store.nextTradeId(), p.getUniqueId(), p.getName(), want, offers, escrow, money, hold,
                negotiable, description, now, expires);

        // one withdrawal for the posting fee and the reserved money, so it is all-or-nothing
        double fee = tradePrice(p);
        double reserved = hold ? money : 0;
        charge(p, fee + reserved); // may throw: nothing has been applied yet
        if (hold) p.getInventory().setStorageContents(work); // point of no return
        store.trades.add(trade);
        lastCreated.put(p.getUniqueId(), now);
        store.saveNow(); // items and money may have just left the player

        plugin.messages().send(p, negotiable ? "trade.created-negotiable" : hold ? "trade.created-escrow" : "trade.created-display",
                Placeholder.unparsed("id", String.valueOf(trade.id)),
                Placeholder.component("items", describeOffer(offers, money)));
        announceCharge(p, fee);
        if (reserved > 0) plugin.messages().send(p, "economy.reserved", Placeholder.unparsed("price", money(reserved)));
        if (toChat) tryPostToChat(p, () -> postTradeToChat(p, trade.id));
        return trade;
    }

    /** Validates an offered amount of money and returns it rounded to cents. */
    private double checkMoney(double money, boolean reserve) {
        if (money <= 0) return 0;
        Settings s = plugin.settings();
        if (!s.moneyOffers) throw new BoardException("trade.money-disabled");
        if (reserve && !(s.economyEnabled && plugin.economy().available())) throw new BoardException("trade.money-unavailable");
        double rounded = Math.round(money * 100.0) / 100.0;
        if (rounded < s.moneyMin) {
            throw new BoardException("trade.money-too-small", Placeholder.unparsed("min", money(s.moneyMin)));
        }
        if (s.moneyMax > 0 && rounded > s.moneyMax) {
            throw new BoardException("trade.money-too-large", Placeholder.unparsed("max", money(s.moneyMax)));
        }
        return rounded;
    }

    public synchronized void removeTrade(Player actor, int id) {
        Trade trade = findTrade(id);
        if (trade == null) throw new BoardException("trade.gone");
        boolean own = trade.owner.equals(actor.getUniqueId());
        if (!own && !actor.hasPermission(PERM_REMOVE_TRADE)) throw new BoardException("no-permission");

        releaseMoney(trade); // first: if the refund cannot be made the listing stays untouched
        store.trades.remove(trade);
        if (trade.escrowed() && !trade.escrow.isEmpty()) returnItems(trade.owner, cloneAll(trade.escrow), actor);
        store.saveNow();
        plugin.messages().send(actor, trade.escrowed() ? "trade.removed-returned" : "trade.removed",
                Placeholder.unparsed("id", String.valueOf(id)));
        if (!own) notifyPlayer(trade.owner, "trade.removed-by-admin", Placeholder.unparsed("id", String.valueOf(id)));
    }

    /** Pays reserved money back to the owner. Throws (changing nothing) if the economy cannot do it. */
    private void releaseMoney(Trade trade) {
        if (!trade.held || trade.money <= 0) return;
        EconomyBridge eco = plugin.economy();
        if (!eco.available()) throw new BoardException("economy.unavailable");
        if (!eco.refund(Bukkit.getOfflinePlayer(trade.owner), trade.money)) {
            plugin.getLogger().severe("Could not refund " + trade.money + " to " + trade.ownerName + " (trade #" + trade.id + ")");
            throw new BoardException("economy.failed");
        }
    }

    /**
     * Accepts an automatic trade: the taker pays {@code trade.want} and receives the held items and money right away.
     * The owner's payment goes to the claim box. Everything is computed on a copy of the taker's inventory first,
     * so a failure never loses or duplicates items.
     */
    public synchronized void executeTrade(Player taker, int tradeId) {
        requirePermission(taker, "agoraboard.trade");
        Trade trade = findTrade(tradeId);
        if (trade == null) throw new BoardException("trade.gone");
        if (!trade.escrowed()) throw new BoardException("trade.not-automatic");
        if (trade.owner.equals(taker.getUniqueId())) throw new BoardException("trade.own");

        Want want = trade.want;
        ItemStack[] work = ItemUtil.cloneContents(taker.getInventory().getStorageContents());
        if (!ItemUtil.remove(work, want.material(), want.amount())) {
            throw new BoardException("trade.missing",
                    Placeholder.component("item", ItemUtil.name(want.material())),
                    Placeholder.unparsed("amount", String.valueOf(want.amount())),
                    Placeholder.unparsed("have", String.valueOf(ItemUtil.count(
                            taker.getInventory().getStorageContents(), want.material()))));
        }
        for (ItemStack reward : trade.escrow) {
            if (ItemUtil.add(work, reward.clone()) > 0) throw new BoardException("trade.no-space");
        }
        if (trade.money > 0) { // pay the money first: if the economy refuses, nothing has changed yet
            EconomyBridge eco = plugin.economy();
            if (!eco.available()) throw new BoardException("economy.unavailable");
            if (!eco.deposit(taker, trade.money)) throw new BoardException("economy.failed");
        }

        // point of no return: apply
        taker.getInventory().setStorageContents(work);
        store.trades.remove(trade);
        addToClaims(trade.owner, ItemUtil.stacks(want.material(), want.amount()));
        store.saveNow();

        plugin.getLogger().info("Trade #" + trade.id + ": " + taker.getName() + " paid " + want.amount() + "x "
                + want.material() + " to " + trade.ownerName + " for " + describePlain(trade.offers, trade.money));

        plugin.messages().send(taker, "trade.done",
                Placeholder.component("items", describeOffer(trade.offers, trade.money)),
                Placeholder.component("give", ItemUtil.name(want.material())),
                Placeholder.unparsed("give_amount", String.valueOf(want.amount())));
        notifyPlayer(trade.owner, "trade.done-owner",
                Placeholder.unparsed("player", taker.getName()),
                Placeholder.unparsed("id", String.valueOf(trade.id)),
                Placeholder.component("get", ItemUtil.name(want.material())),
                Placeholder.unparsed("get_amount", String.valueOf(want.amount())));
    }

    // ---------------------------------------------------------------- prices

    /** Price the player actually pays: 0 when the setting is 0, the economy is off/missing, or the player is exempt. */
    private double effectivePrice(Player p, double base) {
        if (base <= 0 || !plugin.settings().economyEnabled || p.hasPermission("agoraboard.unlimited")) return 0;
        return plugin.economy().available() ? base : 0;
    }

    public double adPrice(Player p) {
        return effectivePrice(p, plugin.settings().adsPrice);
    }

    public double tradePrice(Player p) {
        return effectivePrice(p, plugin.settings().tradesPrice);
    }

    public double chatPrice(Player p) {
        return effectivePrice(p, plugin.settings().chatPrice);
    }

    /** Whether the economy can currently be used for money offers. */
    public boolean moneyAvailable() {
        Settings s = plugin.settings();
        return s.moneyOffers && s.economyEnabled && plugin.economy().available();
    }

    /** Amount formatted by the economy plugin ("$12.50"). */
    public String money(double amount) {
        return plugin.economy().format(amount);
    }

    private void charge(Player p, double amount) {
        if (amount <= 0) return;
        EconomyBridge eco = plugin.economy();
        if (!eco.withdraw(p, amount)) {
            throw new BoardException("economy.not-enough",
                    Placeholder.unparsed("price", eco.format(amount)),
                    Placeholder.unparsed("balance", eco.format(eco.balance(p))));
        }
    }

    private void announceCharge(Player p, double amount) {
        if (amount > 0) plugin.messages().send(p, "economy.charged", Placeholder.unparsed("price", money(amount)));
    }

    // ---------------------------------------------------------------- chat posts

    public boolean chatAvailable(Player p) {
        return plugin.settings().chatEnabled && p.hasPermission("agoraboard.chat");
    }

    /** May this player post the listing of {@code owner} to the chat? Own listings need only the chat permission. */
    public boolean canPostToChat(Player p, UUID owner) {
        return chatAvailable(p) && (owner.equals(p.getUniqueId()) || p.hasPermission(PERM_CHAT_OTHERS));
    }

    /** May this player delete the listing of {@code owner}? Own listings can always be deleted. */
    public boolean canRemove(Player p, UUID owner, boolean ad) {
        return owner.equals(p.getUniqueId()) || p.hasPermission(ad ? PERM_REMOVE_AD : PERM_REMOVE_TRADE);
    }

    /** Does the player have any right over other people's listings (shown as a hint in the lists)? */
    public boolean canModerate(Player p, boolean ad) {
        return p.hasPermission(ad ? PERM_REMOVE_AD : PERM_REMOVE_TRADE)
                || (chatAvailable(p) && p.hasPermission(PERM_CHAT_OTHERS));
    }

    /**
     * Milliseconds until the player may post to the chat for free again. 0 when allowed. Paid posts, exempt players and a
     * cooldown of 0 have no wait. The cooldown is shared by all of the player's listings.
     */
    public long chatCooldownLeft(Player p) {
        Settings s = plugin.settings();
        if (chatPrice(p) > 0 || p.hasPermission("agoraboard.unlimited") || s.chatCooldownMinutes <= 0) return 0;
        Long last = store.chatPosts.get(p.getUniqueId());
        if (last == null) return 0;
        return Math.max(0, last + s.chatCooldownMinutes * 60_000L - System.currentTimeMillis());
    }

    public synchronized void postAdToChat(Player p, int adId) {
        Announcement ad = findAd(adId);
        if (ad == null) throw new BoardException("ad.gone");
        boolean own = ad.owner.equals(p.getUniqueId());
        if (!own && !p.hasPermission(PERM_CHAT_OTHERS)) throw new BoardException("no-permission");
        Messages m = plugin.messages();
        Category cat = plugin.settings().category(ad.categoryId);
        Component catName = cat != null ? m.mini(cat.name()) : Component.text(ad.categoryId);
        String catPlain = PLAIN.serialize(catName);

        Component rich = m.get("ad.broadcast",
                Placeholder.unparsed("player", ad.ownerName),
                Placeholder.component("category", catName),
                Placeholder.unparsed("text", ad.text));
        String withName = PLAIN.serialize(m.get("chat.plain-ad",
                Placeholder.unparsed("player", ad.ownerName),
                Placeholder.unparsed("category", catPlain),
                Placeholder.unparsed("text", ad.text)));
        String asPlayer = PLAIN.serialize(m.get("chat.plain-ad-self",
                Placeholder.unparsed("category", catPlain),
                Placeholder.unparsed("text", ad.text)));
        publish(p, rich, withName, own ? asPlayer : withName, catPlain, ad.text);
    }

    public synchronized void postTradeToChat(Player p, int tradeId) {
        Trade t = findTrade(tradeId);
        if (t == null) throw new BoardException("trade.gone");
        boolean own = t.owner.equals(p.getUniqueId());
        if (!own && !p.hasPermission(PERM_CHAT_OTHERS)) throw new BoardException("no-permission");
        Messages m = plugin.messages();

        Component need = t.want.amount() > 0 ? describe(List.of(t.want)) : ItemUtil.name(t.want.material());
        boolean open = t.offers.isEmpty() && t.money <= 0;
        Component offers = open ? m.get("chat.offers-negotiable") : describeOffer(t.offers, t.money);
        String desc = t.description.isEmpty() ? "" : " " + t.description;
        Component rich = m.get("trade.broadcast",
                Placeholder.unparsed("player", t.ownerName),
                Placeholder.component("need", need),
                Placeholder.component("offers", offers),
                Placeholder.unparsed("description", desc));

        String needPlain = (t.want.amount() > 0 ? t.want.amount() + " x " : "") + ItemUtil.plainName(t.want.material());
        String offersPlain = open ? PLAIN.serialize(m.get("chat.offers-negotiable")) : describePlain(t.offers, t.money);
        String withName = PLAIN.serialize(m.get("chat.plain-trade",
                Placeholder.unparsed("player", t.ownerName),
                Placeholder.unparsed("need", needPlain),
                Placeholder.unparsed("offers", offersPlain),
                Placeholder.unparsed("description", desc)));
        String asPlayer = PLAIN.serialize(m.get("chat.plain-trade-self",
                Placeholder.unparsed("need", needPlain),
                Placeholder.unparsed("offers", offersPlain),
                Placeholder.unparsed("description", desc)));
        publish(p, rich, withName, own ? asPlayer : withName, "trade", (needPlain + " -> " + offersPlain + desc).trim());
    }

    /** Creation with the "-c" flag: the listing already exists, so a chat problem is only reported, not fatal. */
    private void tryPostToChat(Player p, Runnable post) {
        try {
            post.run();
        } catch (BoardException ex) {
            plugin.report(p, ex);
        }
    }

    private void publish(Player p, Component rich, String plainWithName, String plainAsPlayer, String category, String text) {
        Settings s = plugin.settings();
        if (!s.chatEnabled) throw new BoardException("chat.disabled");
        requirePermission(p, "agoraboard.chat");
        long left = chatCooldownLeft(p);
        if (left > 0) {
            throw new BoardException("chat.cooldown",
                    Placeholder.unparsed("time", TimeUtil.left(System.currentTimeMillis() + left, plugin.messages())));
        }
        double price = chatPrice(p);
        charge(p, price);

        boolean delivered = switch (s.chatMode) {
            case BROADCAST -> {
                Platform.text().broadcast(plugin.messages().prefixed(rich));
                yield true;
            }
            case PLAYER -> {
                p.chat(plainAsPlayer); // runs on the player's own thread
                yield true;
            }
            case COMMAND -> runChatCommands(p, plainWithName, category, text);
        };
        if (!delivered) {
            if (price > 0) plugin.economy().refund(p, price);
            throw new BoardException("chat.failed");
        }

        store.chatPosts.put(p.getUniqueId(), System.currentTimeMillis());
        store.saveSoon();
        if (price > 0) plugin.messages().send(p, "chat.posted-paid", Placeholder.unparsed("price", money(price)));
        else plugin.messages().send(p, "chat.posted");
    }

    /**
     * Console commands from config. Player-written text is stripped of % and & so it cannot inject placeholders or colors.
     * On Folia console commands must run on the global thread, so the result cannot be known and success is assumed.
     */
    private boolean runChatCommands(Player p, String message, String category, String text) {
        Map<String, String> values = Map.of(
                "player", p.getName(),
                "category", safeForCommand(category),
                "text", safeForCommand(text),
                "message", safeForCommand(message));
        List<String> commands = new ArrayList<>();
        for (String template : plugin.settings().chatCommands) {
            Matcher m = TOKEN.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(values.get(m.group(1))));
            m.appendTail(sb);
            commands.add(sb.toString());
        }
        if (Platform.FOLIA) {
            Sched.global(() -> commands.forEach(c -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c)));
            return true;
        }
        boolean any = false;
        for (String c : commands) any |= Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
        return any;
    }

    private static String safeForCommand(String s) {
        return s.replace("%", "").replace("&", "");
    }

    // ---------------------------------------------------------------- claim box

    /** Puts items into the owner's claim box (the "receive items" GUI). Never touches an inventory. */
    public synchronized void addToClaims(UUID owner, List<ItemStack> items) {
        store.claimList(owner).addAll(items);
    }

    /**
     * Returns items to their owner. Only the acting player's own inventory is touched directly (it is their thread);
     * for everyone else, and for whatever does not fit, the claim box is used.
     */
    private void returnItems(UUID owner, List<ItemStack> items, Player actor) {
        List<ItemStack> leftover = new ArrayList<>();
        if (actor != null && actor.getUniqueId().equals(owner)) {
            for (ItemStack s : items) leftover.addAll(actor.getInventory().addItem(s).values());
        } else {
            leftover.addAll(items);
        }
        if (leftover.isEmpty()) return;
        addToClaims(owner, leftover);
        if (actor != null && actor.getUniqueId().equals(owner)) plugin.messages().send(actor, "claim.pending");
    }

    /** Moves one waiting stack into the player's inventory. Whatever does not fit stays in the box. */
    public synchronized int claimOne(Player p, ItemStack ref) {
        List<ItemStack> waiting = store.claims.get(p.getUniqueId());
        int idx = -1;
        if (waiting != null) {
            for (int i = 0; i < waiting.size(); i++) {
                if (waiting.get(i) == ref) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) return 0; // already taken (stale GUI)
        int before = ref.getAmount();
        List<ItemStack> left = new ArrayList<>(p.getInventory().addItem(ref.clone()).values());
        if (left.isEmpty()) {
            waiting.remove(idx);
            if (waiting.isEmpty()) store.claims.remove(p.getUniqueId());
        } else {
            waiting.set(idx, left.get(0));
        }
        store.saveNow();
        int given = before - (left.isEmpty() ? 0 : left.get(0).getAmount());
        if (given == 0) throw new BoardException("claim.no-space");
        return given;
    }

    /** Takes everything that fits. Returns the number of items delivered. */
    public synchronized int claimAll(Player p) {
        List<ItemStack> waiting = store.claims.get(p.getUniqueId());
        if (waiting == null || waiting.isEmpty()) return 0;
        int given = 0;
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack s : new ArrayList<>(waiting)) {
            int before = s.getAmount();
            List<ItemStack> rest = new ArrayList<>(p.getInventory().addItem(s.clone()).values());
            if (rest.isEmpty()) {
                given += before;
            } else {
                given += before - rest.get(0).getAmount();
                left.add(rest.get(0));
            }
        }
        waiting.clear();
        if (left.isEmpty()) store.claims.remove(p.getUniqueId());
        else waiting.addAll(left);
        store.saveNow();
        return given;
    }

    // ---------------------------------------------------------------- expiry

    /** Runs on the global thread. Never touches a player's inventory: returned items go to the claim box. */
    public synchronized void expireTick() {
        long now = System.currentTimeMillis();
        boolean itemsMoved = false;
        boolean changed = false;

        for (Announcement a : store.ads) {
            if (!a.isExpired(now)) continue;
            store.ads.remove(a);
            changed = true;
            notifyPlayer(a.owner, "ad.expired", Placeholder.unparsed("id", String.valueOf(a.id)));
        }
        for (Trade t : store.trades) {
            if (!t.isExpired(now)) continue;
            if (t.held && t.money > 0) {
                // reserved money must go back first; if the economy is not there, try again next time
                try {
                    releaseMoney(t);
                } catch (BoardException ex) {
                    continue;
                }
            }
            store.trades.remove(t);
            changed = true;
            if (t.escrowed() && !t.escrow.isEmpty()) {
                addToClaims(t.owner, cloneAll(t.escrow));
                itemsMoved = true;
            }
            notifyPlayer(t.owner, t.escrowed() ? "trade.expired-returned" : "trade.expired",
                    Placeholder.unparsed("id", String.valueOf(t.id)));
            if (t.held && t.money > 0) itemsMoved = true;
        }
        if (itemsMoved) store.saveNow();
        else if (changed) store.saveSoon();
    }

    // ---------------------------------------------------------------- helpers

    /** Tells an online player something, on that player's own thread. Offline players are skipped. */
    private void notifyPlayer(UUID id, String key, TagResolver... resolvers) {
        Player online = Bukkit.getPlayer(id);
        if (online == null) return;
        Sched.forPlayer(online, () -> plugin.messages().send(online, key, resolvers));
    }

    /** "5 x Diamond, 64 x Iron Ingot" with client-translated item names. */
    public static Component describe(List<Want> items) {
        List<Component> parts = new ArrayList<>();
        for (Want w : items) {
            parts.add(Component.text(w.amount() + " x ").append(ItemUtil.name(w.material())));
        }
        if (parts.isEmpty()) return Component.empty();
        return Component.join(JoinConfiguration.separator(Component.text(", ")), parts);
    }

    /** Items and/or money: "5 x Diamond, $250". */
    public Component describeOffer(List<Want> items, double money) {
        List<Component> parts = new ArrayList<>();
        for (Want w : items) parts.add(Component.text(w.amount() + " x ").append(ItemUtil.name(w.material())));
        if (money > 0) parts.add(Component.text(money(money)));
        if (parts.isEmpty()) return Component.empty();
        return Component.join(JoinConfiguration.separator(Component.text(", ")), parts);
    }

    private String describePlain(List<Want> items, double money) {
        List<String> parts = new ArrayList<>();
        for (Want w : items) parts.add(w.amount() + " x " + ItemUtil.plainName(w.material()));
        if (money > 0) parts.add(money(money));
        return String.join(", ", parts);
    }

    private static List<ItemStack> cloneAll(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>(stacks.size());
        for (ItemStack s : stacks) out.add(s.clone());
        return out;
    }

    private static int countAny(Player p, Material m) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.getType() == m) n += s.getAmount();
        }
        return n;
    }

    private void requirePermission(Player p, String node) {
        if (!p.hasPermission(node)) throw new BoardException("no-permission");
    }

    private void checkCooldown(Player p) {
        int cd = plugin.settings().cooldownSeconds;
        if (cd <= 0 || p.hasPermission("agoraboard.unlimited")) return;
        Long last = lastCreated.get(p.getUniqueId());
        if (last == null) return;
        long wait = cd - (System.currentTimeMillis() - last) / 1000;
        if (wait > 0) throw new BoardException("cooldown", Placeholder.unparsed("seconds", String.valueOf(wait)));
    }

    private void checkBlocked(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : plugin.settings().blockedWords) {
            if (lower.contains(w)) throw new BoardException("ad.blocked");
        }
    }

    /** Strips control/format characters and collapses whitespace. The result is only ever shown as plain text. */
    public static String sanitize(String raw) {
        String s = raw.chars()
                .filter(c -> c >= 32 && c != 127 && c != '§')
                .mapToObj(c -> String.valueOf((char) c))
                .collect(Collectors.joining());
        return s.trim().replaceAll("\\s+", " ");
    }
}
