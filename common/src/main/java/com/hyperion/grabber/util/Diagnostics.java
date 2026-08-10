package com.hyperion.grabber.common.util;

public final class Diagnostics {
    private static final int MAX_LINES = 300;
    private static final java.util.ArrayList<String> LINES = new java.util.ArrayList<>();
    private static final Object LOCK = new Object();

    public static void log(String tag, String message) {
        synchronized (LOCK) {
            LINES.add(tag + ": " + message);
            while (LINES.size() > MAX_LINES) {
                LINES.remove(0);
            }
        }
    }

    public static String dump() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder(LINES.size() * 64);
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }
}
