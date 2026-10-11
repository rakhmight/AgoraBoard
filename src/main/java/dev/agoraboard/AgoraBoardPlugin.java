package dev.agoraboard;

import dev.agoraboard.economy.EconomyBridge;
import dev.agoraboard.gui.MenuListener;
import dev.agoraboard.platform.Platform;
import dev.agoraboard.platform.Sched;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class AgoraBoardPlugin extends JavaPlugin {
    /**
     * bStats service id. Register the plugin at https://bstats.org (Add plugin) and put the number here; 0 keeps metrics off.
     * Server owners can always opt out in plugins/bStats/config.yml.
     */
    private static final int BSTATS_PLUGIN_ID = 34636;

    private volatile Settings settings;
    private Messages messages;
    private DataStore store;
    private BoardService service;
    private EconomyBridge economy;

    @Override
    public void onEnable() {
        migrateOldDataFolder();
        Sched.init(this);
        saveDefaultConfig();
        messages = new Messages(this);
        economy = new EconomyBridge(this);
        reloadAll();

        store = new DataStore(this);
        store.load();
        service = new BoardService(this, store);

        BoardCommand command = new BoardCommand(this);
        PluginCommand board = getCommand("board");
        if (board == null) {
            getLogger().severe("Command 'board' is missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        board.setExecutor(command);
        board.setTabCompleter(command);

        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        Sched.globalRepeat(service::expireTick, 20L * 30, 20L * 60);

        // economy plugins register their service after us, so look once everything has loaded
        Sched.global(this::checkEconomy);
        startMetrics();

        getLogger().info("Running on " + Platform.name() + ". Loaded " + store.ads.size() + " announcements and "
                + store.trades.size() + " trades. Trade mode: " + (settings.guiTrades ? "GUI" : "NATURAL"));
    }

    @Override
    public void onDisable() {
        if (store != null) store.saveNow();
    }

    /** The plugin used to be called BulletinBoard: keep its listings, claims and settings. */
    private void migrateOldDataFolder() {
        File old = new File(getDataFolder().getParentFile(), "BulletinBoard");
        if (!getDataFolder().exists() && old.isDirectory() && old.renameTo(getDataFolder())) {
            getLogger().info("Moved the data of the former BulletinBoard plugin into " + getDataFolder().getName() + ".");
        }
    }

    private void startMetrics() {
        if (BSTATS_PLUGIN_ID <= 0) return;
        try {
            Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            metrics.addCustomChart(new SimplePie("trade_mode", () -> settings.guiTrades ? "GUI" : "NATURAL"));
            metrics.addCustomChart(new SimplePie("language", () -> settings.language));
            metrics.addCustomChart(new SimplePie("chat_mode", () -> settings.chatEnabled ? settings.chatMode.name() : "DISABLED"));
            metrics.addCustomChart(new SimplePie("economy", () -> economy.available() ? "Vault" : "none"));
            metrics.addCustomChart(new SimplePie("prices", () ->
                    settings.adsPrice > 0 || settings.tradesPrice > 0 || settings.chatPrice > 0 ? "paid" : "free"));
        } catch (RuntimeException | LinkageError ex) {
            getLogger().warning("bStats could not be started: " + ex);
        }
    }

    /** Re-reads config.yml and the language file. Listings are kept. */
    public void reloadAll() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        messages.reload();
        if (service != null) checkEconomy();
    }

    /** Prices only apply when an economy is present; say so in the console when the config asks for them but none is. */
    private void checkEconomy() {
        boolean priced = settings.adsPrice > 0 || settings.tradesPrice > 0 || settings.chatPrice > 0;
        if (!priced) return;
        if (!settings.economyEnabled) {
            getLogger().warning("Prices are set but economy.enabled is false: everything is free.");
        } else if (!economy.available()) {
            getLogger().warning("Prices are set but no Vault economy was found (install Vault and an economy plugin, "
                    + "e.g. EssentialsX). Until then everything is free and prices are hidden.");
        }
    }

    /** Sends the player-facing text of a failed action. */
    public void report(CommandSender to, BoardException ex) {
        messages.send(to, ex.key(), ex.resolvers());
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public BoardService service() {
        return service;
    }

    public EconomyBridge economy() {
        return economy;
    }
}
