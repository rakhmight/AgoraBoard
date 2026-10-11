package dev.agoraboard.platform;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Detects once what kind of server we run on. */
public final class Platform {
    /** Folia (regionized multithreading): everything must be scheduled on the right region thread. */
    public static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    /** The server itself understands Adventure components (Paper and its forks). Plain Spigot does not. */
    public static final boolean NATIVE_ADVENTURE = hasNativeAdventure();

    private static volatile TextOps text;

    private Platform() {
    }

    public static TextOps text() {
        TextOps ops = text;
        if (ops == null) {
            ops = NATIVE_ADVENTURE ? new PaperText() : new SpigotText();
            text = ops;
        }
        return ops;
    }

    /** "Paper", "Folia" or "Spigot". */
    public static String name() {
        return FOLIA ? "Folia" : text().platformName();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static boolean hasNativeAdventure() {
        try {
            Player.class.getMethod("sendMessage", Component.class);
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }
}
