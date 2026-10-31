package com.muse.meomuneum.auth.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

class DirectAuthOpenApiContractTest {
    private static final Path CONTRACT = Path.of("api/auth-direct-v2.openapi.yaml");
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final List<Route> ROUTES = List.of(
            new Route("/flow", "get", "200,400,404,409,503",
                    "7ec202552ba5014a905636082af325560a7fade2d149fad81a17b5eb4b8972ba"),
            new Route("/login", "post", "200,202,400,401,403,409,429,503",
                    "c675750d581364aa4020a34c013c9e9e43b90b57a092df7461f5afcba5aff1e9"),
            new Route("/login/operations/{operationId}", "get", "200,202,400,404,503",
                    "c9db2c814e11ecfb815349b4bc5438fd1a378ad1f5591fb2bc0b304110434f5a"),
            new Route("/login/operations/{operationId}/session-recovery", "post", "200,202,400,403,404,409,503",
                    "e7da6c59f7d373ff68ca17b5d310a53fb903d647b3162a3e0789e036d051ea83"),
            new Route("/token/refresh", "post", "200,400,401,403,409,428,500,503",
                    "0992bf0556c04cd86632dc617310e314f77a13ae5627df16b1bae015a36ca5bf"),
            new Route("/logout", "post", "200,400,401,403,409,428,503",
                    "aab7d7149413845604fbeb5cb6f6b0c147a0024b4223118b2c0530c3eac11d51"),
            new Route("/token/csrf", "get", "200", "47dc02ab3e0ef9e5e8a9f59ea994e39c6608cf2f356139111b977bf239169068"),
            new Route("/flow/current-operation", "get", "200,400,404,409,503",
                    "34b355794e1ebbf1a52189e2e64a4b65515aa65322ccf1853a3a779da921def1"),
            new Route("/flow/rebootstrap", "post", "200,400,403,404,409,429,503",
                    "8d8452fbf7d7ad891c664df3ef0b6fefba2dfc48abd4856076db4a78b2059f6b"),
            new Route("/token/refresh/operations/{operationId}/session-recovery", "post",
                    "200,202,400,403,404,409,429,503",
                    "6ccc5f2fd439da31aa8d55db0f5e505cd58f3130782dc4585fffabf07c6d2958"),
            new Route("/token/refresh/operations/{operationId}/session-recovery/{recoveryOperationId}", "get",
                    "200,400,403,404,409,503", "587edc24d43f40d5e4604d6296ab93cd4117ecc2001e650ba0a25944853d18e8"),
            new Route("/token/refresh/operations/{operationId}/session-recovery/current", "get",
                    "200,400,403,404,409,503", "f8349710d0762d4f1ad020910e610529ca89e73cc28b961ea4a3591309d574ba"),
            new Route("/flows", "post", "200,400,403,409,429,503",
                    "ddcfc689dd19c574d36ede06141d4eac784cb825827e2e4c9dd152aaf50b9178"));

    @Test
    void routesParametersSecurityAndResponsesMatchApprovedContract() throws Exception {
        Map<String, Object> document = document();
        assertThat(document.get("openapi")).isEqualTo("3.1.0");
        assertThat(map(document.get("info")).get("version")).isEqualTo("2.4.0");
        assertThat(document.get("servers")).isEqualTo(List.of(Map.of("url", "/api/v1/auth")));
        Map<String, Object> paths = map(document.get("paths"));
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(
                ROUTES.stream().map(Route::path).toList());
        for (Route route : ROUTES) {
            Map<String, Object> methods = map(paths.get(route.path()));
            assertThat(methods.keySet()).containsExactly(route.method());
            Map<String, Object> operation = map(methods.get(route.method()));
            assertThat(new TreeSet<>(map(operation.get("responses")).keySet()))
                    .containsExactly(route.statuses().split(","));
            assertThat(hash(operation)).as(route.path() + " " + route.method()
                    + " parameters, security, responses and request body").isEqualTo(route.digest());
        }
    }

    @Test
    void schemasAndCookieSecurityMatchApprovedContractAndReferencesResolve() throws Exception {
        Map<String, Object> document = document();
        Map<String, Object> components = map(document.get("components"));
        assertThat(hash(components.get("schemas"))).as("schemas, closed error codes and conditions")
                .isEqualTo("6e9440d9a67929db190e70ffe0d9cab12e7a40542fb932c6b710abaa7de5c261");
        assertThat(hash(components.get("securitySchemes"))).as("security schemes")
                .isEqualTo("788d536415e602398a62e3d9af82426dbc0c2da08e80856c4832ccd6e141ffd6");
        checkReferences(document, document);
    }

    @Test
    void expiryOverlayAndRecoveryReadSecurityAreExplicit() throws Exception {
        Map<String, Object> document = document();
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        String recovery = "/token/refresh/operations/{operationId}/session-recovery";
        String regex = null;
        for (String[] route : List.of(new String[]{"/token/refresh", "X-Auth-Client-Expires-At"},
                new String[]{recovery, "X-Auth-Recovery-Client-Expires-At"})) {
            Map<String, Object> header = parameter(map(map(paths.get(route[0])).get("post")), route[1]);
            assertThat(header.get("required")).isEqualTo(true);
            String current = (String) map(header.get("schema")).get("pattern");
            if (regex != null) {
                assertThat(current).isEqualTo(regex);
            }
            regex = current;
        }
        for (String valid : List.of("1", "999999999999999", "9007199254740990", "9007199254740991")) {
            assertThat(valid).matches(regex);
        }
        for (String invalid : List.of("", "0", "01", "-1", "+1", "1,2", "9007199254740992",
                "9999999999999999", "10000000000000000")) {
            assertThat(invalid).doesNotMatch(regex);
        }
        for (String name : List.of("CurrentOperationSummary", "RefreshRecoveryStatusData",
                "RefreshRecoveryCurrentData", "RefreshRecoveryLeaseExpiredData")) {
            Map<String, Object> schema = map(schemas.get(name));
            assertThat(schema.get("additionalProperties")).isEqualTo(false);
            assertThat(((List<?>) schema.get("required")).containsAll(List.of("clientExpiresAt", "effectiveExpiresAt")))
                    .isTrue();
            Map<String, Object> properties = map(schema.get("properties"));
            for (String field : List.of("clientExpiresAt", "effectiveExpiresAt")) {
                List<?> choices = (List<?>) map(properties.get(field)).get("oneOf");
                assertThat(map(choices.get(0)).get("pattern")).isEqualTo(regex);
                assertThat(map(choices.get(1)).get("type")).isEqualTo("null");
            }
        }
        for (String suffix : List.of("/{recoveryOperationId}", "/current")) {
            Map<String, Object> get = map(map(paths.get(recovery + suffix)).get("get"));
            assertThat(parameter(get, "Origin").get("required")).isEqualTo(false);
            Map<String, Object> fetchSite = parameter(get, "Sec-Fetch-Site");
            assertThat(map(fetchSite.get("schema"))).doesNotContainKeys("const", "enum", "pattern");
            assertThat((String) fetchSite.get("description")).contains("Origin is present", "same-origin");
            Map<String, Object> response400 = map(map(get.get("responses")).get("400"));
            Map<String, Object> content = map(map(response400.get("content")).get("application/json"));
            assertThat(map(content.get("schema")).get("$ref"))
                    .isEqualTo("#/components/schemas/ErrorEnvelopeRefreshRecoveryRead400");
            for (Object response : map(get.get("responses")).values()) {
                Map<String, Object> headers = map(map(response).get("headers"));
                assertThat(map(map(headers.get("Cache-Control")).get("schema")).get("const")).isEqualTo("no-store");
                assertThat(headers).doesNotContainKey("Set-Cookie");
            }
        }
        assertThat(((List<?>) document.get("x-cors-allow-headers"))
                .containsAll(List.of("X-Auth-Client-Expires-At", "X-Auth-Recovery-Client-Expires-At"))).isTrue();
        for (String name : List.of("ErrorEnvelopeRefresh400", "ErrorEnvelopeRefreshRecovery400")) {
            List<?> allOf = (List<?>) map(schemas.get(name)).get("allOf");
            Map<String, Object> properties = map(map(allOf.get(1)).get("properties"));
            assertThat(((List<?>) map(properties.get("message")).get("enum")).contains("INVALID_CLIENT_EXPIRY"))
                    .isTrue();
        }
    }

    @Test
    void readErrorsOwnerFencingAndCiphertextBindingsStayScoped() throws Exception {
        Map<String, Object> document = document();
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        List<?> readAllOf = (List<?>) map(schemas.get("ErrorEnvelopeRefreshRecoveryRead400")).get("allOf");
        Map<String, Object> readProperties = map(map(readAllOf.get(1)).get("properties"));
        assertThat(((List<?>) map(readProperties.get("message")).get("enum")).contains("INVALID_CLIENT_EXPIRY"))
                .isFalse();
        Map<String, Object> cipher = map(document.get("x-replay-ciphertext-contract"));
        assertThat(((List<?>) cipher.get("v2Payloads")).contains("direct-operation-flow-cookie")).isTrue();
        assertThat((String) cipher.get("storage")).contains(".encrypted_result_flow_cookie");
        assertThat((String) map(cipher.get("aadBindings")).get("direct"))
                .contains("direct-operation-flow-cookie", "REFRESH or LOGOUT", "15-field");
        Map<String, Object> migration = map(document.get("x-schema-migration-contract"));
        List<?> tables = (List<?>) migration.get("newTables");
        Map<String, Object> login = tables.stream().map(DirectAuthOpenApiContractTest::map)
                .filter(table -> "auth_login_operations".equals(table.get("name"))).findFirst().orElseThrow();
        Map<String, Object> states = map(login.get("stateInvariants"));
        assertThat(((List<?>) map(states.get("IN_PROGRESS")).get("requiredNonNull")).contains("owner_token"))
                .isTrue();
        for (String status : List.of("SUCCEEDED", "NOT_ADMITTED", "INVALID_CREDENTIALS", "SUPERSEDED",
                "REAUTH_REQUIRED", "TOMBSTONE")) {
            assertThat(((List<?>) map(states.get(status)).get("requiredNull")).contains("owner_token")).isTrue();
        }
        Map<String, Object> lease = map(migration.get("loginLeaseRecovery"));
        assertThat((String) lease.get("lateWorkerFence")).contains("owner_token", "compare-and-set");
        assertThat((String) lease.get("recoveryTransition")).contains("owner_token and lease_expires_at NULL");
    }

    private static Map<String, Object> parameter(Map<String, Object> operation, String name) {
        return ((List<?>) operation.get("parameters")).stream().map(DirectAuthOpenApiContractTest::map)
                .filter(value -> name.equals(value.get("name"))).findFirst().orElseThrow();
    }

    private static void checkReferences(Object value, Map<String, Object> document) {
        if (value instanceof Map<?, ?> entries) {
            entries.forEach((key, child) -> {
                if ("$ref".equals(key)) {
                    String reference = (String) child;
                    assertThat(reference).startsWith("#/components/");
                    Object target = document;
                    for (String segment : reference.substring(2).split("/")) {
                        target = map(target).get(segment.replace("~1", "/").replace("~0", "~"));
                        assertThat(target).as(reference).isNotNull();
                    }
                } else {
                    checkReferences(child, document);
                }
            });
        } else if (value instanceof List<?> entries) {
            entries.forEach(child -> checkReferences(child, document));
        }
    }

    private static Map<String, Object> document() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(CONTRACT)) {
            return map(new Yaml(new SafeConstructor(options)).load(input));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static String hash(Object value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(CANONICAL.writeValueAsBytes(value)));
    }

    private record Route(String path, String method, String statuses, String digest) {
    }
}
