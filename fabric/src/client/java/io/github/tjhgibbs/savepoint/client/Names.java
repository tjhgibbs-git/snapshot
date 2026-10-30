package io.github.tjhgibbs.savepoint.client;

/** Keeps user-supplied names from blowing up fixed-width UI. */
final class Names {
    private Names() {}

    static String shorten(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "…";
    }
}
