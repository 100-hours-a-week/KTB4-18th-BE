package com.muse.meomuneum.musicrecord.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.muse.meomuneum.global.config.MusicRecordSignupLocalFlywayConfig;

class MusicSearchMigrationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MusicSearchMigrationConfiguration.class, MusicRecordSignupLocalFlywayConfig.class);

    @Test
    void delegatesCanonicalMigrationWithoutChangingValidationConfiguration() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.all()).thenReturn(new MigrationInfo[0]);
        new MusicSearchMigrationConfiguration().musicSearchMigrationStrategy().migrate(flyway);
        verify(flyway).migrate();
    }

    @Test
    void defaultProfileOwnsExactlyOneStrategy() {
        context.run(application -> {
            assertThat(application).hasSingleBean(FlywayMigrationStrategy.class);
            assertThat(application).hasBean("musicSearchMigrationStrategy");
        });
    }

    @Test
    void signupProfileRetainsItsSingleIsolatedStrategy() {
        context.withPropertyValues("spring.profiles.active=test,music-record-signup-local",
                "spring.datasource.url=jdbc:mysql://localhost:3306/meomuneum_music_record_signup_test",
                "spring.datasource.username=mm_signup_test").run(application -> {
                    assertThat(application).hasSingleBean(FlywayMigrationStrategy.class);
                    assertThat(application).hasBean("musicRecordSignupLocalMigrationStrategy");
                });
    }
}
