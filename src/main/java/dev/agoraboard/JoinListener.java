package dev.agoraboard;

import dev.agoraboard.platform.Sched;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Tells returning players about items waiting in their claim box and mentions the board. */
public final class JoinListener implements Listener {
    private final AgoraBoardPlugin plugin;

    public JoinListener(AgoraBoardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // on the player's own thread (Folia), a little after the join so the message is not lost in the login spam
        Sched.forPlayerLater(p, () -> {
            if (!p.isOnline()) return;
            BoardService service = plugin.service();
            int waiting = service.pendingItems(p.getUniqueId());
            if (waiting > 0) {
                plugin.messages().send(p, "claim.join", Placeholder.unparsed("amount", String.valueOf(waiting)));
            }
            if (plugin.settings().notifyOnJoin && p.hasPermission("agoraboard.use")
                    && (!service.ads().isEmpty() || !service.trades().isEmpty())) {
                plugin.messages().send(p, "join.notice",
                        Placeholder.unparsed("ads", String.valueOf(service.ads().size())),
                        Placeholder.unparsed("trades", String.valueOf(service.trades().size())));
            }
        }, 60L);
    }
}
