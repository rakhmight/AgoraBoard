package dev.agoraboard;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** A user-facing failure: carries a messages.yml key that gets sent to the player. */
public final class BoardException extends RuntimeException {
    private final String key;
    private final transient TagResolver[] resolvers;

    public BoardException(String key, TagResolver... resolvers) {
        super(key, null, false, false);
        this.key = key;
        this.resolvers = resolvers;
    }

    public String key() {
        return key;
    }

    public TagResolver[] resolvers() {
        return resolvers;
    }
}
