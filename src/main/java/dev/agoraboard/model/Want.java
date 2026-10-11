package dev.agoraboard.model;

import org.bukkit.Material;

/** One acceptable payment option: {@code amount} pieces of plain {@code material}. */
public record Want(Material material, int amount) {
}
