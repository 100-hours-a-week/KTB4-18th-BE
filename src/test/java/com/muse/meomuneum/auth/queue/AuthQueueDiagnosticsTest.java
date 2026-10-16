package com.muse.meomuneum.auth.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class AuthQueueDiagnosticsTest {

    @Test
    void stageLogCorrelatesWithoutWritingRawIdentifiersOrExceptionMessages() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthQueueDiagnostics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var diagnostics = new AuthQueueDiagnostics(true, "auth-test-run", "a".repeat(32));
            diagnostics.stageForTicket("CREDENTIALS_VERIFY", "raw-ticket-id", "ERROR", "OTHER", 17,
                    new RuntimeException("person@example.com secret-password"));
            diagnostics.stageForCorrelation("OPERATION_ID_VALIDATION", "0123456789abcdef0123456789abcdef",
                    "SUCCESS", "NONE", 2, null);

            assertThat(appender.list).hasSize(2);
            String ticketEvent = appender.list.get(0).getFormattedMessage();
            assertThat(ticketEvent).contains("stage=CREDENTIALS_VERIFY", "outcome=ERROR", "duration_ms=17",
                    "causes=RuntimeException");
            assertThat(ticketEvent).containsPattern("corr_id=[0-9a-f]{32}");
            assertThat(ticketEvent).doesNotContain("raw-ticket-id", "person@example.com", "secret-password");
            assertThat(appender.list.get(1).getFormattedMessage())
                    .contains("corr_id=0123456789abcdef0123456789abcdef");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void disabledDiagnosticsDoNotLogEvenWithAValidCorrelationId() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthQueueDiagnostics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var diagnostics = new AuthQueueDiagnostics(false, "auth-test-run", "a".repeat(32));
            diagnostics.stageForCorrelation("REQUEST_RECEIVED", "0123456789abcdef0123456789abcdef",
                    "SUCCESS", "NONE", 1, null);

            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
