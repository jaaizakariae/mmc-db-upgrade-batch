
package ma.bkam.db.upgrade.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SqlExecutionHistoryBootstrapService {

	private static final String TABLE_NAME = "SQL_EXECUTION_HISTORY";
	private static final String SEQUENCE_NAME = "SQL_EXECUTION_HISTORY_SEQ";

	private static final String INDEX_SEQ = "IDX_SQL_HIST_SEQ";
	private static final String INDEX_FILENAME = "IDX_SQL_HIST_FILENAME";
	private static final String INDEX_CHECKSUM = "IDX_SQL_HIST_CHECKSUM";

	private static final Pattern ORACLE_IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]*");

	private final DataSource dataSource;
	private final String schemaName;

	public SqlExecutionHistoryBootstrapService(DataSource dataSource,
			@Value("${spring.jpa.properties.hibernate.default_schema}") String schemaName) {

		this.dataSource = dataSource;
		this.schemaName = validateSchemaName(schemaName);
	}

	public void initialize() {

		try (Connection connection = dataSource.getConnection()) {

			createSequenceIfMissing(connection);
			createTableIfMissing(connection);

			createIndexIfMissing(connection, INDEX_SEQ, "sequence_number");

			createIndexIfMissing(connection, INDEX_FILENAME, "filename");

			createIndexIfMissing(connection, INDEX_CHECKSUM, "checksum_sha256");

			log.info("SQL execution history technical objects are ready for schema {}.", schemaName);

		} catch (SQLException exception) {

			throw new IllegalStateException("Unable to initialize " + qualifiedName(TABLE_NAME) + " technical objects.",
					exception);
		}
	}

	private void createSequenceIfMissing(Connection connection) throws SQLException {

		if (sequenceExists(connection, SEQUENCE_NAME)) {
			return;
		}

		String sql = """
				CREATE SEQUENCE %s
				    START WITH 1
				    INCREMENT BY 1
				    NOCACHE
				""".formatted(qualifiedName(SEQUENCE_NAME));

		execute(connection, sql);

		log.info("Created sequence {}.", qualifiedName(SEQUENCE_NAME));
	}

	private void createTableIfMissing(Connection connection) throws SQLException {

		if (tableExists(connection, TABLE_NAME)) {
			return;
		}

		String sql = """
				CREATE TABLE %s (
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
				""".formatted(qualifiedName(TABLE_NAME));

		execute(connection, sql);

		log.info("Created table {}.", qualifiedName(TABLE_NAME));
	}

	private void createIndexIfMissing(Connection connection, String indexName, String columnName) throws SQLException {

		if (indexExists(connection, indexName)) {
			return;
		}

		String sql = """
				CREATE INDEX %s
				ON %s (%s)
				""".formatted(qualifiedName(indexName), qualifiedName(TABLE_NAME), columnName);

		execute(connection, sql);

		log.info("Created index {}.", qualifiedName(indexName));
	}

	private boolean tableExists(Connection connection, String tableName) throws SQLException {

		String sql = """
				SELECT 1
				FROM all_tables
				WHERE owner = ?
				  AND table_name = ?
				""";

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setString(1, schemaName);
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

			statement.setString(1, schemaName);
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

			statement.setString(1, schemaName);
			statement.setString(2, indexName);

			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next();
			}
		}
	}

	private String qualifiedName(String objectName) {
		return schemaName + "." + objectName;
	}

	private static String validateSchemaName(String schemaName) {

		if (schemaName == null || schemaName.isBlank()) {
			throw new IllegalArgumentException("Oracle schema name must not be empty.");
		}

		String normalized = schemaName.trim().toUpperCase(Locale.ROOT);

		if (!ORACLE_IDENTIFIER.matcher(normalized).matches() || normalized.length() > 128) {

			throw new IllegalArgumentException("Invalid Oracle schema name: " + schemaName);
		}

		return normalized;
	}

	private void execute(Connection connection, String sql) throws SQLException {

		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
