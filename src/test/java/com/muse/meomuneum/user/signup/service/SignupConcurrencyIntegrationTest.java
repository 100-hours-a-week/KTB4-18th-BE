package com.muse.meomuneum.user.signup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.muse.meomuneum.user.signup.dto.SignupRequest;
import com.muse.meomuneum.user.signup.exception.DuplicateEmailException;
import com.muse.meomuneum.user.signup.repository.SignupRepository;

@SpringBootTest(properties = "auth.jwt.secret=development-only-secret-with-at-least-32-bytes")
@ActiveProfiles("test")
class SignupConcurrencyIntegrationTest {
    private static final String TEST_EMAIL_PREFIX = "signup-concurrency-";
    private static final long AWAIT_TIMEOUT_SECONDS = 5;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbc.update("""
                DELETE agreements
                FROM terms_agreements agreements
                INNER JOIN users ON users.id = agreements.user_id
                WHERE users.email LIKE ?
                """, TEST_EMAIL_PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE email LIKE ?", TEST_EMAIL_PREFIX + "%");
    }

    @Test
    void returnsDuplicateEmailExceptionAndPersistsOneUserForConcurrentSignup() throws Exception {
        String email = TEST_EMAIL_PREFIX + Long.toHexString(UUID.randomUUID().getMostSignificantBits()) + "@e.co";
        List<Long> requiredTermsIds = findCurrentRequiredTermsIds();
        assertFalse(requiredTermsIds.isEmpty());
        CountDownLatch emailLookupComplete = new CountDownLatch(2);
        CountDownLatch createUserStart = new CountDownLatch(1);
        SignupService signupService = new SignupService(
                new CoordinatingSignupRepository(jdbc, emailLookupComplete, createUserStart),
                passwordEncoder,
                Clock.systemUTC());

        Future<SignupAttempt> firstAttempt = executor.submit(() -> signup(signupService, email, requiredTermsIds));
        Future<SignupAttempt> secondAttempt = executor.submit(() -> signup(signupService, email, requiredTermsIds));
        try {
            assertTrue(emailLookupComplete.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            createUserStart.countDown();

            List<SignupAttempt> attempts = List.of(
                    firstAttempt.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondAttempt.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            List<RuntimeException> failures = attempts.stream()
                    .map(SignupAttempt::exception)
                    .filter(exception -> exception != null)
                    .toList();

            assertEquals(1, attempts.stream().filter(SignupAttempt::isSuccess).count());
            assertEquals(1, failures.size());
            assertInstanceOf(DuplicateEmailException.class, failures.getFirst());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email));
            assertEquals(requiredTermsIds.size(), jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM terms_agreements agreements
                    INNER JOIN users ON users.id = agreements.user_id
                    WHERE users.email = ?
                    """, Integer.class, email));
        } finally {
            createUserStart.countDown();
        }
    }

    private SignupAttempt signup(SignupService signupService, String email, List<Long> requiredTermsIds) {
        try {
            signupService.signup(new SignupRequest(
                    email, "password1!", "머문음", null, null, requiredTermsIds));
            return SignupAttempt.success();
        } catch (RuntimeException exception) {
            return SignupAttempt.failure(exception);
        }
    }

    private List<Long> findCurrentRequiredTermsIds() {
        return jdbc.queryForList("""
                SELECT t.id
                FROM terms t
                WHERE t.type IN ('SERVICE', 'PROFILE', 'AIPERSONAL', 'LOCATIONTERMS', 'LOCATION')
                    AND t.is_required = TRUE
                    AND t.effective_at <= CURRENT_TIMESTAMP(6)
                    AND t.effective_at = (
                        SELECT MAX(current_terms.effective_at)
                        FROM terms current_terms
                        WHERE current_terms.type = t.type
                            AND current_terms.effective_at <= CURRENT_TIMESTAMP(6)
                    )
                """, Long.class);
    }

    private record SignupAttempt(RuntimeException exception) {
        static SignupAttempt success() {
            return new SignupAttempt(null);
        }

        static SignupAttempt failure(RuntimeException exception) {
            return new SignupAttempt(exception);
        }

        boolean isSuccess() {
            return exception == null;
        }
    }

    private static final class CoordinatingSignupRepository extends SignupRepository {
        private final CountDownLatch emailLookupComplete;
        private final CountDownLatch createUserStart;

        private CoordinatingSignupRepository(
                JdbcTemplate jdbc, CountDownLatch emailLookupComplete, CountDownLatch createUserStart) {
            super(jdbc);
            this.emailLookupComplete = emailLookupComplete;
            this.createUserStart = createUserStart;
        }

        @Override
        public boolean existsUserByEmail(String email) {
            emailLookupComplete.countDown();
            try {
                if (!createUserStart.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent signup test timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent signup test was interrupted", exception);
            }
            return false;
        }
    }
}
