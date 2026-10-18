package com.muse.meomuneum.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.util.Throwables.getStackTrace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.muse.meomuneum.auth.config.AuthStateProperties;

@ExtendWith(OutputCaptureExtension.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TestDotenvEnvironmentPostProcessorTest {

    private static final String SENTINEL = "dotenv-secret-sentinel";
    private static final String HMAC_KEY = "fixture-only-hmac-key-with-at-least-32-bytes";

    @TempDir
    private Path temporaryDirectory;

    private String originalDirectory;

    @BeforeEach
    void useTemporaryWorkingDirectory() {
        originalDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", temporaryDirectory.toString());
    }

    @AfterEach
    void restoreWorkingDirectory() {
        System.setProperty("user.dir", originalDirectory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void discoversProcessorDuringRealBootAndBindsExactQuotedSecrets(String profile) throws Exception {
        write(".env", """
                DEV_DB_PASSWORD='  dev password#=with spaces  '
                TEST_DB_PASSWORD='  password#=with spaces  '
                %s="%s"
                AUTH_STATE_HMAC_KEY='%s'
                SERVER_PORT=18091
                """.formatted(profile.equals("dev") ? "AUTH_JWT_SECRET" : "TEST_AUTH_JWT_SECRET",
                profile.equals("dev")
                        ? "fixture-only-dev-jwt-secret#=with spaces"
                        : "fixture-only-jwt-secret#=with spaces",
                HMAC_KEY));

        try (var context = start(profile)) {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.datasource.password"))
                    .isEqualTo(profile.equals("dev") ? "  dev password#=with spaces  " : "  password#=with spaces  ");
            assertThat(Binder.get(environment).bind("auth.jwt.secret", String.class).get())
                    .isEqualTo(profile.equals("dev")
                            ? "fixture-only-dev-jwt-secret#=with spaces"
                            : "fixture-only-jwt-secret#=with spaces");
            assertThat(Binder.get(environment).bind("auth.state", AuthStateProperties.class).get().hmacKey())
                    .isEqualTo(HMAC_KEY);
            assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(18091);
            assertThat(environment.getPropertySources().contains("testDotenv")).isTrue();
        }
        assertThat(new TestDotenvEnvironmentPostProcessor().getOrder())
                .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void loadsAllSuffixesInStringOrderAndKeepsLastDeclarations(String profile) throws Exception {
        write(".env.z", "COLLISION=z\n");
        write(".env.2", "NUMERIC_SUFFIX_COLLISION=two\n");
        write(".env.foo", "FOO=yes\nDUPLICATE=first\nDUPLICATE=last\n");
        write(".env.prod", "PROD_SUFFIX=yes\nSPRING_PROFILES_ACTIVE=prod\n");
        write(".env.10", "NUMERIC_SUFFIX_COLLISION=ten\n");
        write(".env.a", "A_SUFFIX=yes\nCOLLISION=a\n");
        write(".env", "COLLISION=base\nBASE=yes\n");

        try (var context = start(profile)) {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("BASE")).isEqualTo("yes");
            assertThat(environment.getProperty("FOO")).isEqualTo("yes");
            assertThat(environment.getProperty("A_SUFFIX")).isEqualTo("yes");
            assertThat(environment.getProperty("PROD_SUFFIX")).isEqualTo("yes");
            assertThat(environment.getProperty("COLLISION")).isEqualTo("z");
            assertThat(environment.getProperty("NUMERIC_SUFFIX_COLLISION")).isEqualTo("two");
            assertThat(environment.getProperty("DUPLICATE")).isEqualTo("last");
            assertThat(environment.getActiveProfiles()).containsExactly(profile);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void supportsSingleLineValuesCommentsAndLiteralCommandText(String profile) throws Exception {
        Path marker = temporaryDirectory.resolve("must-not-exist");
        write(".env", """
                # comment

                SINGLE='  p#=word  ' # trailing comment
                DOUBLE="  p#=word  " # trailing comment
                PLAIN=plain value # trailing comment
                EMPTY=
                SINGLE_LAYER='"quoted"'
                COMMAND=$(touch %s)
                """.formatted(marker));

        var environment = process(profile, Map.of(), Map.of());
        assertThat(environment.getProperty("SINGLE")).isEqualTo("  p#=word  ");
        assertThat(environment.getProperty("DOUBLE")).isEqualTo("  p#=word  ");
        assertThat(environment.getProperty("PLAIN")).isEqualTo("plain value");
        assertThat(environment.getProperty("EMPTY")).isEmpty();
        assertThat(environment.getProperty("SINGLE_LAYER")).isEqualTo("\"quoted\"");
        assertThat(environment.getProperty("COMMAND")).isEqualTo("$(touch " + marker + ")");
        assertThat(marker).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void keepsCommandLineSystemAndOperatingSystemValuesAheadOfDotenv(String profile) throws Exception {
        String databaseKey = profile.equals("dev") ? "DEV_DB_PASSWORD" : "TEST_DB_PASSWORD";
        write(".env", databaseKey + "=dotenv\nSERVER_PORT=18092\n");
        Path legacy = write("legacy.properties", databaseKey + "=legacy\n");
        for (Map<String, Object> operatingSystem : java.util.List.<Map<String, Object>>of(
                Map.of(), Map.of(databaseKey, "os"))) {
            try (var context = start(profile, legacy, operatingSystem, Map.of())) {
                assertThat(context.getEnvironment().getProperty("spring.datasource.password"))
                        .isEqualTo(operatingSystem.isEmpty() ? "dotenv" : "os");
            }
        }
        try (var context = start(profile, legacy, Map.of(databaseKey, "os"),
                Map.of(databaseKey, "jvm"))) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.password")).isEqualTo("jvm");
        }
        try (var context = start(profile, legacy, Map.of(databaseKey, "os"),
                Map.of(databaseKey, "jvm"), "--" + databaseKey + "=cli", "--server.port=18093")) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.password")).isEqualTo("cli");
            assertThat(context.getEnvironment().getProperty("server.port", Integer.class)).isEqualTo(18093);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void usesOnlyDeclaredFileEntriesAndDoesNotMutateGlobalProperties(String profile) throws Exception {
        write(".env", "UNIQUE_DOTENV_PROBE=fixture\n");
        Properties before = new Properties();
        before.putAll(System.getProperties());

        var environment = process(profile, Map.of(), Map.of());
        var source = environment.getPropertySources().get("testDotenv");
        assertThat(source).isInstanceOf(SystemEnvironmentPropertySource.class);
        assertThat(((SystemEnvironmentPropertySource) source).getSource())
                .containsExactlyEntriesOf(Map.of("UNIQUE_DOTENV_PROBE", "fixture"));
        assertThat(System.getProperties()).containsExactlyInAnyOrderEntriesOf(before);
        new TestDotenvEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getPropertySources().stream().filter(item -> item.getName().equals("testDotenv")))
                .hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void preservesSelectedJavaPropertiesAndSuppliesMissingFallbackValues(String profile) throws Exception {
        String databaseKey = profile.equals("dev") ? "DEV_DB_PASSWORD" : "TEST_DB_PASSWORD";
        Path legacy = write(".env.custom.properties", """
                %s=legacy\\u0020password
                LEGACY_ONLY=legacy\\u0020value
                """.formatted(databaseKey));
        write(".env.local.properties", "unsupported dotenv legacy text\n");
        try (var context = start(profile, legacy, Map.of(), Map.of())) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.password")).isEqualTo("legacy password");
            assertThat(context.getEnvironment().getProperty("LEGACY_ONLY")).isEqualTo("legacy value");
        }
        write(".env", databaseKey + "=dotenv\n");
        try (var context = start(profile, legacy, Map.of(), Map.of())) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.password")).isEqualTo("dotenv");
            assertThat(context.getEnvironment().getProperty("LEGACY_ONLY")).isEqualTo("legacy value");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void acceptsEmptyFilesMissingFilesAndExternalOnlyConfiguration(String profile) throws Exception {
        try (var context = start(profile)) {
            assertThat(context.getEnvironment().getPropertySources().contains("testDotenv")).isFalse();
        }
        write(".env", "");
        write(".env.foo", "");
        try (var context = start(profile, missingLegacy(),
                Map.of(profile.equals("dev") ? "DEV_DB_PASSWORD" : "TEST_DB_PASSWORD", "external"), Map.of())) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.password")).isEqualTo("external");
            assertThat(context.getEnvironment().getPropertySources().contains("testDotenv")).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "other", "prod", "dev,prod", "prod,dev", "test,prod", "prod,test",
            "dev,test,prod", "dev,prod,test", "prod,dev,test", "test,dev,prod", "test,prod,dev", "prod,test,dev"})
    void neverReadsMalformedDotenvOutsideLocalProfilesWithoutProduction(String profiles) throws Exception {
        write(".env", "invalid " + SENTINEL);
        try (var context = start(profiles)) {
            assertThat(context.getEnvironment().getPropertySources().contains("testDotenv")).isFalse();
            assertThat(context.getEnvironment().getProperty("UNIQUE_DOTENV_PROBE")).isNull();
        }
        System.setProperty("user.dir", temporaryDirectory.resolve("not-a-directory").toString());
        assertThat(catchThrowable(() -> process(profiles, Map.of(), Map.of()))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad key=", "export KEY=", "KEY='", "KEY=\"", "KEY='first\nsecond", "KEY=\"first\nsecond",
            "KEY='closed' extra", "KEY=\"closed\" extra"})
    void rejectsMalformedInputWithoutDisclosingValuesOrVendorCauses(String prefix, CapturedOutput output)
            throws Exception {
        write(".env", prefix + SENTINEL);
        for (String profile : new String[]{"dev", "test"}) {
            Throwable error = catchThrowable(() -> start(profile));

            assertThat(error).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot load local dotenv file .env").hasNoCause().hasNoSuppressedExceptions();
            assertThat(getStackTrace(error)).doesNotContain(SENTINEL, temporaryDirectory.toString());
            assertThat(output.getAll()).doesNotContain(SENTINEL, temporaryDirectory.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void excludesTemplatesDirectoriesAndEverySymlink(String profile, @TempDir Path externalDirectory) throws Exception {
        for (String name : new String[]{".env.example", ".env.foo.example", ".env.foo.sample", ".env.foo.template"}) {
            write(name, "invalid " + SENTINEL);
        }
        Files.createDirectory(temporaryDirectory.resolve(".env"));
        Files.createDirectory(temporaryDirectory.resolve(".env.folder"));
        Path internal = write("internal-fixture", "invalid " + SENTINEL);
        Path external = Files.writeString(externalDirectory.resolve("external-fixture"), "invalid " + SENTINEL);
        Files.createSymbolicLink(temporaryDirectory.resolve(".env.internal"), internal);
        Files.createSymbolicLink(temporaryDirectory.resolve(".env.external"), external);
        Files.createSymbolicLink(temporaryDirectory.resolve(".env.directory"), externalDirectory);
        Files.writeString(temporaryDirectory.resolve(".env.folder/.env"), "invalid nested " + SENTINEL);
        write(".env.real", "ONLY_REAL=yes\n");

        try (var context = start(profile)) {
            assertThat(context.getEnvironment().getProperty("ONLY_REAL")).isEqualTo("yes");
            assertThat(context.getEnvironment().getProperty("UNIQUE_DOTENV_PROBE")).isNull();
        }
        assertThat(Files.readString(external)).isEqualTo("invalid " + SENTINEL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void reportsDiscoveryFailureWithoutPathsOrAttachedExceptions(String profile) throws Exception {
        Path file = write("regular-file", SENTINEL);
        System.setProperty("user.dir", file.toString());
        Throwable error = catchThrowable(() -> process(profile, Map.of(), Map.of()));
        assertThat(error).isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot load local dotenv working directory").hasNoCause().hasNoSuppressedExceptions();
        assertThat(getStackTrace(error)).doesNotContain(SENTINEL, temporaryDirectory.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void retainsPrecedenceWhenSystemEnvironmentSourceIsAbsent(String profile) throws Exception {
        write(".env", "PROBE=dotenv\nSERVER_PORT=18094\n");
        var environment = environment(Map.of(), Map.of("PROBE", "jvm"), missingLegacy());
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.setActiveProfiles(profile);
        environment.getPropertySources()
                .addLast(new MapPropertySource("Config resource fixture", Map.of("PROBE", "yaml")));
        new TestDotenvEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("PROBE")).isEqualTo("jvm");
        assertThat(environment.getProperty("server.port")).isEqualTo("18094");

        var withoutSystemSources = environment(Map.of(), Map.of(), missingLegacy());
        withoutSystemSources.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        withoutSystemSources.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        withoutSystemSources.setActiveProfiles(profile);
        withoutSystemSources.getPropertySources().addLast(new MapPropertySource("Config resource fixture",
                Map.of("PROBE", "yaml")));
        new TestDotenvEnvironmentPostProcessor().postProcessEnvironment(withoutSystemSources, new SpringApplication());
        assertThat(withoutSystemSources.getProperty("PROBE")).isEqualTo("dotenv");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev,test", "test,dev"})
    void loadsDotenvForBothLocalProfileOrdersWithoutChangingSelectedProfiles(String profiles) throws Exception {
        write(".env", "LOCAL_COMBINATION=loaded\nSPRING_PROFILES_ACTIVE=prod\n");
        try (var context = start(profiles)) {
            assertThat(context.getEnvironment().getProperty("LOCAL_COMBINATION")).isEqualTo("loaded");
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly(profiles.split(","));
        }
    }

    private Path write(String filename, String content) throws Exception {
        return Files.writeString(temporaryDirectory.resolve(filename), content);
    }

    private Path missingLegacy() {
        return temporaryDirectory.resolve("missing-local.properties");
    }

    private StandardEnvironment process(String profiles, Map<String, Object> operatingSystem,
            Map<String, Object> systemProperties) {
        var environment = environment(operatingSystem, systemProperties, missingLegacy());
        if (!profiles.isEmpty()) {
            environment.setActiveProfiles(profiles.split(","));
        }
        new TestDotenvEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    private ConfigurableApplicationContext start(String profiles) {
        return start(profiles, missingLegacy(), Map.of(), Map.of());
    }

    private ConfigurableApplicationContext start(String profiles, Path legacy, Map<String, Object> operatingSystem,
            Map<String, Object> systemProperties, String... arguments) {
        var application = new SpringApplication(MinimalConfiguration.class);
        application.setEnvironment(environment(operatingSystem, systemProperties, legacy));
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        String[] options = new String[arguments.length + 1];
        options[0] = "--spring.profiles.active=" + profiles;
        System.arraycopy(arguments, 0, options, 1, arguments.length);
        return application.run(options);
    }

    private StandardEnvironment environment(Map<String, Object> operatingSystem, Map<String, Object> systemProperties,
            Path legacy) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        operatingSystem));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));
        Map<String, Object> fixtures = new HashMap<>();
        fixtures.put("spring.config.location", "classpath:/");
        fixtures.put("LOCAL_ENV_PROPERTIES", legacy.toAbsolutePath().toString());
        environment.getPropertySources().addLast(new MapPropertySource("fixture-settings", fixtures));
        return environment;
    }

    @Configuration(proxyBeanMethods = false)
    static class MinimalConfiguration {
    }
}
