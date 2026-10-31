package com.muse.meomuneum.auth.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
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
    private static final String APPROVED_OVERLAY_SHA256 =
            "f716de5abffd6b1b651aa324c17be3d050b47b976920a0844937bc10a3b6b928";

    @Test
    void allRoutesStatusesAndErrorCodesMatchBaseWithOnlyApprovedRefresh500Addition() throws Exception {
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
                Set<String> expectedStatuses = new TreeSet<>(expected.keySet());
                if (path.equals("/token/refresh") && method.equals("post")) {
                    expectedStatuses.add("500");
                }
                assertThat(new TreeSet<>(responses.keySet())).as(path + " " + method)
                        .isEqualTo(expectedStatuses);
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
    void pinnedOverlayProjectsRoutingReplayRetentionAndAuthoritySemantics() throws Exception {
        byte[] fixtureBytes = Files.readAllBytes(FIXTURES.resolve("proposal-overlay.json"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixtureBytes)))
                .isEqualTo(APPROVED_OVERLAY_SHA256);
        Map<String, Object> overlay = json("proposal-overlay.json");
        assertThat(overlay.get("kind")).isEqualTo("explicit-contract-overrides");
        assertThat(overlay.get("status")).isEqualTo("proposed_not_implemented");

        Map<String, Object> document = yaml();
        assertThat((String) map(document.get("info")).get("description")).doesNotContain("auth-contract-matrix.json");
        Map<String, Object> refresh = operation(document, "/token/refresh", "post");
        Map<String, Object> routing = map(refresh.get("x-refresh-mode-routing"));
        Map<String, Object> protocol = map(overlay.get("protocol_routing"));
        assertThat(routing.get("cases")).isEqualTo(protocol.get("cases"));
        assertThat(routing.get("markerless_cutover")).isEqualTo(overlay.get("markerless_cutover"));

        Map<String, Object> refreshOverlay = overlayOperation(overlay, "paths./token/refresh.post");
        Map<String, Object> revisit = map(refresh.get("x-refresh-revisit-contract"));
        assertThat(revisit.get("maximumAutomaticFirstDispatches"))
                .isEqualTo(map(refreshOverlay.get("first_dispatch")).get("max_automatic_dispatches"));
        assertThat(revisit.get("currentOperationNullProvesNoPriorRequest"))
                .isEqualTo(map(refreshOverlay.get("first_dispatch")).get("current_operation_null_proves_no_prior_request"));
        assertThat(revisit.get("never")).isEqualTo(map(refreshOverlay.get("first_dispatch")).get("never"));
        assertThat(map(revisit.get("receipt_first_proof_order")))
                .isEqualTo(map(refreshOverlay.get("receipt_first_proof_order")));

        Map<String, Object> sourceLoser = map(refreshOverlay.get("definitive_b_loss"));
        Map<String, Object> projectedLoser = map(revisit.get("definitiveBLoss"));
        assertThat(projectedLoser.get("status")).isEqualTo(sourceLoser.get("http_status"));
        assertThat(projectedLoser.get("code")).isEqualTo(sourceLoser.get("code"));
        assertThat(projectedLoser.get("allConditions")).isEqualTo(sourceLoser.get("all_conditions"));
        assertThat(map(projectedLoser.get("negativeTombstone")).get("admission"))
                .isEqualTo(map(sourceLoser.get("negative_tombstone")).get("admission"));
        assertThat(map(revisit.get("allFailureResponses")))
                .isEqualTo(map(refreshOverlay.get("all_failure_responses")));
        assertThat(map(refresh.get("x-refresh-db-authority")))
                .isEqualTo(map(refreshOverlay.get("db_authority_projection_failure")));
        assertThat(map(refresh.get("x-refresh-retention"))).isEqualTo(map(overlay.get("retention")));

        Map<String, Object> current = operation(document, "/flow/current-operation", "get");
        assertThat(map(current.get("x-proposal-overlay-current-operation")))
                .isEqualTo(map(overlayOperation(overlay, "paths./flow/current-operation.get")));
        Map<String, Object> logout = operation(document, "/logout", "post");
        assertThat(map(logout.get("x-proposal-overlay-logout")))
                .isEqualTo(map(overlayOperation(overlay, "paths./logout.post")));

        Map<String, Object> negativePolicy = map(refresh.get("x-refresh-negative-receipt-policy"));
        assertThat(negativePolicy.get("perFlowLimit")).isEqualTo(20);
        assertThat(negativePolicy.get("windowSeconds")).isEqualTo(900);
        assertThat(strings(negativePolicy.get("appliesTo")))
                .contains("Existing login rejection receipts", "Negative REFRESH receipts");
        assertThat((String) map(sourceLoser.get("negative_tombstone")).get("admission"))
                .contains("20 rejections per 900 seconds");
    }

    @Test
    void expiryHeadersAndNullableFieldsRemainExplicit() throws Exception {
        Map<String, Object> document = yaml();
        Map<String, Object> paths = map(document.get("paths"));
        for (String[] route : List.of(new String[]{"/token/refresh", "X-Auth-Client-Expires-At"},
                new String[]{RECOVERY, "X-Auth-Recovery-Client-Expires-At"})) {
            Map<String, Object> header = parameter(map(map(paths.get(route[0])).get("post")), route[1]);
            assertThat(header.get("required")).isEqualTo(true);
            assertThat(map(header.get("schema")).get("pattern")).isInstanceOf(String.class);
        }
        for (String name : List.of("CurrentOperationSummary", "RefreshRecoveryStatusData",
                "RefreshRecoveryCurrentData", "RefreshRecoveryLeaseExpiredData")) {
            Map<String, Object> schema = schema(document, name);
            assertThat(schema.get("additionalProperties")).isEqualTo(false);
            assertThat(strings(schema.get("required"))).contains("clientExpiresAt", "effectiveExpiresAt");
            Map<String, Object> properties = map(schema.get("properties"));
            for (String field : List.of("clientExpiresAt", "effectiveExpiresAt")) {
                List<?> choices = (List<?>) map(properties.get(field)).get("oneOf");
                assertThat(map(choices.get(1)).get("type")).isEqualTo("null");
            }
        }
        assertThat(strings(document.get("x-cors-allow-headers")))
                .contains("X-Auth-Client-Expires-At", "X-Auth-Recovery-Client-Expires-At");
    }

    @Test
    void currentOperationProofPrecedenceAndApplicationFailurePrivacyAreExplicit() throws Exception {
        Map<String, Object> document = yaml();
        Map<String, Object> current = operation(document, "/flow/current-operation", "get");
        Map<String, Object> proof = map(current.get("x-auth-proof-selection"));
        assertThat(strings(proof.get("precedence"))).anyMatch(text ->
                text.contains("uniquely valid current direct flow-proof") && text.contains("binding cookie"));
        assertThat(strings(proof.get("precedence"))).anyMatch(text ->
                text.contains("more than one distinct flow") && text.contains("do not attempt binding fallback"));
        assertThat(strings(proof.get("precedence"))).anyMatch(text ->
                text.contains("Only if no current flow-proof cookie validates"));

        Map<String, Object> refresh = operation(document, "/token/refresh", "post");
        Map<String, Object> responses = map(refresh.get("responses"));
        for (String status : List.of("400", "401", "403", "409", "428", "500", "503")) {
            Map<String, Object> response = map(responses.get(status));
            if (response.containsKey("headers")) {
                assertThat(map(response.get("headers"))).as("refresh " + status).doesNotContainKey("Set-Cookie");
            }
        }
        Map<String, Object> response500 = map(responses.get("500"));
        Map<String, Object> schema = map(map(map(response500.get("content")).get("application/json")).get("schema"));
        assertThat(strings(schema.get("required"))).containsExactlyInAnyOrder("message", "data");
        Map<String, Object> properties = map(schema.get("properties"));
        assertThat(map(properties.get("message")).get("const")).isEqualTo("internal server error");
        assertThat(map(properties.get("data")).get("type")).isEqualTo("null");
        assertThat(map(refresh.get("x-refresh-revisit-contract")).get("applicationFailureStatuses"))
                .isEqualTo(List.of(400, 401, 403, 409, 428, 500, 503));
    }

    @Test
    void logoutProofAndNewLiveReceiptRequirementsAreFenced() throws Exception {
        Map<String, Object> document = yaml();
        Map<String, Object> logout = operation(document, "/logout", "post");
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

    private static Map<String, Object> operation(Map<String, Object> document, String path, String method) {
        return map(map(map(document.get("paths")).get(path)).get(method));
    }

    private static Map<String, Object> overlayOperation(Map<String, Object> overlay, String target) {
        return ((List<?>) overlay.get("operations")).stream().map(DirectAuthOverlayProjectionTest::map)
                .filter(operation -> target.equals(operation.get("target")))
                .map(operation -> map(operation.get("updates"))).findFirst().orElseThrow();
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
