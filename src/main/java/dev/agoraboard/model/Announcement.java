package dev.agoraboard.model;

import java.util.UUID;

public final class Announcement {
    public final int id;
    public final UUID owner;
    public final String ownerName;
    public final String categoryId;
    public final String text;
    public final long created;
    public final long expires;

    public Announcement(int id, UUID owner, String ownerName, String categoryId, String text, long created, long expires) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.categoryId = categoryId;
        this.text = text;
        this.created = created;
        this.expires = expires;
    }

    public boolean isExpired(long now) {
        return expires > 0 && now >= expires;
    }
}
