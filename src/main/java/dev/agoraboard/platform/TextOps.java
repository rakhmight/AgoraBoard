package dev.agoraboard.platform;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Everything that differs between Paper (native Adventure) and plain Spigot (legacy strings and BungeeCord chat).
 * The rest of the plugin only builds Adventure {@link Component}s and hands them to this interface.
 */
public interface TextOps {
    void send(CommandSender to, Component message);

    void broadcast(Component message);

    Inventory createInventory(InventoryHolder holder, int size, Component title);

    /** The holder of a custom inventory, without forcing block-state snapshots where the platform allows it. */
    InventoryHolder holderOf(Inventory inventory);

    void setName(ItemMeta meta, Component name);

    void setLore(ItemMeta meta, List<Component> lore);

    /** Existing lore of the item, never null. */
    List<Component> getLore(ItemMeta meta);

    /** Custom name if the stack has one, otherwise the client-translated vanilla name. */
    Component stackName(ItemStack stack);

    /** Translation key of an item type, e.g. "item.minecraft.diamond". */
    String translationKey(Material material);

    /** Self-contained text form of an item stack for data.yml. */
    String serializeItem(ItemStack stack);

    ItemStack deserializeItem(String data);

    String platformName();
}
