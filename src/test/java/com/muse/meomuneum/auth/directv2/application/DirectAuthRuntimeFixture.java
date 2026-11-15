package com.muse.meomuneum.auth.directv2.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.KDF;
import javax.crypto.spec.HKDFParameterSpec;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthBootstrapRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthFlowRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthLoginLifecycleRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthNonceAllocator;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthOperationRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthReceiptRepository;
import com.muse.meomuneum.auth.directv2.repository.DirectAuthSessionRepository;
import com.muse.meomuneum.auth.directv2.security.DirectAuthCookieService;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.service.UserAuthenticationService;

public class DirectAuthRuntimeFixture implements AutoCloseable {
    public final JdbcTemplate jdbc;
    public final org.springframework.mock.env.MockEnvironment safetyEnvironment;
    public final com.muse.meomuneum.auth.directv2.security.DirectAuthNonceAdmissionGuard nonceAdmission;
    public final DirectAuthNonceAllocator allocator;
    public final RefreshReplayCipher cipher;
    public final DirectAuthTransactionRunner transactions;
    public final DirectAuthFlowRepository flows;
    public final DirectAuthOperationRepository operations;
    public final DirectAuthLoginLifecycleRepository lifecycle;
    public final DirectAuthProjectionRepository projections;
    public final DirectAuthSessionRepository sessions;
    public final DirectAuthCookieService cookies;
    public final DirectAuthFlowService flowService;
    public final DirectAuthLoginService login;
    public final DirectAuthLogoutService logout;
    public final DirectAuthRecoveryService recovery;
    public final DirectAuthRefreshService refresh;
    public final DirectAuthRecoveryLifecycle sourceLifecycle;
    public final DirectAuthRebootstrapService rebootstrap;
    public final DirectAuthRefreshRecoveryService sourceRecovery;
    public final DirectAuthRefreshRecoveryReadService sourceReads;
    public final com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository recoveryRows;
    public final JwtTokenProvider tokens;
    public final AuthStateProperties properties;
    public final com.muse.meomuneum.auth.application.AuthFlowService authority;
    private final JdbcTemplate server;
    private final String schema;

    public DirectAuthRuntimeFixture(UserAuthenticationService users) throws Exception {
        this(users, new com.muse.meomuneum.auth.directv2.security.DirectAuthNonceAdmissionGuard());
    }

    public DirectAuthRuntimeFixture(UserAuthenticationService users,
            com.muse.meomuneum.auth.directv2.security.DirectAuthNonceAdmissionGuard admission) throws Exception {
        String endpoint = System.getenv().getOrDefault("AUTH_TEST_MYSQL_ENDPOINT", "127.0.0.1:13319");
        String password = System.getenv("AUTH_TEST_MYSQL_PASSWORD");
        if (password == null) {
            throw new IllegalStateException("AUTH_TEST_MYSQL_PASSWORD is required; MySQL tests never skip");
        }
        String url = "jdbc:mysql://" + endpoint + "/?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC";
        server = new JdbcTemplate(new DriverManagerDataSource(url, "root", password));
        schema = "direct_auth_test_" + UUID.randomUUID().toString().replace("-", "");
        server.execute("CREATE DATABASE " + schema);
        var dataSource = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), "root", password);
        jdbc = new JdbcTemplate(dataSource);
        var populator = new ResourceDatabasePopulator();
        for (String migration : new String[]{"V20261024142649__create_auth_browser_flow_authority.sql",
                "V20261031002221__add_direct_auth_operations.sql",
                "V20261031150133__add_direct_auth_recovery_constraints.sql",
                "V20261102175650__add_direct_auth_login_runtime.sql",
                "V20261102181711__add_direct_auth_projection_metadata.sql"}) {
            populator.addScript(new FileSystemResource("src/main/resources/db/migration/" + migration));
        }
        populator.execute(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        transactions = new DirectAuthTransactionRunner(manager);
        flows = new DirectAuthFlowRepository(jdbc);
        operations = new DirectAuthOperationRepository(jdbc, flows);
        lifecycle = new DirectAuthLoginLifecycleRepository(jdbc, operations);
        safetyEnvironment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("auth.direct-v2.safety-mode", "LOCAL")
                .withProperty("auth.direct-v2.backend-instances", "1");
        projections = new DirectAuthProjectionRepository(jdbc,
                new com.muse.meomuneum.auth.directv2.security.DirectAuthRestoreSafety(safetyEnvironment));
        sessions = new DirectAuthSessionRepository(jdbc);
        String stateKey = "test-only-state-key-" + UUID.randomUUID();
        byte[] encryption = new byte[32];
        new java.security.SecureRandom().nextBytes(encryption);
        properties = new AuthStateProperties(stateKey, Base64.getEncoder().encodeToString(encryption), 1209600, 7776000,
                10, 64, 16, 86400, 1, 100000, 300, 45);
        cookies = new DirectAuthCookieService(properties, false, "Lax");
        byte[] derived = KDF.getInstance("HKDF-SHA256").deriveData(HKDFParameterSpec.ofExtract().addIKM(encryption)
                .addSalt("meomuneum/auth-replay/v2/hkdf-salt".getBytes(StandardCharsets.UTF_8))
                .thenExpand("meomuneum/auth-replay/v2/aes-256-gcm".getBytes(StandardCharsets.UTF_8), 32));
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update("meomuneum/auth-replay/v2/key-id".getBytes(StandardCharsets.UTF_8));
        String keyId = HexFormat.of().formatHex(digest.digest(derived));
        jdbc.update("INSERT INTO auth_replay_key_pin VALUES (1, ?)", keyId);
        jdbc.update("INSERT INTO auth_replay_iv_allocators VALUES (?, ?, FALSE)", keyId, new byte[12]);
        jdbc.update("""
                UPDATE auth_direct_projection_control SET cookie_mode = 'GENERATION_SCOPED',
                readiness = 'LOCAL_READY', replay_key_id = ?, restore_verified = TRUE WHERE singleton_id = 1
                """, keyId);
        nonceAdmission = admission;
        allocator = new DirectAuthNonceAllocator(jdbc, manager, projections, nonceAdmission);
        cipher = new RefreshReplayCipher(properties, allocator);
        var jwt = new JwtProperties("test", "test", "test-only-jwt-key-" + UUID.randomUUID(), 300, 3600);
        tokens = new JwtTokenProvider(jwt);
        authority = new com.muse.meomuneum.auth.application.AuthFlowService(jdbc, manager, properties,
                false, "Lax", false, 120, jwt, projections);
        flowService = new DirectAuthFlowService(transactions, flows, new DirectAuthBootstrapRepository(jdbc),
                projections, sessions, cookies, cipher, new ObjectMapper(), jdbc, properties);
        login = new DirectAuthLoginService(transactions, flows, operations, lifecycle, projections, sessions,
                cookies, users, tokens, cipher, properties, jwt);
        var nonceProofs = new com.muse.meomuneum.auth.directv2.security.DirectAuthNonceProofService(flows,
                new DirectAuthReceiptRepository(jdbc),
                new com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository(jdbc), sessions, cookies,
                new com.muse.meomuneum.auth.directv2.security.DirectAuthRefreshProofService(cookies, tokens));
        logout = new DirectAuthLogoutService(transactions, flows, new DirectAuthReceiptRepository(jdbc), operations,
                projections, sessions, cookies, login, cipher, new ObjectMapper(), properties, nonceProofs);
        var refreshReceipts = new com.muse.meomuneum.auth.directv2.repository.DirectAuthRefreshRepository(jdbc);
        refresh = new DirectAuthRefreshService(transactions, flows, sessions, refreshReceipts, projections, cookies,
                new com.muse.meomuneum.auth.directv2.security.DirectAuthRefreshProofService(cookies, tokens),
                login, users, tokens, cipher, new ObjectMapper(), properties, jwt, true, nonceProofs);
        recoveryRows = new com.muse.meomuneum.auth.directv2.repository.DirectAuthRecoveryRepository(jdbc);
        sourceLifecycle = new DirectAuthRecoveryLifecycle(flows, refreshReceipts, sessions, cookies, refresh,
                recoveryRows,
                new com.muse.meomuneum.auth.directv2.security.DirectAuthRefreshProofService(cookies, tokens),
                flowService);
        sourceRecovery = new DirectAuthRefreshRecoveryService(transactions, projections, sourceLifecycle, recoveryRows,
                flows, cookies, login, tokens, users, cipher, new ObjectMapper(), jwt, true);
        sourceReads = new DirectAuthRefreshRecoveryReadService(transactions, sourceLifecycle, recoveryRows);
        var reboots = new com.muse.meomuneum.auth.directv2.repository.DirectAuthRebootstrapRepository(jdbc);
        rebootstrap = new DirectAuthRebootstrapService(transactions, flows, operations, lifecycle, reboots,
                projections, sessions, cookies, login, cipher, properties, flowService, true);
        recovery = new DirectAuthRecoveryService(transactions, flows, operations, sessions, projections,
                flowService, login, cookies, users, tokens, cipher, new DirectAuthReceiptRepository(jdbc),
                refreshReceipts, refresh, recoveryRows, sourceLifecycle, reboots);
    }

    @Override
    public void close() {
        server.execute("DROP DATABASE " + schema);
    }
}
