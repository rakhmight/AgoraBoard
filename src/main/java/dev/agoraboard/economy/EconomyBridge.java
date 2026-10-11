package dev.agoraboard.economy;

import dev.agoraboard.AgoraBoardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Talks to the server economy through Vault's {@code net.milkbowl.vault.economy.Economy} service using reflection, so the
 * plugin has no compile-time or hard runtime dependency on Vault. Works with Vault and VaultUnlocked (EssentialsX, CMI...).
 * The provider is looked up lazily because economy plugins register their service after AgoraBoard has loaded.
 */
public final class EconomyBridge {
    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    private final AgoraBoardPlugin plugin;

    private Object provider;
    private Method has;
    private Method balance;
    private Method withdraw;
    private Method deposit;
    private Method format;
    private Method success;

    public EconomyBridge(AgoraBoardPlugin plugin) {
        this.plugin = plugin;
    }

    /** For tests: wrap an already known provider. */
    public EconomyBridge(Object provider, Class<?> economyType) {
        this.plugin = null;
        bind(provider, economyType);
    }

    /** True when an economy plugin is installed and has registered itself. */
    public boolean available() {
        if (provider != null) return true;
        if (plugin == null) return false;
        try {
            Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
            if (vault == null || !vault.isEnabled()) return false;
            Class<?> type = Class.forName(ECONOMY_CLASS, false, vault.getClass().getClassLoader());
            @SuppressWarnings({"unchecked", "rawtypes"})
            RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration((Class) type);
            if (reg == null) return false;
            bind(reg.getProvider(), type);
            plugin.getLogger().info("Economy hooked: " + reg.getProvider().getClass().getSimpleName()
                    + " (via " + reg.getPlugin().getName() + ")");
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Vault was found but its economy API could not be used: " + ex);
            return false;
        }
    }

    private void bind(Object providerObject, Class<?> type) {
        try {
            has = type.getMethod("has", OfflinePlayer.class, double.class);
            balance = type.getMethod("getBalance", OfflinePlayer.class);
            withdraw = type.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = type.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            format = type.getMethod("format", double.class);
            success = withdraw.getReturnType().getMethod("transactionSuccess");
            provider = providerObject;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Unsupported economy API: " + ex.getMessage(), ex);
        }
    }

    /** Takes the money. Returns false (and takes nothing) when the player cannot afford it or the economy refuses. */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!available()) return false;
        try {
            if (!(boolean) has.invoke(provider, player, amount)) return false;
            Object response = withdraw.invoke(provider, player, amount);
            return (boolean) success.invoke(response);
        } catch (ReflectiveOperationException ex) {
            warn("withdraw", ex);
            return false;
        }
    }

    /** Pays the player. Returns false when the economy is missing or refuses. */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (!available()) return false;
        try {
            Object response = deposit.invoke(provider, player, amount);
            return (boolean) success.invoke(response);
        } catch (ReflectiveOperationException ex) {
            warn("deposit", ex);
            return false;
        }
    }

    /** Gives money back (an action failed after the player was charged, or a reserved offer is released). */
    public boolean refund(OfflinePlayer player, double amount) {
        return deposit(player, amount);
    }

    public double balance(OfflinePlayer player) {
        if (!available()) return 0;
        try {
            return ((Number) balance.invoke(provider, player)).doubleValue();
        } catch (ReflectiveOperationException ex) {
            warn("balance", ex);
            return 0;
        }
    }

    /** Amount in the currency format of the economy plugin ("$12.50"); plain number when no economy is present. */
    public String format(double amount) {
        if (available()) {
            try {
                return String.valueOf(format.invoke(provider, amount));
            } catch (ReflectiveOperationException ex) {
                warn("format", ex);
            }
        }
        return amount == Math.rint(amount) ? String.valueOf((long) amount) : String.format(Locale.ROOT, "%.2f", amount);
    }

    private void warn(String what, Exception ex) {
        if (plugin != null) plugin.getLogger().warning("Economy call '" + what + "' failed: " + ex);
    }
}
