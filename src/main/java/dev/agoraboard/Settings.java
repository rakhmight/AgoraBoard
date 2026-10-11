package dev.agoraboard;

import dev.agoraboard.model.Category;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Immutable snapshot of config.yml. */
public final class Settings {
    public final String language;
    public final boolean guiTrades;
    public final int cooldownSeconds;
    public final boolean notifyOnJoin;
    public final List<String> blockedWords;

    public final int adsMax;
    public final int adsDays;
    public final int adsMinLength;
    public final int adsMaxLength;
    /** Cost of posting an announcement; 0 = free. */
    public final double adsPrice;

    public final boolean economyEnabled;

    /** Command (without slash) that the "[write]" buttons suggest, e.g. "msg" or "tell". */
    public final String messageCommand;

    public enum ChatMode { BROADCAST, COMMAND, PLAYER }

    public final boolean chatEnabled;
    public final ChatMode chatMode;
    /** Console commands used in COMMAND mode. Placeholders: {player} {category} {text} {message}. */
    public final List<String> chatCommands;
    /** Cost of one chat post (first post and every repost); 0 = free with a shared cooldown. */
    public final double chatPrice;
    public final int chatCooldownMinutes;

    public final int tradesMax;
    /** Cost of posting a trade; 0 = free. */
    public final double tradesPrice;
    /** Players may offer money instead of / besides items (needs a Vault economy to be reserved). */
    public final boolean moneyOffers;
    public final double moneyMin;
    /** 0 = no upper limit. */
    public final double moneyMax;
    public final int tradesDays;
    public final int maxOffers;
    public final int maxDescription;
    public final int maxAmount;
    public final boolean confirmGui;

    public final Map<String, Category> categories;

    private Settings(FileConfiguration c, Logger log) {
        String lang = c.getString("language", "ru").trim().toLowerCase(Locale.ROOT);
        if (!lang.matches("[a-z0-9_-]{1,16}")) {
            log.warning("config.yml: invalid language '" + lang + "'. Using ru.");
            lang = "ru";
        }
        language = lang;
        String mode = c.getString("trade-mode", "GUI").trim().toUpperCase(Locale.ROOT);
        if (!mode.equals("GUI") && !mode.equals("NATURAL")) {
            log.warning("config.yml: trade-mode must be GUI or NATURAL, got '" + mode + "'. Using GUI.");
            mode = "GUI";
        }
        guiTrades = mode.equals("GUI");
        cooldownSeconds = Math.max(0, c.getInt("create-cooldown-seconds", 20));
        notifyOnJoin = c.getBoolean("notify-on-join", true);
        List<String> words = new ArrayList<>();
        for (String w : c.getStringList("blocked-words")) {
            if (!w.isBlank()) words.add(w.toLowerCase(Locale.ROOT));
        }
        blockedWords = List.copyOf(words);

        adsMax = Math.max(1, c.getInt("ads.max-per-player", 2));
        adsDays = Math.max(1, c.getInt("ads.duration-days", 10));
        adsMinLength = Math.max(1, c.getInt("ads.min-length", 3));
        adsMaxLength = Math.max(adsMinLength, c.getInt("ads.max-length", 150));
        adsPrice = Math.max(0, c.getDouble("ads.price", 0));

        economyEnabled = c.getBoolean("economy.enabled", true);
        String msgCmd = c.getString("message-command", "msg").trim();
        if (msgCmd.startsWith("/")) msgCmd = msgCmd.substring(1);
        if (!msgCmd.matches("[A-Za-z0-9_:\\-]{1,32}")) {
            log.warning("config.yml: invalid message-command '" + msgCmd + "'. Using msg.");
            msgCmd = "msg";
        }
        messageCommand = msgCmd;

        chatEnabled = c.getBoolean("chat.enabled", true);
        ChatMode chat;
        try {
            chat = ChatMode.valueOf(c.getString("chat.mode", "BROADCAST").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warning("config.yml: chat.mode must be BROADCAST, COMMAND or PLAYER. Using BROADCAST.");
            chat = ChatMode.BROADCAST;
        }
        List<String> cmds = new ArrayList<>();
        for (String line : c.getStringList("chat.commands")) {
            String cmd = line.trim();
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isEmpty()) cmds.add(cmd);
        }
        if (chat == ChatMode.COMMAND && cmds.isEmpty()) {
            log.warning("config.yml: chat.mode is COMMAND but chat.commands is empty. Using BROADCAST.");
            chat = ChatMode.BROADCAST;
        }
        chatMode = chat;
        chatCommands = List.copyOf(cmds);
        chatPrice = Math.max(0, c.getDouble("chat.price", 0));
        chatCooldownMinutes = Math.max(0, c.getInt("chat.cooldown-minutes", 30));

        tradesPrice = Math.max(0, c.getDouble("trades.price", 0));
        moneyOffers = c.getBoolean("trades.money.enabled", true);
        moneyMin = Math.max(0.01, c.getDouble("trades.money.min", 1));
        moneyMax = Math.max(0, c.getDouble("trades.money.max", 0));
        tradesMax = Math.max(1, c.getInt("trades.max-per-player", 5));
        tradesDays = Math.max(0, c.getInt("trades.duration-days", 10));
        maxOffers = Math.max(1, Math.min(9, c.getInt("trades.max-offers", 5)));
        maxDescription = Math.max(0, c.getInt("trades.max-description", 100));
        maxAmount = Math.max(1, c.getInt("trades.max-amount", 2304));
        confirmGui = c.getBoolean("trades.confirm-gui", true);

        Map<String, Category> cats = new LinkedHashMap<>();
        ConfigurationSection sec = c.getConfigurationSection("categories");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) continue;
                String key = id.toLowerCase(Locale.ROOT);
                Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
                if (icon == null || icon.isAir() || !icon.isItem()) {
                    log.warning("config.yml: category '" + id + "' has an invalid icon, using PAPER.");
                    icon = Material.PAPER;
                }
                cats.put(key, new Category(key, s.getString("name", id), icon));
            }
        }
        if (cats.isEmpty()) {
            log.warning("config.yml: no categories defined, adding 'other'.");
            cats.put("other", new Category("other", "<gray>Other", Material.PAPER));
        }
        categories = java.util.Collections.unmodifiableMap(cats);
    }

    public static Settings load(FileConfiguration config, Logger log) {
        return new Settings(config, log);
    }

    /** Case-insensitive lookup; null when unknown. */
    public Category category(String id) {
        return id == null ? null : categories.get(id.toLowerCase(Locale.ROOT));
    }
}
