package com.muse.meomuneum.user.account.repository;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class UserNicknameUniqueConstraintIntegrationTest {
    private static final String TEST_EMAIL_PREFIX = "nickname-unique-";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("meomuneum_nickname_test")
            .withUsername("meomuneum_test")
            .withPassword("meomuneum_test");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    @AfterEach
    void cleanUpTestUsers() {
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", TEST_EMAIL_PREFIX + "%");
    }

    @Test
    void rejectsTheSameNicknameForAnotherUser() {
        jdbcTemplate.update("INSERT INTO users (email, password_hash, nickname) VALUES (?, ?, ?)",
                TEST_EMAIL_PREFIX + "first@example.com", "hash", "중복검증닉네임");

        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, ?, ?)",
                TEST_EMAIL_PREFIX + "second@example.com", "hash", "중복검증닉네임"));
    }
}
