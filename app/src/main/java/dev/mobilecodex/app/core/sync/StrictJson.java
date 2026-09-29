package dev.mobilecodex.app.core.sync;

import org.json.JSONObject;
import java.util.HashSet;

/** Bounded RFC 8259 syntax check, including duplicate keys, before Android's lenient parser. */
public final class StrictJson {
    private final String text;
    private int at;
    private StrictJson(String text) { this.text = text; }
    public static JSONObject object(String text) {
        try {
            StrictJson parser = new StrictJson(text);
            parser.value(0); parser.space();
            if (parser.at != text.length()) throw new IllegalArgumentException("Trailing JSON data");
            return new JSONObject(text);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid project JSON", e); }
    }
    private void space() { while (at < text.length() && " \r\n\t".indexOf(text.charAt(at)) >= 0) at++; }
    private char peek() { space(); if (at >= text.length()) throw new IllegalArgumentException("Incomplete JSON"); return text.charAt(at); }
    private void take(char c) { if (peek() != c) throw new IllegalArgumentException("Invalid JSON token"); at++; }
    private void value(int depth) {
        if (depth > 12) throw new IllegalArgumentException("JSON too deep");
        char c = peek();
        if (c == '{') {
            at++; HashSet<String> keys = new HashSet<>();
            if (peek() == '}') { at++; return; }
            while (true) {
                if (!keys.add(string())) throw new IllegalArgumentException("Duplicate JSON key");
                take(':'); value(depth + 1);
                if (peek() == '}') { at++; return; } take(',');
            }
        } else if (c == '[') {
            at++; if (peek() == ']') { at++; return; }
            while (true) { value(depth + 1); if (peek() == ']') { at++; return; } take(','); }
        } else if (c == '"') string();
        else if (c == 't' || c == 'f' || c == 'n') {
            String literal = c == 't' ? "true" : c == 'f' ? "false" : "null";
            if (!text.startsWith(literal, at)) throw new IllegalArgumentException("Invalid JSON literal"); at += literal.length();
        } else {
            int start = at;
            if (text.charAt(at) == '-') at++;
            if (at < text.length() && text.charAt(at) == '0') at++;
            else { int first = at; while (digit()) at++; if (first == at) throw new IllegalArgumentException("Invalid JSON number"); }
            if (at < text.length() && text.charAt(at) == '.') { at++; int first = at; while (digit()) at++; if (first == at) throw new IllegalArgumentException("Invalid JSON fraction"); }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++; if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
                int first = at; while (digit()) at++; if (first == at) throw new IllegalArgumentException("Invalid JSON exponent");
            }
            if (at == start) throw new IllegalArgumentException("Invalid JSON value");
        }
    }
    private boolean digit() { return at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9'; }
    private String string() {
        take('"'); StringBuilder out = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 32) throw new IllegalArgumentException("Invalid JSON string");
            if (c == '\\') {
                if (at >= text.length()) throw new IllegalArgumentException("Incomplete escape");
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length() || !text.substring(at, at + 4).matches("[0-9a-fA-F]{4}")) throw new IllegalArgumentException("Invalid unicode escape");
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); at += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape");
                }
            } else out.append(c);
        }
        throw new IllegalArgumentException("Unclosed string");
    }
}
