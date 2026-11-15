package com.muse.coreauth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Test-only certificates and per-JVM DNS; neither system trust nor the OS hosts file is modified. */
final class ProductionTlsFixture {
    static final String CORE_HOST = "core.chat.example.test";
    static final String PASSWORD = "synthetic-fixture-password";
    private final Path directory;

    ProductionTlsFixture() {
        try {
            directory = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "chat-prod-tls-");
            Files.writeString(hosts(), "127.0.0.1 localhost " + CORE_HOST + "\n::1 localhost\n");
            System.setProperty("jdk.net.hosts.file", hosts().toString());
            generate("core", "dns:" + CORE_HOST);
            generate("redis", "dns:localhost,ip:127.0.0.1");
            KeyStore redis = load(redisStore());
            writePem("redis.crt", "CERTIFICATE", redis.getCertificate("fixture").getEncoded());
            writePem("redis.key", "PRIVATE KEY", redis.getKey("fixture", PASSWORD.toCharArray()).getEncoded());
        } catch (Exception failure) {
            throw new IllegalStateException("Production TLS fixture setup failed", failure);
        }
    }

    Path hosts() {
        return directory.resolve("hosts");
    }

    Path coreStore() {
        return directory.resolve("core.p12");
    }

    Path redisStore() {
        return directory.resolve("redis.p12");
    }

    Path certificate() {
        return directory.resolve("redis.crt");
    }

    Path privateKey() {
        return directory.resolve("redis.key");
    }

    SSLContext clientContext() throws Exception {
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(load(coreStore()));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private KeyStore load(Path path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) {
            store.load(input, PASSWORD.toCharArray());
        }
        return store;
    }

    private void generate(String name, String hosts) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=" + name, "-ext", "SAN=" + hosts, "-ext", "bc=ca:true", "-storetype", "PKCS12",
                "-keystore", directory.resolve(name + ".p12").toString(), "-storepass", PASSWORD, "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve(name + "-keytool.log").toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("Certificate generation timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Certificate generation failed");
        }
    }

    private void writePem(String name, String type, byte[] bytes) throws Exception {
        Files.writeString(directory.resolve(name), "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n", StandardCharsets.US_ASCII);
    }
}
