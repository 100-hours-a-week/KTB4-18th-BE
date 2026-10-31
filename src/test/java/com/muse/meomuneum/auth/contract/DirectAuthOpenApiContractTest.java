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
            new Route("/token/refresh", "post", "200,400,401,403,409,428,503",
                    "e08788af78f1cd1b3d621ea28318018094168bedada4aeab02f2f9fdc650ae9c"),
            new Route("/logout", "post", "200,400,401,403,409,428,503",
                    "19c942a2d9afd61a93ea29cfb3940679027c7abc06331ebd4baa498c1fc2181d"),
            new Route("/token/csrf", "get", "200", "47dc02ab3e0ef9e5e8a9f59ea994e39c6608cf2f356139111b977bf239169068"),
            new Route("/flow/current-operation", "get", "200,400,404,409,503",
                    "afafbd6876e0f8299434701ebbb7682593d11064d7ec96930e46a39eb0bff661"),
            new Route("/flow/rebootstrap", "post", "200,400,403,404,409,429,503",
                    "8d8452fbf7d7ad891c664df3ef0b6fefba2dfc48abd4856076db4a78b2059f6b"),
            new Route("/token/refresh/operations/{operationId}/session-recovery", "post",
                    "200,202,400,403,404,409,429,503",
                    "6a6e0a49b243710d8aee18c74056cf718a6bc7fe82605096af77255d22933cc4"),
            new Route("/token/refresh/operations/{operationId}/session-recovery/{recoveryOperationId}", "get",
                    "200,400,403,404,409,503", "c95a030e7f3b420f019804fabb4d1fc7f21d7434c5a7ea7e6b620505eceabe43"),
            new Route("/token/refresh/operations/{operationId}/session-recovery/current", "get",
                    "200,400,403,404,409,503", "552af7ff38c52dc383bae32a26cfa75f31294a69f3634bf09051663d37eee970"),
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
                .isEqualTo("3bae68ccf1e90519499ac7d112dc3a6115fa12a5df332cad36eb55eb60896713");
        assertThat(hash(components.get("securitySchemes"))).as("security schemes")
                .isEqualTo("788d536415e602398a62e3d9af82426dbc0c2da08e80856c4832ccd6e141ffd6");
        checkReferences(document, document);
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
