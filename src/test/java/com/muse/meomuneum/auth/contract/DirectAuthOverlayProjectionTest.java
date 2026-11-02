package com.muse.meomuneum.auth.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.fasterxml.jackson.databind.ObjectMapper;

class DirectAuthOverlayProjectionTest {
    private static final Path FIXTURES = Path.of("src/test/resources/auth-contract");
    private static final String RECOVERY = "/token/refresh/operations/{operationId}/session-recovery";

    @Test
    void allRoutesStatusesAndErrorCodesMatchBaseWithOnlyOverlayAdditions() throws Exception {
        Map<String, Object> base = json("base-openapi.json");
        Map<String, Object> actual = yaml();
        Map<String, Object> basePaths = map(base.get("paths"));
        Map<String, Object> paths = map(actual.get("paths"));
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(basePaths.keySet());
        for (String path : basePaths.keySet()) {
            Map<String, Object> baseMethods = map(basePaths.get(path));
            Map<String, Object> methods = map(paths.get(path));
            assertThat(methods.keySet()).as(path).containsExactlyInAnyOrderElementsOf(baseMethods.keySet());
            for (String method : baseMethods.keySet()) {
                Map<String, Object> expected = map(map(baseMethods.get(method)).get("responses"));
                Map<String, Object> responses = map(map(methods.get(method)).get("responses"));
                assertThat(responses.keySet()).as(path + " " + method)
                        .containsExactlyInAnyOrderElementsOf(expected.keySet());
                for (String status : expected.keySet()) {
                    Set<String> codes = messageValues(expected.get(status), base);
                    if (method.equals("post") && status.equals("400") &&
                            (path.equals("/token/refresh") || path.equals(RECOVERY))) {
                        codes.add("INVALID_CLIENT_EXPIRY");
                    }
                    assertThat(messageValues(responses.get(status), actual))
                            .as(path + " " + method + " " + status).isEqualTo(codes);
                }
            }
        }
    }

    @Test
    void headersAndRequiredNullableFieldsProjectTheActualOverlay() throws Exception {
        Map<String, Object> overlay = json("proposal-overlay.json");
        List<?> overrides = (List<?>) overlay.get("overrides");
        Map<String, Object> actual = yaml();
        Map<String, Object> paths = map(actual.get("paths"));
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> add = map(map(overrides.get(i)).get("add"));
            Map<String, Object> expected = map(add.get("header"));
            String path = i == 1 ? "/token/refresh" : RECOVERY;
            Map<String, Object> operation = map(map(paths.get(path)).get("post"));
            Map<String, Object> header = parameter(operation, (String) expected.get("name"));
            assertThat(header.get("required")).isEqualTo(expected.get("required"));
            assertThat(map(header.get("schema")).get("pattern"))
                    .isEqualTo(map(expected.get("schema")).get("pattern"));
        }
        for (String name : List.of("CurrentOperationSummary", "RefreshRecoveryStatusData",
                "RefreshRecoveryCurrentData", "RefreshRecoveryLeaseExpiredData")) {
            Map<String, Object> expected = map(map(overrides.get(name.equals("CurrentOperationSummary") ? 3 : 4))
                    .get("add"));
            Map<String, Object> actualSchema = schema(actual, name);
            assertThat(actualSchema.get("additionalProperties")).isEqualTo(false);
            assertThat(strings(actualSchema.get("required"))).containsAll(strings(expected.get("required")));
            for (String field : strings(expected.get("required"))) {
                List<?> source = (List<?>) map(map(expected.get("properties")).get(field)).get("oneOf");
                List<?> projected = (List<?>) map(map(actualSchema.get("properties")).get(field)).get("oneOf");
                assertThat(map(projected.get(0)).get("pattern")).isEqualTo(map(source.get(0)).get("pattern"));
                assertThat(map(projected.get(1)).get("type")).isEqualTo("null");
            }
        }
        List<?> allowed = (List<?>) map(map(overrides.get(6)).get("add")).get("allowedRequestHeaders");
        assertThat(strings(actual.get("x-cors-allow-headers"))).containsAll(strings(allowed));
    }

    @Test
    void logoutProofAndNewLiveReceiptRequirementsAreFenced() throws Exception {
        Map<String, Object> document = yaml();
        Map<String, Object> logout = map(map(map(document.get("paths")).get("/logout")).get("post"));
        List<?> alternatives = (List<?>) map(logout.get("x-operation-replay-contract")).get("proofAlternatives");
        assertThat(strings(map(alternatives.get(0)).get("allOf")).get(0))
                .contains("keyed HMAC", "generation alone is insufficient");
        assertThat(strings(map(alternatives.get(1)).get("allOf")).get(0))
                .contains("exact result flow proof", "generation alone is insufficient");
        Map<String, Object> migration = map(document.get("x-schema-migration-contract"));
        Map<String, Object> direct = ((List<?>) migration.get("newTables")).stream()
                .map(DirectAuthOverlayProjectionTest::map)
                .filter(table -> "auth_direct_operation_receipts".equals(table.get("name")))
                .findFirst().orElseThrow();
        Map<String, Object> invariants = map(direct.get("operationKindInvariants"));
        assertThat(strings(map(invariants.get("REFRESH")).get("requiredNonNullForNewLiveRows")))
                .contains("client_expires_at_ms", "effective_expires_at_ms", "result_session_sid",
                        "original_flow_cookie_hmac", "encrypted_result_flow_cookie");
        assertThat(strings(map(invariants.get("LOGOUT")).get("requiredNonNullForNewLiveRows")))
                .contains("original_flow_cookie_hmac", "encrypted_result_flow_cookie");
        assertThat((String) map(direct.get("rowInvariants")).get("SUCCEEDED"))
                .contains("Legacy", "unknown", "fail closed");
    }

    private static Set<String> messageValues(Object value, Map<String, Object> document) {
        Set<String> found = new TreeSet<>();
        collectMessages(value, document, new HashSet<>(), found, false);
        return found;
    }

    private static void collectMessages(Object value, Map<String, Object> document,
            Set<String> seen, Set<String> found, boolean inMessage) {
        if (value instanceof Map<?, ?> entries) {
            entries.forEach((key, child) -> {
                if ("$ref".equals(key) && child instanceof String ref && ref.startsWith("#/components/") &&
                        seen.add(ref + ":" + inMessage)) {
                    Object target = document;
                    for (String part : ref.substring(2).split("/")) {
                        target = map(target).get(part.replace("~1", "/").replace("~0", "~"));
                    }
                    collectMessages(target, document, seen, found, inMessage);
                } else if (inMessage && "const".equals(key) && child instanceof String text) {
                    found.add(text);
                } else if (inMessage && "enum".equals(key) && child instanceof List<?> values) {
                    values.forEach(item -> found.add((String) item));
                } else {
                    collectMessages(child, document, seen, found, inMessage || "message".equals(key));
                }
            });
        } else if (value instanceof List<?> entries) {
            entries.forEach(child -> collectMessages(child, document, seen, found, inMessage));
        }
    }

    @Test
    void unexpectedMessageEnumAndConstAreDetected() throws Exception {
        Map<String, Object> document = yaml();
        Map<String, Object> errors = schema(document, "ErrorEnvelopeRefreshRecoveryRead400");
        Map<String, Object> properties = map(map(((List<?>) errors.get("allOf")).get(1)).get("properties"));
        Map<String, Object> message = map(properties.get("message"));
        Set<String> original = messageValues(errors, document);
        @SuppressWarnings("unchecked")
        List<Object> values = (List<Object>) message.get("enum");
        values.add("UNEXPECTED_ERROR");
        assertThat(messageValues(errors, document)).contains("UNEXPECTED_ERROR").isNotEqualTo(original);
        values.remove(values.size() - 1);
        message.remove("enum");
        message.put("const", "UNEXPECTED_ERROR");
        assertThat(messageValues(errors, document)).contains("UNEXPECTED_ERROR").isNotEqualTo(original);
    }

    private static Map<String, Object> parameter(Map<String, Object> operation, String name) {
        return ((List<?>) operation.get("parameters")).stream().map(DirectAuthOverlayProjectionTest::map)
                .filter(item -> name.equals(item.get("name"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> schema(Map<String, Object> document, String name) {
        return map(map(map(document.get("components")).get("schemas")).get(name));
    }

    private static Map<String, Object> yaml() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(Path.of("api/auth-direct-v2.openapi.yaml"))) {
            return map(new Yaml(new SafeConstructor(options)).load(input));
        }
    }

    private static Map<String, Object> json(String name) throws Exception {
        return map(new ObjectMapper().readValue(FIXTURES.resolve(name).toFile(), Map.class));
    }

    private static List<String> strings(Object value) {
        return ((List<?>) value).stream().map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
