package dev.agoraboard.util;

import dev.agoraboard.platform.Platform;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;

/**
 * The plugin is compiled against the oldest supported API (1.18.2) and runs unchanged on newer servers. Anything that only
 * exists on newer servers is looked up once here and falls back to an older way of doing the same thing.
 */
public final class Compat {
    /** 1.20.5+ */
    private static final Method GLINT_OVERRIDE = find(ItemMeta.class, "setEnchantmentGlintOverride", Boolean.class);
    /** 1.20.5+ */
    private static final Method HIDE_TOOLTIP = find(ItemMeta.class, "setHideTooltip", boolean.class);
    private Compat() {
    }

    private static Method find(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    /** Makes the item shimmer like an enchanted one without showing any enchantment. */
    public static void glow(ItemMeta meta) {
        if (GLINT_OVERRIDE != null) {
            try {
                GLINT_OVERRIDE.invoke(meta, Boolean.TRUE);
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through to the enchantment trick
            }
        }
        Enchantment unbreaking = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
        if (unbreaking != null) {
            meta.addEnchant(unbreaking, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
    }

    /** Hides the whole tooltip (decorative filler). Old servers just get a blank name. */
    public static void hideTooltip(ItemMeta meta) {
        Platform.text().setName(meta, Component.text(" "));
        if (HIDE_TOOLTIP != null) {
            try {
                HIDE_TOOLTIP.invoke(meta, Boolean.TRUE);
            } catch (ReflectiveOperationException ignored) {
                // the blank name is enough
            }
        }
    }

    /** Custom name if the stack has one, otherwise the client-translated vanilla name. */
    public static Component itemName(ItemStack stack) {
        return Platform.text().stackName(stack);
    }
}
