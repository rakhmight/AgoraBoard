package dev.agoraboard;

import dev.agoraboard.model.Announcement;
import dev.agoraboard.model.Trade;
import dev.agoraboard.model.Want;
import dev.agoraboard.platform.Platform;
import dev.agoraboard.platform.Sched;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything is kept in memory and persisted to data.yml (atomic replace). The collections are thread-safe because on
 * Folia different regions call in concurrently; compound changes are serialized by {@link BoardService}.
 */
public final class DataStore {
    private final AgoraBoardPlugin plugin;
    private final File file;

    public final List<Announcement> ads = new CopyOnWriteArrayList<>();
    public final List<Trade> trades = new CopyOnWriteArrayList<>();
    /** Items waiting for offline players (or players whose inventory was full). */
    public final Map<UUID, List<ItemStack>> claims = new ConcurrentHashMap<>();

    /** When each player last posted to the chat (millis). Drives the shared free-post cooldown. */
    public final Map<UUID, Long> chatPosts = new ConcurrentHashMap<>();

    private int nextAdId = 1;
    private int nextTradeId = 1;

    private final Object writeLock = new Object();
    private long sequence;
    private long written;
    private final AtomicBoolean savePending = new AtomicBoolean();

    public DataStore(AgoraBoardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public synchronized int nextAdId() {
        return nextAdId++;
    }

    public synchronized int nextTradeId() {
        return nextTradeId++;
    }

    /** A claim list that is safe to read from any thread while another one changes it. */
    public List<ItemStack> claimList(UUID player) {
        return claims.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>());
    }

    // ---------------------------------------------------------------- load

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextAdId = Math.max(1, y.getInt("next-ad-id", 1));
        nextTradeId = Math.max(1, y.getInt("next-trade-id", 1));

        ConfigurationSection as = y.getConfigurationSection("ads");
        if (as != null) {
            for (String key : as.getKeys(false)) {
                try {
                    ConfigurationSection s = as.getConfigurationSection(key);
                    int id = Integer.parseInt(key);
                    ads.add(new Announcement(id, UUID.fromString(s.getString("owner", "")),
                            s.getString("owner-name", "?"), s.getString("category", ""),
                            s.getString("text", ""), s.getLong("created"), s.getLong("expires")));
                    nextAdId = Math.max(nextAdId, id + 1);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Skipping broken announcement '" + key + "': " + ex.getMessage());
                }
            }
        }

        ConfigurationSection ts = y.getConfigurationSection("trades");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                try {
                    ConfigurationSection s = ts.getConfigurationSection(key);
                    int id = Integer.parseInt(key);
                    UUID owner = UUID.fromString(s.getString("owner", ""));
                    nextTradeId = Math.max(nextTradeId, id + 1);
                    if (!s.contains("want")) {
                        // pre-1.0 format: drop the listing but never lose a held item
                        String legacy = s.getString("offered");
                        if (legacy != null && s.getBoolean("escrowed")) {
                            claimList(owner).add(decode(legacy));
                        }
                        plugin.getLogger().warning("Dropped old-format trade '" + key + "' (held item moved to claims).");
                        continue;
                    }
                    Want want = parseWant(s.getString("want", ""));
                    List<Want> offers = new ArrayList<>();
                    for (String w : s.getStringList("offers")) offers.add(parseWant(w));
                    List<ItemStack> escrow = new ArrayList<>();
                    for (String enc : s.getStringList("escrow")) escrow.add(decode(enc));
                    double money = s.getDouble("money", 0);
                    boolean held = s.contains("held") ? s.getBoolean("held") : !escrow.isEmpty();
                    boolean negotiable = s.getBoolean("negotiable");
                    if (offers.isEmpty() && money <= 0 && !negotiable) throw new IllegalStateException("no offers");
                    trades.add(new Trade(id, owner, s.getString("owner-name", "?"), want, offers, escrow, money, held,
                            negotiable, s.getString("description", ""), s.getLong("created"), s.getLong("expires")));
                } catch (Exception ex) {
                    plugin.getLogger().warning("Skipping broken trade '" + key + "': " + ex.getMessage());
                }
            }
        }

        ConfigurationSection posts = y.getConfigurationSection("chat-posts");
        if (posts != null) {
            for (String key : posts.getKeys(false)) {
                try {
                    chatPosts.put(UUID.fromString(key), posts.getLong(key));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Skipping broken chat-post entry '" + key + "'");
                }
            }
        }

        ConfigurationSection cs = y.getConfigurationSection("claims");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                try {
                    List<ItemStack> items = new ArrayList<>();
                    for (String enc : cs.getStringList(key)) items.add(decode(enc));
                    if (!items.isEmpty()) claimList(UUID.fromString(key)).addAll(items);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Skipping broken claim list '" + key + "': " + ex.getMessage());
                }
            }
        }
    }

    // ---------------------------------------------------------------- save

    private synchronized String snapshot() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-ad-id", nextAdId);
        y.set("next-trade-id", nextTradeId);
        for (Announcement a : ads) {
            String p = "ads." + a.id + ".";
            y.set(p + "owner", a.owner.toString());
            y.set(p + "owner-name", a.ownerName);
            y.set(p + "category", a.categoryId);
            y.set(p + "text", a.text);
            y.set(p + "created", a.created);
            y.set(p + "expires", a.expires);
        }
        for (Trade t : trades) {
            String p = "trades." + t.id + ".";
            y.set(p + "owner", t.owner.toString());
            y.set(p + "owner-name", t.ownerName);
            y.set(p + "want", formatWant(t.want));
            List<String> offers = new ArrayList<>();
            for (Want w : t.offers) offers.add(formatWant(w));
            y.set(p + "offers", offers);
            if (!t.escrow.isEmpty()) {
                List<String> enc = new ArrayList<>();
                for (ItemStack s : t.escrow) enc.add(encode(s));
                y.set(p + "escrow", enc);
            }
            if (t.money > 0) y.set(p + "money", t.money);
            y.set(p + "held", t.held);
            if (t.negotiable) y.set(p + "negotiable", true);
            if (!t.description.isEmpty()) y.set(p + "description", t.description);
            y.set(p + "created", t.created);
            y.set(p + "expires", t.expires);
        }
        for (Map.Entry<UUID, Long> e : chatPosts.entrySet()) y.set("chat-posts." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, List<ItemStack>> e : claims.entrySet()) {
            List<String> enc = new ArrayList<>();
            for (ItemStack s : e.getValue()) enc.add(encode(s));
            if (!enc.isEmpty()) y.set("claims." + e.getKey(), enc);
        }
        return y.saveToString();
    }

    /** Writes immediately on the calling thread. Used for anything that moves items or money. */
    public void saveNow() {
        String data = snapshot();
        long seq;
        synchronized (writeLock) {
            seq = ++sequence;
        }
        write(data, seq);
    }

    /** Debounced asynchronous save for harmless changes (announcements). */
    public void saveSoon() {
        if (!savePending.compareAndSet(false, true)) return;
        Sched.globalLater(() -> {
            savePending.set(false);
            String data = snapshot();
            long seq;
            synchronized (writeLock) {
                seq = ++sequence;
            }
            Sched.async(() -> write(data, seq));
        }, 40L);
    }

    private void write(String data, long seq) {
        synchronized (writeLock) {
            if (seq < written) return;
            try {
                File dir = file.getParentFile();
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                File tmp = new File(dir, "data.yml.tmp");
                Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                written = seq;
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not save data.yml: " + ex.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- items

    private static String formatWant(Want w) {
        return w.material().getKey().getKey().toLowerCase(Locale.ROOT) + ":" + w.amount();
    }

    private static Want parseWant(String text) {
        int sep = text.lastIndexOf(':');
        if (sep < 0) throw new IllegalStateException("bad item entry '" + text + "'");
        Material m = Material.matchMaterial(text.substring(0, sep));
        if (m == null) throw new IllegalStateException("unknown item '" + text + "'");
        return new Want(m, Integer.parseInt(text.substring(sep + 1)));
    }

    private static String encode(ItemStack stack) {
        return Platform.text().serializeItem(stack);
    }

    private static ItemStack decode(String enc) {
        return Platform.text().deserializeItem(enc);
    }
}
