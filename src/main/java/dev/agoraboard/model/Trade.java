package dev.agoraboard.model;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * A trade listing: the owner NEEDS {@code want} and gives {@code offers} (items) and/or {@code money} in return.
 * When {@code held} is true the board physically holds the offer: the item stacks in {@code escrow} and the reserved
 * {@code money}, and the exchange is automatic. Otherwise {@code offers}/{@code money} are only informational.
 */
public final class Trade {
    public final int id;
    public final UUID owner;
    public final String ownerName;
    public final Want want;
    public final List<Want> offers;
    public final List<ItemStack> escrow;
    /** Money offered on top of (or instead of) items; 0 when none. Reserved from the owner's balance when {@code held}. */
    public final double money;
    /** The board holds the offer and exchanges it automatically on acceptance. */
    public final boolean held;
    /** Terms are agreed in person: no automatic exchange, {@code want.amount()} may be 0 (unspecified) and offers may be empty. */
    public final boolean negotiable;
    /** Free text from the owner (enchantments, conditions...). Empty when absent. */
    public final String description;
    public final long created;
    public final long expires;

    public Trade(int id, UUID owner, String ownerName, Want want, List<Want> offers, List<ItemStack> escrow,
                 double money, boolean held, boolean negotiable, String description, long created, long expires) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.want = want;
        this.offers = List.copyOf(offers);
        this.escrow = List.copyOf(escrow);
        this.money = money;
        this.held = held;
        this.negotiable = negotiable;
        this.description = description == null ? "" : description;
        this.created = created;
        this.expires = expires;
    }

    /** True when the offer is held by the board and the exchange is automatic. */
    public boolean escrowed() {
        return held;
    }

    public boolean isExpired(long now) {
        return expires > 0 && now >= expires;
    }
}
