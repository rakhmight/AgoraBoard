package dev.agoraboard.util;

import dev.agoraboard.platform.Platform;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Inventory array helpers. All trade math runs on cloned arrays so it is all-or-nothing. */
public final class ItemUtil {
    private ItemUtil() {
    }

    /** A stack counts as "plain" when it matches a freshly created stack of the material (no name, enchants, damage...). */
    public static boolean isPlain(ItemStack stack, Material material) {
        return stack != null && stack.getType() == material && stack.isSimilar(new ItemStack(material));
    }

    public static int count(ItemStack[] contents, Material material) {
        int total = 0;
        for (ItemStack s : contents) {
            if (isPlain(s, material)) total += s.getAmount();
        }
        return total;
    }

    /** Removes {@code amount} plain items. Returns false (and changes nothing) if there are not enough. */
    public static boolean remove(ItemStack[] contents, Material material, int amount) {
        if (count(contents, material) < amount) return false;
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (!isPlain(s, material)) continue;
            int take = Math.min(left, s.getAmount());
            left -= take;
            if (take == s.getAmount()) contents[i] = null;
            else s.setAmount(s.getAmount() - take);
        }
        return true;
    }

    /**
     * Takes {@code amount} items of the material out of the array, any kind of stack (enchanted, named...),
     * plain stacks first so special items are only used when nothing else is left.
     * Returns the taken stacks, or null (and changes nothing) if there are not enough.
     */
    public static List<ItemStack> take(ItemStack[] contents, Material material, int amount) {
        int total = 0;
        for (ItemStack s : contents) if (s != null && s.getType() == material) total += s.getAmount();
        if (total < amount) return null;

        List<ItemStack> taken = new ArrayList<>();
        int left = amount;
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < contents.length && left > 0; i++) {
                ItemStack s = contents[i];
                if (s == null || s.getType() != material) continue;
                boolean plain = isPlain(s, material);
                if ((pass == 0) != plain) continue;
                int n = Math.min(left, s.getAmount());
                ItemStack part = s.clone();
                part.setAmount(n);
                taken.add(part);
                left -= n;
                if (n == s.getAmount()) contents[i] = null;
                else s.setAmount(s.getAmount() - n);
            }
        }
        return taken;
    }

    /** Stacks that are identical (same item and components) merged for display; {@code total} may exceed one stack. */
    public record Group(ItemStack sample, int total) {
    }

    public static List<Group> group(List<ItemStack> stacks) {
        List<ItemStack> samples = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        for (ItemStack s : stacks) {
            int idx = -1;
            for (int i = 0; i < samples.size(); i++) {
                if (samples.get(i).isSimilar(s)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                samples.add(s.clone());
                totals.add(s.getAmount());
            } else {
                totals.set(idx, totals.get(idx) + s.getAmount());
            }
        }
        List<Group> out = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) out.add(new Group(samples.get(i), totals.get(i)));
        return out;
    }

    /** Adds the stack into the array. Returns the amount that did not fit. */
    public static int add(ItemStack[] contents, ItemStack stack) {
        int left = stack.getAmount();
        int max = stack.getMaxStackSize();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType().isAir() || !s.isSimilar(stack)) continue;
            int room = max - s.getAmount();
            if (room <= 0) continue;
            int put = Math.min(room, left);
            s.setAmount(s.getAmount() + put);
            left -= put;
        }
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (s != null && !s.getType().isAir()) continue;
            int put = Math.min(max, left);
            ItemStack copy = stack.clone();
            copy.setAmount(put);
            contents[i] = copy;
            left -= put;
        }
        return left;
    }

    public static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    /** Splits a plain amount into legal stacks. */
    public static List<ItemStack> stacks(Material material, int amount) {
        List<ItemStack> out = new ArrayList<>();
        int max = new ItemStack(material).getMaxStackSize();
        int left = amount;
        while (left > 0) {
            int n = Math.min(max, left);
            out.add(new ItemStack(material, n));
            left -= n;
        }
        return out;
    }

    /** Display name of a stack (custom name if it has one, otherwise the translated vanilla name). */
    public static Component name(ItemStack stack) {
        return Compat.itemName(stack);
    }

    public static Component name(Material material) {
        return Component.translatable(Platform.text().translationKey(material));
    }

    /** English display name for server-side plain text ("iron_ingot" -> "Iron Ingot"). */
    public static String plainName(Material material) {
        StringBuilder sb = new StringBuilder();
        for (String part : material.getKey().getKey().split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** Greedy word wrap for lore. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            while (word.length() > width) {
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
