package io.github.barrrssss.ldpcstaircase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for {@code vectors/MANIFEST.json} (objects, arrays, strings,
 * numbers, booleans, null). The library has no runtime dependencies and the tests use
 * only JUnit, so the manifest is parsed here rather than by pulling in a JSON library.
 */
final class Json {

    private final String source;
    private int position;

    private Json(String source) {
        this.source = source;
    }

    static Object parse(String source) {
        Json json = new Json(source);
        json.skipWhitespace();
        Object value = json.readValue();
        json.skipWhitespace();
        if (json.position != source.length()) {
            throw new IllegalStateException("Trailing content in JSON at offset " + json.position);
        }

        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    static int asInt(Object value) {
        return (int) ((Number) value).longValue();
    }

    static int[] asIntArray(Object value) {
        List<Object> list = asList(value);
        int[] result = new int[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = asInt(list.get(i));
        }

        return result;
    }

    private Object readValue() {
        skipWhitespace();
        char ch = peek();
        switch (ch) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
            case 'f':
                return readBoolean();
            case 'n':
                return readNull();
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return map;
        }

        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            map.put(key, readValue());
            skipWhitespace();
            char ch = next();
            if (ch == '}') {
                break;
            }

            if (ch != ',') {
                throw new IllegalStateException("Expected ',' or '}' at offset " + (position - 1));
            }
        }

        return map;
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return list;
        }

        while (true) {
            list.add(readValue());
            skipWhitespace();
            char ch = next();
            if (ch == ']') {
                break;
            }

            if (ch != ',') {
                throw new IllegalStateException("Expected ',' or ']' at offset " + (position - 1));
            }
        }

        return list;
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char ch = next();
            if (ch == '"') {
                break;
            }

            if (ch != '\\') {
                sb.append(ch);
                continue;
            }

            char escape = next();
            switch (escape) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(source.substring(position, position + 4), 16));
                    position += 4;
                    break;
                default:
                    throw new IllegalStateException(
                            "Bad escape \\" + escape + " at offset " + (position - 1));
            }
        }

        return sb.toString();
    }

    private Object readNumber() {
        int start = position;
        boolean isDouble = false;
        while (position < source.length()) {
            char ch = source.charAt(position);
            if (ch == '-' || ch == '+' || (ch >= '0' && ch <= '9')) {
                position++;
            } else if (ch == '.' || ch == 'e' || ch == 'E') {
                isDouble = true;
                position++;
            } else {
                break;
            }
        }

        String text = source.substring(start, position);
        return isDouble ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
    }

    private Boolean readBoolean() {
        if (source.startsWith("true", position)) {
            position += 4;
            return Boolean.TRUE;
        }

        if (source.startsWith("false", position)) {
            position += 5;
            return Boolean.FALSE;
        }

        throw new IllegalStateException("Invalid literal at offset " + position);
    }

    private Object readNull() {
        if (source.startsWith("null", position)) {
            position += 4;
            return null;
        }

        throw new IllegalStateException("Invalid literal at offset " + position);
    }

    private void skipWhitespace() {
        while (position < source.length()) {
            char ch = source.charAt(position);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                position++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (position >= source.length()) {
            throw new IllegalStateException("Unexpected end of JSON");
        }

        return source.charAt(position);
    }

    private char next() {
        if (position >= source.length()) {
            throw new IllegalStateException("Unexpected end of JSON");
        }

        return source.charAt(position++);
    }

    private void expect(char expected) {
        char ch = next();
        if (ch != expected) {
            throw new IllegalStateException(
                    "Expected '" + expected + "' but got '" + ch + "' at offset " + (position - 1));
        }
    }
}
