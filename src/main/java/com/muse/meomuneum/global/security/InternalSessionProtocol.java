package com.muse.meomuneum.global.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Strict internal wire contract. Never include input or credentials in parse failures. */
public final class InternalSessionProtocol {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private InternalSessionProtocol() {
    }

    public static List<SessionPair> request(byte[] bytes) {
        JsonNode root = tree(bytes);
        fields(root, "sessions");
        JsonNode sessions = root.get("sessions");
        if (!sessions.isArray() || sessions.isEmpty() || sessions.size() > 100) {
            throw invalid();
        }
        var pairs = new ArrayList<SessionPair>();
        var sids = new HashSet<String>();
        for (JsonNode item : sessions) {
            fields(item, "user_id", "sid");
            SessionPair pair = pair(item);
            if (!sids.add(pair.sid())) {
                throw invalid();
            }
            pairs.add(pair);
        }
        return List.copyOf(pairs);
    }

    public static List<SessionDecision> response(byte[] bytes, List<SessionPair> expected) {
        JsonNode root = tree(bytes);
        fields(root, "message", "data");
        JsonNode message = root.get("message");
        if (!message.isTextual() || message.asText().isBlank() || message.asText().length() > 200) {
            throw invalid();
        }
        fields(root.get("data"), "sessions");
        JsonNode sessions = root.get("data").get("sessions");
        if (!sessions.isArray() || sessions.size() != expected.size()) {
            throw invalid();
        }
        List<SessionDecision> decisions = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            JsonNode item = sessions.get(i);
            SessionPair pair = pair(item);
            if (!pair.equals(expected.get(i))) {
                throw invalid();
            }
            String status = item.path("status").asText();
            if ("ACTIVE".equals(status)) {
                fields(item, "user_id", "sid", "status", "session_generation", "remaining_session_ms");
                decisions.add(new SessionDecision(pair, true, number(item, "session_generation", 0),
                        number(item, "remaining_session_ms", 1)));
            } else if ("INACTIVE".equals(status)) {
                fields(item, "user_id", "sid", "status");
                decisions.add(SessionDecision.inactive(pair));
            } else {
                throw invalid();
            }
        }
        return List.copyOf(decisions);
    }

    public static byte[] encodeRequest(List<SessionPair> pairs) {
        ObjectNode root = JSON.createObjectNode();
        var array = root.putArray("sessions");
        pairs.forEach(pair -> writePair(array.addObject(), pair));
        return encode(root);
    }

    public static byte[] encodeResponse(List<SessionDecision> decisions) {
        ObjectNode root = JSON.createObjectNode().put("message", "sessions validated");
        var array = root.putObject("data").putArray("sessions");
        decisions.forEach(decision -> {
            ObjectNode item = array.addObject();
            writePair(item, decision.pair());
            item.put("status", decision.active() ? "ACTIVE" : "INACTIVE");
            if (decision.active()) {
                item.put("session_generation", decision.generation());
                item.put("remaining_session_ms", decision.remainingMillis());
            }
        });
        return encode(root);
    }

    public static JsonNode tree(byte[] bytes) {
        try {
            JsonNode tree = JSON.readTree(bytes);
            if (tree == null) {
                throw invalid();
            }
            return tree;
        } catch (IOException failure) {
            throw invalid();
        }
    }

    public static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) {
            throw invalid();
        }
        for (String name : names) {
            if (!node.hasNonNull(name)) {
                throw invalid();
            }
        }
    }

    public static long number(JsonNode node, String name, long minimum) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < minimum || value.longValue() > SessionPair.MAX_SAFE_INTEGER) {
            throw invalid();
        }
        return value.longValue();
    }

    private static SessionPair pair(JsonNode item) {
        if (item == null || !item.path("sid").isTextual()) {
            throw invalid();
        }
        return new SessionPair(number(item, "user_id", 1), item.get("sid").asText());
    }

    private static void writePair(ObjectNode item, SessionPair pair) {
        item.put("user_id", pair.userId()).put("sid", pair.sid());
    }

    private static byte[] encode(JsonNode tree) {
        try {
            return JSON.writeValueAsBytes(tree);
        } catch (IOException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid internal authentication message");
    }
}
