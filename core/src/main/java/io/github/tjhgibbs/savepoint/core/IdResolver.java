package io.github.tjhgibbs.savepoint.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns what a person types into a savepoint: full id, short id (the 4 hex digits at the end, or any unique
 * prefix/suffix of the id), "latest", exact name, or a unique name prefix (case-insensitive).
 */
public final class IdResolver {
    private IdResolver() {}

    public sealed interface Result permits Found, NotFound, Ambiguous {}

    public record Found(Manifest manifest) implements Result {}

    public record NotFound(String token) implements Result {}

    public record Ambiguous(String token, List<Manifest> candidates) implements Result {}

    /** The short id shown to people: the last four characters of the id. */
    public static String shortId(Manifest m) {
        String id = m.id();
        return id.length() <= 4 ? id : id.substring(id.length() - 4);
    }

    /** @param all newest first */
    public static Result resolve(List<Manifest> all, String token) {
        if (token == null || token.isBlank()) return new NotFound(token == null ? "" : token);
        String t = token.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        for (Manifest m : all) if (m.id().equals(t)) return new Found(m);
        if (lower.equals("latest") || lower.equals("last")) {
            return all.isEmpty() ? new NotFound(t) : new Found(all.get(0));
        }
        List<Manifest> hits = new ArrayList<>();
        for (Manifest m : all) {
            String id = m.id().toLowerCase(Locale.ROOT);
            if (id.equals(lower) || id.endsWith("-" + lower) || id.startsWith(lower) && lower.length() >= 4) hits.add(m);
        }
        if (hits.size() == 1) return new Found(hits.get(0));
        if (hits.size() > 1) return new Ambiguous(t, hits);
        for (Manifest m : all) if (m.name().equalsIgnoreCase(t)) hits.add(m);
        if (hits.size() == 1) return new Found(hits.get(0));
        if (hits.size() > 1) return new Ambiguous(t, hits);
        for (Manifest m : all) if (m.name().toLowerCase(Locale.ROOT).startsWith(lower)) hits.add(m);
        if (hits.size() == 1) return new Found(hits.get(0));
        if (hits.size() > 1) return new Ambiguous(t, hits);
        return new NotFound(t);
    }
}
