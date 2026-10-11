package dev.agoraboard.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * One scheduling API for Bukkit/Spigot/Paper and Folia. The plugin is compiled against an old API, so Folia's
 * schedulers are reached by reflection; on every other server the normal Bukkit scheduler is used.
 * <ul>
 *   <li>{@link #global} - the main/global thread (config-wide work: timers, console commands)</li>
 *   <li>{@link #forPlayer} - the thread that owns a player (opening inventories, touching their items)</li>
 *   <li>{@link #async} - off the game threads (file writes)</li>
 * </ul>
 */
public final class Sched {
    private static Plugin plugin;

    // Folia only
    private static Object globalScheduler;
    private static Object asyncScheduler;
    private static Method globalRun;
    private static Method globalRunDelayed;
    private static Method globalRunAtFixedRate;
    private static Method asyncRunNow;
    private static Method entitySchedulerGetter;
    private static Method entityRun;
    private static Method entityRunDelayed;

    private Sched() {
    }

    public static void init(Plugin owner) {
        plugin = owner;
        if (!Platform.FOLIA) return;
        try {
            Object server = Bukkit.getServer();
            globalScheduler = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
            asyncScheduler = server.getClass().getMethod("getAsyncScheduler").invoke(server);
            Class<?> global = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> async = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            Class<?> entity = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            globalRun = global.getMethod("run", Plugin.class, Consumer.class);
            globalRunDelayed = global.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
            globalRunAtFixedRate = global.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
            asyncRunNow = async.getMethod("runNow", Plugin.class, Consumer.class);
            entitySchedulerGetter = Class.forName("org.bukkit.entity.Entity").getMethod("getScheduler");
            entityRun = entity.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
            entityRunDelayed = entity.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Folia scheduler API not usable: " + ex, ex);
        }
    }

    /** Next tick on the global thread. */
    public static void global(Runnable task) {
        if (Platform.FOLIA) call(globalRun, globalScheduler, plugin, wrap(task));
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    public static void globalLater(Runnable task, long ticks) {
        if (Platform.FOLIA) call(globalRunDelayed, globalScheduler, plugin, wrap(task), Math.max(1L, ticks));
        else Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }

    public static void globalRepeat(Runnable task, long delayTicks, long periodTicks) {
        if (Platform.FOLIA) call(globalRunAtFixedRate, globalScheduler, plugin, wrap(task), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        else Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    public static void async(Runnable task) {
        if (Platform.FOLIA) call(asyncRunNow, asyncScheduler, plugin, wrap(task));
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /** Next tick on the thread that owns this player. Skipped silently if the player has left. */
    public static void forPlayer(Player player, Runnable task) {
        if (Platform.FOLIA) {
            Object scheduler = entityScheduler(player);
            call(entityRun, scheduler, plugin, wrap(task), (Runnable) null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void forPlayerLater(Player player, Runnable task, long ticks) {
        if (Platform.FOLIA) {
            Object scheduler = entityScheduler(player);
            call(entityRunDelayed, scheduler, plugin, wrap(task), (Runnable) null, Math.max(1L, ticks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
        }
    }

    private static Object entityScheduler(Player player) {
        try {
            return entitySchedulerGetter.invoke(player);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Consumer<Object> wrap(Runnable task) {
        return ignored -> task.run();
    }

    private static void call(Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
