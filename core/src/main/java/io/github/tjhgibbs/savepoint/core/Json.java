package io.github.tjhgibbs.savepoint.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader/writer. Values: Map, List, String, Long, Double, Boolean, null. */
public final class Json {
    private final String s;
    private int p;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.p != j.s.length()) throw j.err("trailing data");
        return v;
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("JSON: " + m + " at " + p);
    }

    private void ws() {
        while (p < s.length() && s.charAt(p) <= ' ') p++;
    }

    private Object value() {
        if (p >= s.length()) throw err("unexpected end");
        char c = s.charAt(p);
        switch (c) {
            case '{': {
                p++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(p) == '}') {
                    p++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value());
                    ws();
                    if (s.charAt(p) == ',') {
                        p++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            case '[': {
                p++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(p) == ']') {
                    p++;
                    return l;
                }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    if (s.charAt(p) == ',') {
                        p++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            case '"':
                return string();
            case 't':
                lit("true");
                return Boolean.TRUE;
            case 'f':
                lit("false");
                return Boolean.FALSE;
            case 'n':
                lit("null");
                return null;
            default:
                return number();
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, p)) throw err("bad literal");
        p += w.length();
    }

    private void expect(char c) {
        if (p >= s.length() || s.charAt(p) != c) throw err("expected '" + c + "'");
        p++;
    }

    private Object number() {
        int st = p;
        boolean fp = false;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '.' || c == 'e' || c == 'E') fp = true;
            else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) break;
            p++;
        }
        if (st == p) throw err("unexpected character");
        String t = s.substring(st, p);
        return fp ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (p >= s.length()) throw err("unterminated string");
            char c = s.charAt(p++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(p++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                    p += 4;
                }
                default -> b.append(e);
            }
        }
    }

    /** Appends a JSON string literal. */
    public static void quote(StringBuilder b, String v) {
        b.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < ' ') b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("JSON: expected object");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        if (o instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("JSON: expected array");
    }

    public static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("JSON: expected number");
    }

    public static double dbl(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("JSON: expected number");
    }
}
