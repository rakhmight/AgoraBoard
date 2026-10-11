package dev.agoraboard.model;

import org.bukkit.Material;

/** Announcement category. {@code name} is a MiniMessage string. */
public record Category(String id, String name, Material icon) {
}
