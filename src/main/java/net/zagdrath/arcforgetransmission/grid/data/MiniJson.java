/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// A small JSON reader for the attach group files: objects become maps, arrays lists, numbers doubles. Kept free of
// Minecraft (and Gson) so the attach groups load the same in the game and in the unit tests.
public final class MiniJson {
    private final String text;
    private int at;

    private MiniJson(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        MiniJson json = new MiniJson(text);
        Object value = json.value();
        json.space();
        if (json.at != text.length()) {
            throw json.error("trailing text");
        }
        return value;
    }

    private Object value() {
        space();
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (peek('}')) {
            at++;
            return map;
        }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            map.put(key, value());
            space();
            if (peek(',')) {
                at++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (peek(']')) {
            at++;
            return list;
        }
        while (true) {
            list.add(value());
            space();
            if (peek(',')) {
                at++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                char e = text.charAt(at++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> out.append(e);
                }
            } else {
                out.append(c);
            }
        }
        throw error("unterminated string");
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (start == at) {
            throw error("unexpected character");
        }
        return Double.parseDouble(text.substring(start, at));
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("expected " + word);
        }
        at += word.length();
        return value;
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private boolean peek(char c) {
        return at < text.length() && text.charAt(at) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        at++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + at);
    }
}
