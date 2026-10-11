package dev.agoraboard.util;

import dev.agoraboard.Messages;

public final class TimeUtil {
    private TimeUtil() {
    }

    /** "9д 3ч", "5ч 12м", "7м". */
    public static String left(long expiresAt, Messages m) {
        long s = Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000);
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long min = (s % 3600) / 60;
        if (d > 0) return d + m.raw("time.d") + " " + h + m.raw("time.h");
        if (h > 0) return h + m.raw("time.h") + " " + min + m.raw("time.m");
        return Math.max(1, min) + m.raw("time.m");
    }
}
