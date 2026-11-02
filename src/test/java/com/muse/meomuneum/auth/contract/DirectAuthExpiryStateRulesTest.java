package com.muse.meomuneum.auth.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class DirectAuthExpiryStateRulesTest {
    private static final List<String> FIELDS = List.of("clientExpiresAt", "effectiveExpiresAt");

    @Test
    void expiryRulesCoverEachOverlayStateAndRequiredNullablePair() throws Exception {
        Map<String, Object> schemas = schemas();
        verify(map(schemas.get("CurrentOperationSummary")), "kind", List.of("LOGOUT"),
                List.of("LOGIN", "REFRESH", "LOGOUT"));
        verify(map(schemas.get("RefreshRecoveryStatusData")), "status", List.of("NOT_RECEIVED"),
                List.of("NOT_RECEIVED", "IN_PROGRESS", "SUCCEEDED"));
        verify(map(schemas.get("RefreshRecoveryCurrentData")), "status", List.of("NONE", "EXPIRED"),
                List.of("NONE", "IN_PROGRESS", "SUCCEEDED", "RESULT_STALE", "EXPIRED"));
        verify(map(schemas.get("RefreshRecoveryLeaseExpiredData")), "status", List.of(), List.of("LEASE_EXPIRED"));
    }

    @Test
    void missingFieldNullRuleOrPairRuleMutationsFailTheCheck() throws Exception {
        Map<String, Object> schemas = schemas();
        Map<String, Object> schema = map(schemas.get("RefreshRecoveryCurrentData"));
        List<String> nullStates = List.of("NONE", "EXPIRED");
        List<String> statuses = List.of("NONE", "IN_PROGRESS", "SUCCEEDED", "RESULT_STALE", "EXPIRED");
        verify(schema, "status", nullStates, statuses);
        List<?> required = (List<?>) schema.get("required");
        schema.put("required", new ArrayList<>(required));
        ((List<?>) schema.get("required")).remove("effectiveExpiresAt");
        assertThrows(AssertionError.class, () -> verify(schema, "status", nullStates, statuses));
        schema.put("required", required);
        List<?> allOf = (List<?>) schema.get("allOf");
        schema.put("allOf", new ArrayList<>(allOf));
        ((List<?>) schema.get("allOf")).remove(0);
        assertThrows(AssertionError.class, () -> verify(schema, "status", nullStates, statuses));
        schema.put("allOf", new ArrayList<>(allOf));
        ((List<?>) schema.get("allOf")).remove(1);
        assertThrows(AssertionError.class, () -> verify(schema, "status", nullStates, statuses));
    }

    @Test
    void loginLiveAndLegacyPairsRemainDistinctFromNullOnlyStates() throws Exception {
        Map<String, Object> schemas = schemas();
        Map<String, Object> current = map(schemas.get("CurrentOperationSummary"));
        for (String status : List.of("IN_PROGRESS", "SUCCEEDED", "NOT_ADMITTED", "INVALID_CREDENTIALS",
                "SUPERSEDED", "REAUTH_REQUIRED")) {
            assertThat(acceptsExpiryProjection(current, "kind", "LOGIN", "1800000001001", "1800000001000", status))
                    .as("known live canonical LOGIN " + status + " wire projection").isTrue();
        }
        assertThat(acceptsExpiryProjection(current, "kind", "LOGIN", "1800000000123", "1800000000000"))
                .as("original absolute expiry pair independent of expires_in=900").isTrue();
        assertThat(acceptsExpiryProjection(current, "kind", "LOGIN", "1", "1", "EXPIRED")).isFalse();
        assertThat(acceptsExpiryProjection(current, "kind", "LOGIN", null, null))
                .as("unknown legacy, absent, expired or scrubbed wire projection").isTrue();
        for (String boundary : List.of("1", "9007199254740991")) {
            assertThat(acceptsExpiryProjection(current, "kind", "LOGIN", boundary, boundary)).isTrue();
        }
        for (Object invalid : List.of("", " ", "0", "01", "+1", "-1", "1.0", "1e3", "1,2",
                "9007199254740992", 1L)) {
            assertThat(acceptsExpiryProjection(current, "kind", "LOGIN", invalid, "1")).isFalse();
            assertThat(acceptsExpiryProjection(current, "kind", "LOGIN", "1", invalid)).isFalse();
        }
        assertThat(acceptsExpiryProjection(current, "kind", "LOGIN", null, "1")).isFalse();
        assertThat(acceptsExpiryProjection(current, "kind", "LOGIN", "1", null)).isFalse();
        for (String[] state : List.of(new String[]{"CurrentOperationSummary", "kind", "LOGOUT"},
                new String[]{"RefreshRecoveryStatusData", "status", "NOT_RECEIVED"},
                new String[]{"RefreshRecoveryCurrentData", "status", "NONE"},
                new String[]{"RefreshRecoveryCurrentData", "status", "EXPIRED"})) {
            Map<String, Object> schema = map(schemas.get(state[0]));
            assertThat(acceptsExpiryProjection(schema, state[1], state[2], null, null)).isTrue();
            assertThat(acceptsExpiryProjection(schema, state[1], state[2], "1", "1")).isFalse();
        }
    }

    @Test
    void loginNullOnlyLogoutRelaxationAndUnpairedMutationsAreRejected() throws Exception {
        Map<String, Object> current = map(schemas().get("CurrentOperationSummary"));
        List<?> rules = (List<?>) current.get("allOf");
        Map<String, Object> kinds = map(map(map(map(rules.get(0)).get("if")).get("properties")).get("kind"));
        kinds.put("enum", List.of("LOGIN", "LOGOUT"));
        assertThrows(AssertionError.class, () -> assertThat(acceptsExpiryProjection(
                current, "kind", "LOGIN", "1", "1")).isTrue());
        kinds.put("enum", List.of("LOGOUT"));
        current.put("allOf", new ArrayList<>(rules));
        ((List<?>) current.get("allOf")).remove(0);
        assertThrows(AssertionError.class, () -> assertThat(acceptsExpiryProjection(
                current, "kind", "LOGOUT", "1", "1")).isFalse());
        current.put("allOf", new ArrayList<>(rules));
        ((List<?>) current.get("allOf")).remove(1);
        assertThrows(AssertionError.class, () -> assertThat(acceptsExpiryProjection(
                current, "kind", "LOGIN", null, "1")).isFalse());
    }

    @Test
    void fractionalWriterClockAdmissionAndFloorCutoffNeverExtendTheDeadline() {
        for (long t0 : List.of(1800000000000001L, 1800000000000999L)) {
            long maximum = 1800000900000L;
            assertThat(t0 < maximum * 1000 && maximum * 1000 <= t0 + 900000000).isTrue();
            assertThat((maximum + 1) * 1000 <= t0 + 900000000).isFalse();
            assertThat(1800000000000L * 1000 > t0).isFalse();
            assertThat(1800000000001L * 1000 > t0).isTrue();
            assertThat(Math.floorDiv(Math.min(maximum * 1000, t0 + 900000000), 1000))
                    .isEqualTo(maximum);
            for (long source : List.of(t0, t0 + 900000000)) {
                long expected = source < t0 + 900000000 ? 1800000000000L : maximum;
                assertThat(Math.floorDiv(source, 1000)).isEqualTo(expected);
                assertThrows(AssertionError.class, () -> assertThat((source + 999) / 1000)
                        .as("fractional first-claim/900s ceil mutation").isEqualTo(expected));
                if (source % 1000 == 999) {
                    assertThrows(AssertionError.class, () -> assertThat((source + 500) / 1000)
                            .as("fractional first-claim/900s round mutation").isEqualTo(expected));
                }
            }
            for (long receipt : List.of(1800000001000001L, 1800000001000999L)) {
                long cutoff = Math.min(Math.min(maximum * 1000, t0 + 900000000), receipt);
                long effective = Math.floorDiv(cutoff, 1000);
                assertThat(effective).isEqualTo(1800000001000L);
                assertThat(effective * 1000).isLessThanOrEqualTo(cutoff);
                assertThat(cutoff - effective * 1000).isBetween(1L, 999L);
                assertThat(withinCutoff(effective * 1000 - 1, effective)).isTrue();
                for (long now : List.of(effective * 1000, effective * 1000 + 1,
                        cutoff - 1, cutoff, cutoff + 1)) {
                    assertThat(withinCutoff(now, effective)).isFalse();
                }
                assertThrows(AssertionError.class, () -> assertThat((cutoff + 999) / 1000)
                        .as("ceil mutation").isEqualTo(1800000001000L));
                if (receipt % 1000 == 999) {
                    assertThrows(AssertionError.class, () -> assertThat((cutoff + 500) / 1000)
                            .as("nearest mutation").isEqualTo(1800000001000L));
                }
            }
            for (long receipt : List.of(t0 - 1, t0)) {
                long cutoff = Math.min(Math.min(maximum * 1000, t0 + 900000000), receipt);
                long effective = Math.floorDiv(cutoff, 1000);
                assertThat(withinCutoff(t0, effective))
                        .as("receipt cutoff at or before first claim fails closed").isFalse();
            }
            long laterCutoff = Math.min(Math.min(maximum * 1000, t0 + 900000000), t0 + 1);
            assertThat(withinCutoff(t0, Math.floorDiv(laterCutoff, 1000))).isEqualTo(t0 % 1000 == 999);
        }
        long aligned = 1800000000000000L;
        for (long delta : List.of(-1L, 0L, 1L, 900000L, 900001L)) {
            long client = aligned / 1000 + delta;
            assertThat(aligned < client * 1000 && client * 1000 <= aligned + 900000000)
                    .as("aligned t0 delta " + delta).isEqualTo(delta == 1 || delta == 900000);
        }
        assertThat(9007199254740991L * 1000).isEqualTo(9007199254740991000L);
    }

    private static boolean withinCutoff(long nowUs, long effectiveMs) {
        return nowUs < effectiveMs * 1000;
    }

    private static boolean acceptsExpiryProjection(Map<String, Object> schema, String selector, String state,
            Object client, Object effective, String... statuses) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(selector, state);
        values.put("clientExpiresAt", client);
        values.put("effectiveExpiresAt", effective);
        values.putIfAbsent("status", statuses.length == 0 ? "SUCCEEDED" : statuses[0]);
        Map<String, Object> properties = map(schema.get("properties"));
        if (!matchesProjection(properties, values)) {
            return false;
        }
        for (String field : FIELDS) {
            List<?> variants = (List<?>) map(properties.get(field)).get("oneOf");
            if (variants.stream().map(DirectAuthExpiryStateRulesTest::map)
                    .noneMatch(rule -> matchesValue(rule, values.get(field)))) {
                return false;
            }
        }
        for (Object entry : (List<?>) schema.get("allOf")) {
            Map<String, Object> rule = map(entry);
            Map<String, Object> when = map(rule.get("if"));
            if (!values.keySet().containsAll((List<?>) when.get("required"))) {
                continue;
            }
            boolean applies = matchesProjection(map(when.get("properties")), values);
            Object branch = rule.get(applies ? "then" : "else");
            if (branch != null && !matchesProjection(map(map(branch).get("properties")), values)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesProjection(Map<String, Object> properties, Map<String, Object> values) {
        return properties.entrySet().stream().filter(entry -> values.containsKey(entry.getKey()))
                .allMatch(entry -> matchesValue(map(entry.getValue()), values.get(entry.getKey())));
    }

    private static boolean matchesValue(Map<String, Object> rule, Object value) {
        if (rule.containsKey("enum") && !((List<?>) rule.get("enum")).contains(value)) {
            return false;
        }
        if ("null".equals(rule.get("type"))) {
            return value == null;
        }
        if ("string".equals(rule.get("type"))) {
            return value instanceof String text &&
                    (!rule.containsKey("pattern") || text.matches((String) rule.get("pattern")));
        }
        return !rule.containsKey("const") || rule.get("const").equals(value);
    }

    private static void verify(Map<String, Object> schema, String selector, List<String> nullStates,
            List<String> expectedStates) {
        assertThat(((List<?>) schema.get("required")).containsAll(FIELDS)).isTrue();
        Map<String, Object> properties = map(schema.get("properties"));
        Map<String, Object> selectorSchema = map(properties.get(selector));
        if (selectorSchema.containsKey("const")) {
            assertThat(List.of(selectorSchema.get("const"))).containsExactlyElementsOf(expectedStates);
        } else {
            assertThat(selectorSchema.get("enum")).isEqualTo(expectedStates);
        }
        for (String field : FIELDS) {
            List<?> variants = (List<?>) map(properties.get(field)).get("oneOf");
            assertThat(variants).hasSize(2);
            assertThat(map(variants.get(0)).get("type")).isEqualTo("string");
            assertThat(map(variants.get(0)).get("pattern")).isInstanceOf(String.class);
            assertThat(map(variants.get(1)).get("type")).isEqualTo("null");
        }
        List<?> conditions = (List<?>) schema.get("allOf");
        if (!nullStates.isEmpty()) {
            assertThat(conditions.stream().map(DirectAuthExpiryStateRulesTest::map)
                    .anyMatch(condition -> nullStateRule(condition, selector, nullStates))).isTrue();
        }
        assertThat(conditions.stream().map(DirectAuthExpiryStateRulesTest::map)
                .anyMatch(DirectAuthExpiryStateRulesTest::pairRule)).isTrue();
    }

    private static boolean nullStateRule(Map<String, Object> rule, String selector, List<String> states) {
        Map<String, Object> when = map(rule.get("if"));
        if (!((List<?>) when.get("required")).contains(selector)) {
            return false;
        }
        Object selectorRule = map(when.get("properties")).get(selector);
        if (!(selectorRule instanceof Map<?, ?>) || !states.equals(map(selectorRule).get("enum"))) {
            return false;
        }
        Map<String, Object> then = map(map(rule.get("then")).get("properties"));
        return FIELDS.stream().allMatch(field -> then.containsKey(field) &&
                "null".equals(map(then.get(field)).get("type")));
    }

    private static boolean pairRule(Map<String, Object> rule) {
        Map<String, Object> when = map(rule.get("if"));
        if (!((List<?>) when.get("required")).contains("clientExpiresAt")) {
            return false;
        }
        Object client = map(when.get("properties")).get("clientExpiresAt");
        if (!(client instanceof Map<?, ?>) || !"null".equals(map(client).get("type")) ||
                !rule.containsKey("else")) {
            return false;
        }
        Map<String, Object> then = map(map(rule.get("then")).get("properties"));
        Map<String, Object> otherwise = map(map(rule.get("else")).get("properties"));
        return "null".equals(map(then.get("effectiveExpiresAt")).get("type")) &&
                "string".equals(map(otherwise.get("effectiveExpiresAt")).get("type"));
    }

    private static Map<String, Object> schemas() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(Path.of("api/auth-direct-v2.openapi.yaml"))) {
            Map<String, Object> root = map(new Yaml(new SafeConstructor(options)).load(input));
            return map(map(root.get("components")).get("schemas"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
