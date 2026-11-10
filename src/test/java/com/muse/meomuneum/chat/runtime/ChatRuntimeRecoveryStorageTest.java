package com.muse.meomuneum.chat.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ChatRuntimeRecoveryStorageTest {
    @Test
    void actualMissingTableSupportsDatabaseBeforeMusicMigration() {
        assertThatCode(() -> recovery(1146, "42S02")).doesNotThrowAnyException();
    }

    @Test
    void missingGrantsAndConnectivityMustNotSilentlySkipMusicRecovery() {
        assertThatThrownBy(() -> recovery(1142, "42000")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> recovery(0, "08006")).isInstanceOf(DataAccessException.class);
    }

    private ChatRuntimeRecovery recovery(int code, String state) throws SQLException {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(statement.executeQuery("SELECT COUNT(*) FROM chat_music_outbox WHERE 1=0"))
                .thenThrow(new SQLException("synthetic database failure", state, code));
        return new ChatRuntimeRecovery(new JdbcTemplate(source), new DataSourceTransactionManager(source), "test");
    }
}
