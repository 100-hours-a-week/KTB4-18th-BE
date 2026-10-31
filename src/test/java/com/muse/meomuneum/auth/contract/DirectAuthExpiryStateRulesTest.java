package com.muse.meomuneum.auth.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        verify(map(schemas.get("CurrentOperationSummary")), "kind", List.of("LOGIN", "LOGOUT"),
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
