package io.github.pgallo.sipcc.platform.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One block of a line-oriented PBX manager protocol: {@code Key: Value} lines terminated by an
 * empty line. Actions carry {@code Action}, responses {@code Response}, events {@code Event};
 * {@code ActionID} correlates a response with its action. Keys may repeat (e.g. {@code Variable}).
 */
public final class ManagerMessage {

    private final List<Map.Entry<String, String>> fields = new ArrayList<>();

    public static ManagerMessage action(String name) {
        return new ManagerMessage().put("Action", name);
    }

    public static ManagerMessage event(String name) {
        return new ManagerMessage().put("Event", name);
    }

    public static ManagerMessage response(boolean success, String actionId, String message) {
        ManagerMessage m = new ManagerMessage().put("Response", success ? "Success" : "Error");
        if (actionId != null) {
            m.put("ActionID", actionId);
        }
        return m.put("Message", message);
    }

    public ManagerMessage put(String key, String value) {
        if (key.indexOf(':') >= 0 || key.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("field cannot be encoded on one line: " + key);
        }
        fields.add(Map.entry(key, value));
        return this;
    }

    public String get(String key) {
        for (var e : fields) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    public List<String> getAll(String key) {
        return fields.stream().filter(e -> e.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue).toList();
    }

    public boolean isEvent() {
        return get("Event") != null;
    }

    public boolean isSuccess() {
        return "Success".equalsIgnoreCase(get("Response"));
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (var e : fields) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        return sb.append("\r\n").toString();
    }

    /** Reads one block; returns null at end of stream. Blank lines between blocks are skipped. */
    public static ManagerMessage read(BufferedReader in) throws IOException {
        ManagerMessage m = new ManagerMessage();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
                if (m.fields.isEmpty()) {
                    continue;
                }
                return m;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("malformed manager line: " + line);
            }
            String value = line.substring(colon + 1);
            m.fields.add(Map.entry(line.substring(0, colon).trim(), value.startsWith(" ") ? value.substring(1) : value));
        }
        return m.fields.isEmpty() ? null : m;
    }

    @Override
    public String toString() {
        return encode().replace("\r\n", " | ");
    }
}
