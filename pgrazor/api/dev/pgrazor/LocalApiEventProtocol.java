package dev.pgrazor;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded JSON command grammar. IDs and topic names are literal ASCII identifiers. */
final class LocalApiEventProtocol {
    private static final String TOKEN = "\"[A-Za-z0-9_.-]{1,48}\"";
    private static final Pattern FIELD = Pattern.compile(
        "\\s*\"(version|id|op|topics)\"\\s*:\\s*(1|" + TOKEN
        + "|\\[\\s*(?:" + TOKEN + "(?:\\s*,\\s*" + TOKEN + "){0,2})?\\s*\\])\\s*([,}])");
    private static final Pattern IDENTIFIER = Pattern.compile(TOKEN);

    static final class Command {
        final String id;
        final String operation;
        final Set<String> topics;

        Command(String id, String operation, Set<String> topics) {
            this.id = id;
            this.operation = operation;
            this.topics = topics;
        }
    }

    static Command parse(String message) {
        if (message.length() > LocalApiWebSocket.MAX_MESSAGE_BYTES) return null;
        String json = message.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$", "");
        if (!json.startsWith("{") || json.indexOf('\u000b') >= 0 || json.indexOf('\f') >= 0) return null;
        Matcher field = FIELD.matcher(json);
        Map<String, String> fields = new HashMap<>();
        int position = 1;
        for (int count = 0; count < 4; count++) {
            if (!field.region(position, json.length()).lookingAt()
                    || fields.put(field.group(1), field.group(2)) != null) return null;
            position = field.end();
            if (field.group(3).equals("}")) {
                return position == json.length() ? command(fields) : null;
            }
        }
        return null;
    }

    private static Command command(Map<String, String> fields) {
        String id = fields.getOrDefault("id", "");
        String op = fields.getOrDefault("op", "");
        String list = fields.get("topics");
        if (!"1".equals(fields.get("version")) || !IDENTIFIER.matcher(id).matches()
                || !IDENTIFIER.matcher(op).matches() || (list != null && !list.startsWith("["))) return null;
        Set<String> topics = new LinkedHashSet<>();
        if (list != null) {
            Matcher identifiers = IDENTIFIER.matcher(list);
            while (identifiers.find()) {
                if (!topics.add(unquote(identifiers.group()))) return null;
            }
        }
        String operation = unquote(op);
        if (operation.equals("discover") && list != null) return null;
        if ((operation.equals("subscribe") || operation.equals("unsubscribe")) && topics.isEmpty()) return null;
        return new Command(unquote(id), operation, topics);
    }

    private static String unquote(String value) {
        return value.substring(1, value.length() - 1);
    }

    static String nullable(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    static String array(Iterable<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (String value : values) {
            if (json.length() > 1) json.append(',');
            json.append(nullable(value));
        }
        return json.append(']').toString();
    }
}
