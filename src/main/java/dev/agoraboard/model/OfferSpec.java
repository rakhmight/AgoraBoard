package dev.agoraboard.model;

import org.bukkit.Material;

/** One thing the trade owner gives in return, as typed in the command: {@code amount} pieces of {@code material}. */
public record OfferSpec(Material material, int amount) {
}
