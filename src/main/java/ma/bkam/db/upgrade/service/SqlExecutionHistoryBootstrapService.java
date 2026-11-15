package ma.bkam.db.upgrade.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecutionHistoryBootstrapService {

	private static final String SCHEMA_NAME = "COTMAD";
	private static final String TABLE_NAME = "SQL_EXECUTION_HISTORY";
	private static final String SEQUENCE_NAME = "SQL_EXECUTION_HISTORY_SEQ";

	private final DataSource dataSource;

	public void initialize() {
		try (Connection connection = dataSource.getConnection()) {
			createSequenceIfMissing(connection);
			createTableIfMissing(connection);
			createIndexIfMissing(connection, "IDX_SQL_HIST_SEQ", """
					CREATE INDEX cotmad.idx_sql_hist_seq
					ON cotmad.sql_execution_history (sequence_number)
					""");
			createIndexIfMissing(connection, "IDX_SQL_HIST_FILENAME", """
					CREATE INDEX cotmad.idx_sql_hist_filename
					ON cotmad.sql_execution_history (filename)
					""");
			createIndexIfMissing(connection, "IDX_SQL_HIST_CHECKSUM", """
					CREATE INDEX cotmad.idx_sql_hist_checksum
					ON cotmad.sql_execution_history (checksum_sha256)
					""");

			log.info("SQL execution history technical objects are ready.");

		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to initialize COTMAD.SQL_EXECUTION_HISTORY technical objects.",
					exception);
		}
	}

	private void createSequenceIfMissing(Connection connection) throws SQLException {
		if (sequenceExists(connection, SEQUENCE_NAME)) {
			return;
		}

		execute(connection, """
				CREATE SEQUENCE cotmad.sql_execution_history_seq
				    START WITH 1
				    INCREMENT BY 1
				    NOCACHE
				""");

		log.info("Created sequence COTMAD.SQL_EXECUTION_HISTORY_SEQ.");
	}

	private void createTableIfMissing(Connection connection) throws SQLException {
		if (tableExists(connection, TABLE_NAME)) {
			return;
		}

		execute(connection, """
				CREATE TABLE cotmad.sql_execution_history (
				    id                  NUMBER(19) NOT NULL,
				    filename            VARCHAR2(512 CHAR) NOT NULL,
				    sequence_number     NUMBER(10) NOT NULL,
				    checksum_sha256     VARCHAR2(64 CHAR) NOT NULL,
				    executed_at         TIMESTAMP WITH TIME ZONE NOT NULL,
				    duration_ms         NUMBER(19) NOT NULL,
				    status              VARCHAR2(32 CHAR) NOT NULL,
				    error_code          VARCHAR2(64 CHAR),
				    error_message       CLOB,

				    CONSTRAINT pk_sql_exec_history
				        PRIMARY KEY (id)
				)
				""");

		log.info("Created table COTMAD.SQL_EXECUTION_HISTORY.");
	}

	private void createIndexIfMissing(Connection connection, String indexName, String sql) throws SQLException {
		if (indexExists(connection, indexName)) {
			return;
		}

		execute(connection, sql);

		log.info("Created index COTMAD.{}.", indexName);
	}

	private boolean tableExists(Connection connection, String tableName) throws SQLException {
		String sql = """
				SELECT 1
				FROM all_tables
				WHERE owner = ?
				  AND table_name = ?
				""";

		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, SCHEMA_NAME);
			statement.setString(2, tableName);

			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next();
			}
		}
	}

	private boolean sequenceExists(Connection connection, String sequenceName) throws SQLException {
		String sql = """
				SELECT 1
				FROM all_sequences
				WHERE sequence_owner = ?
				  AND sequence_name = ?
				""";

		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, SCHEMA_NAME);
			statement.setString(2, sequenceName);

			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next();
			}
		}
	}

	private boolean indexExists(Connection connection, String indexName) throws SQLException {
		String sql = """
				SELECT 1
				FROM all_indexes
				WHERE owner = ?
				  AND index_name = ?
				""";

		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, SCHEMA_NAME);
			statement.setString(2, indexName);

			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next();
			}
		}
	}

	private void execute(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}