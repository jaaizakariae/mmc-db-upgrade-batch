package ma.bkam.db.upgrade.service;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.bkam.db.upgrade.entities.SqlExecutionHistory;
import ma.bkam.db.upgrade.enums.ErrorCodeEnum;
import ma.bkam.db.upgrade.enums.ExecutionStatusEnum;
import ma.bkam.db.upgrade.enums.SqlFileProcessResultEnum;
import ma.bkam.db.upgrade.properties.DbUpgradeProperties;
import ma.bkam.db.upgrade.repositories.SqlExecutionHistoryRepository;
import ma.bkam.db.upgrade.util.ChecksumUtil;
import ma.bkam.db.upgrade.util.FileNaming;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlFileProcessor {

	private static final DateTimeFormatter ARCHIVE_TIMESTAMP_FORMATTER = DateTimeFormatter
			.ofPattern("yyyy_MM_dd__HH_mm_ss");

	private static final Pattern LINE_NUMBER_PATTERN = Pattern.compile("\\bline\\s+(\\d+)\\b",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern ORACLE_ERROR_PATTERN = Pattern.compile("\\bORA-\\d{5}\\b");

	private final DbUpgradeProperties props;
	private final SqlExecutionHistoryRepository repo;
	private final DataSource dataSource;
	private final PlatformTransactionManager txManager;

	public SqlFileProcessResultEnum processSingleFile(Path file) {
		String filename = file.getFileName().toString();

		log.info("Processing {}", filename);

		Optional<FileNaming.Parsed> parsedOpt = FileNaming.parse(filename, props.getFilenameRegex());

		if (parsedOpt.isEmpty()) {
			log.error("Invalid filename pattern: {}", filename);
			moveToErrorWithLog(file, ErrorCodeEnum.FILE_NAME_INVALID, "Invalid filename pattern.");
			return SqlFileProcessResultEnum.FAILED;
		}

		FileNaming.Parsed parsed = parsedOpt.get();

		String checksum;

		try {
			checksum = ChecksumUtil.sha256(file);
			log.debug("SQL script checksum. filename={}, sha256={}", filename, checksum);

		} catch (Exception exception) {
			log.error("Checksum error for {}: {}", filename, exception.getMessage(), exception);
			moveToErrorWithLog(file, ErrorCodeEnum.IO_ERROR, "Checksum error: " + exception.getMessage(), exception);
			return SqlFileProcessResultEnum.FAILED;
		}

		boolean alreadySucceededByName = repo.existsByFilenameAndStatus(filename, ExecutionStatusEnum.SUCCESS);

		if (alreadySucceededByName) {
			log.warn("Script already executed successfully with the same filename. Skipping script: {}", filename);

			moveToSkipped(file);
			return SqlFileProcessResultEnum.SKIPPED;
		}

		Optional<SqlExecutionHistory> existingSuccessByChecksum = repo
				.findFirstByChecksumSha256AndStatusOrderByExecutedAtDesc(checksum, ExecutionStatusEnum.SUCCESS);

		if (existingSuccessByChecksum.isPresent()) {
			SqlExecutionHistory existingHistory = existingSuccessByChecksum.get();

			log.warn(
					"Script content was already executed successfully. Skipping script: {}. "
							+ "Same SHA-256 checksum found in previously executed script: {} "
							+ "(sequenceNumber={}, executedAt={}, historyId={}).",
					filename, existingHistory.getFilename(), existingHistory.getSequenceNumber(),
					existingHistory.getExecutedAt(), existingHistory.getId());

			moveToSkipped(file);
			return SqlFileProcessResultEnum.SKIPPED;
		}

		long start = System.currentTimeMillis();

		ExecutionStatusEnum status = ExecutionStatusEnum.ERROR;
		ErrorCodeEnum errorCode = null;
		String errorMessage = null;
		Throwable lastError = null;

		try {
			executeScriptInSingleTransaction(file);

			status = ExecutionStatusEnum.SUCCESS;

			log.info("SUCCESS {}", filename);

		} catch (ScriptException exception) {
			status = ExecutionStatusEnum.ERROR;
			errorCode = ErrorCodeEnum.SQL_EXECUTION_ERROR;
			errorMessage = buildDetailedSqlErrorMessage(exception);
			lastError = exception;

			log.error("ERROR executing {}: {}", filename, errorMessage, exception);

		} catch (SQLException exception) {
			status = ExecutionStatusEnum.ERROR;
			errorCode = ErrorCodeEnum.SQL_EXECUTION_ERROR;
			errorMessage = buildDetailedOracleSqlErrorMessage(exception);
			lastError = exception;

			log.error("ERROR executing {}: {}", filename, errorMessage, exception);

		} catch (Exception exception) {
			status = ExecutionStatusEnum.ERROR;
			errorCode = ErrorCodeEnum.SQL_EXECUTION_ERROR;
			errorMessage = rootMessage(exception);
			lastError = exception;

			log.error("ERROR executing {}: {}", filename, errorMessage, exception);

		} finally {
			long duration = System.currentTimeMillis() - start;

			persistHistoryRequiresNew(filename, parsed.sequenceNumber(), checksum, status, errorCode, errorMessage,
					duration);
		}

		if (status == ExecutionStatusEnum.SUCCESS) {
			moveToArchive(file);
			return SqlFileProcessResultEnum.SUCCESS;
		}

		moveToErrorWithLog(file, errorCode != null ? errorCode : ErrorCodeEnum.SQL_EXECUTION_ERROR,
				errorMessage != null ? errorMessage : "Unknown SQL error", lastError);

		return SqlFileProcessResultEnum.FAILED;
	}

	private void executeScriptInSingleTransaction(Path file) throws Exception {
		String sql = Files.readString(file, StandardCharsets.UTF_8);

		if (!sql.isEmpty() && sql.charAt(0) == '\uFEFF') {
			sql = sql.substring(1);
		}

		sql = removeSqlPlusCommands(sql);

		List<String> statements = splitSqlStatements(sql);

		try (Connection connection = dataSource.getConnection()) {
			boolean previousAutoCommit = connection.getAutoCommit();

			connection.setAutoCommit(false);

			try (Statement statement = connection.createStatement()) {
				for (String rawStatement : statements) {
					String sqlStatement = rawStatement.trim();

					if (sqlStatement.isBlank()) {
						continue;
					}

					log.debug("Executing SQL:\n{}", truncate(sqlStatement, 800));

					statement.execute(sqlStatement);
				}

				connection.commit();

			} catch (Exception exception) {
				rollback(connection, file);
				throw exception;

			} finally {
				restoreAutoCommit(connection, previousAutoCommit, file);
			}
		}
	}

	/**
	 * Removes SQL*Plus-only commands without removing normal Oracle SQL clauses.
	 *
	 * Important: - "CONNECT user/password" is a SQL*Plus command. - "CONNECT BY
	 * ..." is an Oracle SQL clause and must remain intact.
	 */
	private static String removeSqlPlusCommands(String sql) {
		StringBuilder result = new StringBuilder();

		for (String line : sql.split("\\R", -1)) {
			String trimmedLine = line.trim();
			String upperLine = trimmedLine.toUpperCase();

			boolean sqlPlusCommand = upperLine.startsWith("SET SERVEROUTPUT") || upperLine.startsWith("SET DEFINE")
					|| upperLine.startsWith("SET ECHO") || upperLine.startsWith("SET FEEDBACK")
					|| upperLine.startsWith("SET HEADING") || upperLine.startsWith("PROMPT ")
					|| upperLine.startsWith("SPOOL ") || upperLine.startsWith("WHENEVER SQLERROR")
					|| upperLine.startsWith("WHENEVER OSERROR") || upperLine.equals("DISCONNECT")
					|| upperLine.equals("EXIT") || upperLine.startsWith("EXIT ") || upperLine.startsWith("REM ")
					|| isSqlPlusConnectCommand(upperLine);

			if (!sqlPlusCommand) {
				result.append(line).append(System.lineSeparator());
			}
		}

		return result.toString();
	}

	/**
	 * Removes only SQL*Plus CONNECT commands. Keeps valid Oracle SQL such as:
	 * CONNECT BY PRIOR id = parent_id
	 */
	private static boolean isSqlPlusConnectCommand(String upperLine) {
		if (upperLine.equals("CONNECT")) {
			return true;
		}

		return upperLine.startsWith("CONNECT ") && !upperLine.startsWith("CONNECT BY");
	}

	private void persistHistoryRequiresNew(String filename, int sequenceNumber, String checksum,
			ExecutionStatusEnum status, ErrorCodeEnum code, String message, long durationMs) {

		try {
			TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);
			transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

			transactionTemplate.execute(statusTransaction -> {
				SqlExecutionHistory history = repo.findByFilename(filename).orElseGet(SqlExecutionHistory::new);

				history.setFilename(filename);
				history.setSequenceNumber(sequenceNumber);
				history.setChecksumSha256(checksum);
				history.setExecutedAt(Instant.now());
				history.setDurationMs(durationMs);
				history.setStatus(status);
				history.setErrorCode(code);
				history.setErrorMessage(message);

				repo.saveAndFlush(history);

				return null;
			});

		} catch (Exception exception) {
			log.error("Failed to save history for {}: {}", filename, exception.getMessage(), exception);
		}
	}

	private void moveToArchive(Path file) {
		Path archiveDir = Paths.get(props.getArchiveFolder());

		ensureDir(archiveDir);

		String originalName = file.getFileName().toString();
		String base = originalName.replaceFirst("(?i)\\.sql$", "");
		String timestamp = LocalDateTime.now().format(ARCHIVE_TIMESTAMP_FORMATTER);
		String targetName = base + "_" + timestamp + ".sql";

		Path target = archiveDir.resolve(targetName);

		try {
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
			log.info("Archived to {}", target);

		} catch (IOException exception) {
			log.error("Failed to move to archive: {}", exception.getMessage(), exception);
		}
	}

	private void moveToSkipped(Path file) {
		Path skippedDir = Paths.get(props.getArchiveFolder()).resolve("skipped");

		ensureDir(skippedDir);

		String originalName = file.getFileName().toString();
		String base = originalName.replaceFirst("(?i)\\.sql$", "");
		String timestamp = LocalDateTime.now().format(ARCHIVE_TIMESTAMP_FORMATTER);
		String targetName = base + "_SKIPPED_" + timestamp + ".sql";

		Path target = skippedDir.resolve(targetName);

		try {
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
			log.info("Skipped script archived to {}", target);

		} catch (IOException exception) {
			log.error("Failed to move skipped script: {}", exception.getMessage(), exception);
		}
	}

	private void moveToErrorWithLog(Path file, ErrorCodeEnum code, String message) {
		moveToErrorWithLog(file, code, message, null);
	}

	private void moveToErrorWithLog(Path file, ErrorCodeEnum code, String message, Throwable throwable) {
		Path errorRoot = Paths.get(props.getErrorFolder());

		ensureDir(errorRoot);

		String base = file.getFileName().toString().replaceAll("(?i)\\.sql$", "");
		String folderName = base + "_" + System.currentTimeMillis();
		Path errorDir = errorRoot.resolve(folderName);

		ensureDir(errorDir);

		Path sqlTarget = errorDir.resolve(file.getFileName());

		try {
			if (Files.exists(file)) {
				Files.move(file, sqlTarget, StandardCopyOption.REPLACE_EXISTING);
			}

		} catch (IOException moveException) {
			log.error("Failed moving SQL into error folder: {}", moveException.getMessage(), moveException);
		}

		Path logFile = errorDir.resolve("error.log");

		try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8))) {

			writer.printf("ERROR CODE: %s - %s%n", code.name(), code.getDescription());
			writer.printf("DETAILS   : %s%n", message);

			if (throwable != null) {
				writer.println();
				writer.println("STACKTRACE:");
				throwable.printStackTrace(writer);
			}

		} catch (IOException exception) {
			log.error("Failed writing error log: {}", exception.getMessage(), exception);
		}

		log.info("Error folder prepared at {}", errorDir);
	}

	private void rollback(Connection connection, Path file) {
		try {
			connection.rollback();

		} catch (SQLException rollbackException) {
			log.error("Rollback failed for {}: {}", file.getFileName(), rollbackException.getMessage(),
					rollbackException);
		}
	}

	private void restoreAutoCommit(Connection connection, boolean previousAutoCommit, Path file) {
		try {
			connection.setAutoCommit(previousAutoCommit);

		} catch (SQLException autoCommitException) {
			log.warn("Failed to restore autoCommit for {}: {}", file.getFileName(), autoCommitException.getMessage());
		}
	}

	private static void ensureDir(Path dir) {
		try {
			Files.createDirectories(dir);

		} catch (IOException exception) {
			throw new RuntimeException("Cannot create directory " + dir + ": " + exception.getMessage(), exception);
		}
	}

	private static List<String> splitSqlStatements(String sql) {
		List<String> statements = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		boolean inLineComment = false;
		boolean inBlockComment = false;
		boolean oracleBlock = false;
		boolean lineStart = true;

		int index = 0;

		while (index < sql.length()) {
			char currentChar = sql.charAt(index);
			char nextChar = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

			if (lineStart && !inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment) {
				int lineEnd = findLineEnd(sql, index);
				String line = sql.substring(index, lineEnd).trim();

				if ("/".equals(line)) {
					addStatement(statements, current);
					current.setLength(0);
					oracleBlock = false;
					index = lineEnd;
					lineStart = true;
					continue;
				}

				if (!oracleBlock && startsOracleBlock(line)) {
					oracleBlock = true;
				}
			}

			if (inLineComment) {
				current.append(currentChar);

				if (currentChar == '\n') {
					inLineComment = false;
					lineStart = true;
				} else if (!Character.isWhitespace(currentChar)) {
					lineStart = false;
				}

				index++;
				continue;
			}

			if (inBlockComment) {
				current.append(currentChar);

				if (currentChar == '*' && nextChar == '/') {
					current.append(nextChar);
					inBlockComment = false;
					index += 2;
					lineStart = false;
				} else {
					lineStart = currentChar == '\n';
					index++;
				}

				continue;
			}

			if (!inSingleQuote && !inDoubleQuote) {
				if (currentChar == '-' && nextChar == '-') {
					current.append(currentChar).append(nextChar);
					inLineComment = true;
					index += 2;
					lineStart = false;
					continue;
				}

				if (currentChar == '/' && nextChar == '*') {
					current.append(currentChar).append(nextChar);
					inBlockComment = true;
					index += 2;
					lineStart = false;
					continue;
				}
			}

			if (!inDoubleQuote && currentChar == '\'') {
				current.append(currentChar);

				if (inSingleQuote && nextChar == '\'') {
					current.append(nextChar);
					index += 2;
					lineStart = false;
					continue;
				}

				inSingleQuote = !inSingleQuote;
				index++;
				lineStart = false;
				continue;
			}

			if (!inSingleQuote && currentChar == '"') {
				current.append(currentChar);
				inDoubleQuote = !inDoubleQuote;
				index++;
				lineStart = false;
				continue;
			}

			if (!oracleBlock && !inSingleQuote && !inDoubleQuote && currentChar == ';') {
				addStatement(statements, current);
				current.setLength(0);
				index++;
				lineStart = false;
				continue;
			}

			current.append(currentChar);

			if (currentChar == '\n' || currentChar == '\r') {
				lineStart = true;
			} else if (!Character.isWhitespace(currentChar)) {
				lineStart = false;
			}

			index++;
		}

		addStatement(statements, current);

		return statements;
	}

	private static void addStatement(List<String> statements, StringBuilder current) {
		String statement = removeTrailingSlash(current.toString().trim());

		if (!statement.isBlank()) {
			statements.add(statement);
		}
	}

	private static String removeTrailingSlash(String statement) {
		String[] lines = statement.split("\\R", -1);

		int lastNonBlankIndex = -1;

		for (int index = lines.length - 1; index >= 0; index--) {
			if (!lines[index].trim().isEmpty()) {
				lastNonBlankIndex = index;
				break;
			}
		}

		if (lastNonBlankIndex >= 0 && "/".equals(lines[lastNonBlankIndex].trim())) {
			StringBuilder result = new StringBuilder();

			for (int index = 0; index < lastNonBlankIndex; index++) {
				result.append(lines[index]).append(System.lineSeparator());
			}

			return result.toString().trim();
		}

		return statement;
	}

	private static int findLineEnd(String value, int start) {
		int index = start;

		while (index < value.length()) {
			char current = value.charAt(index);

			if (current == '\n' || current == '\r') {
				return index;
			}

			index++;
		}

		return value.length();
	}

	private static boolean startsOracleBlock(String line) {
		String normalized = line.trim().replaceAll("\\s+", " ").toUpperCase();

		return normalized.startsWith("DECLARE") || normalized.startsWith("BEGIN")
				|| normalized.startsWith("CREATE OR REPLACE PROCEDURE ")
				|| normalized.startsWith("CREATE OR REPLACE FUNCTION ")
				|| normalized.startsWith("CREATE OR REPLACE PACKAGE ")
				|| normalized.startsWith("CREATE OR REPLACE PACKAGE BODY ")
				|| normalized.startsWith("CREATE OR REPLACE TRIGGER ")
				|| normalized.startsWith("CREATE OR REPLACE TYPE ")
				|| normalized.startsWith("CREATE OR REPLACE TYPE BODY ");
	}

	private String buildDetailedSqlErrorMessage(ScriptException exception) {
		StringBuilder result = new StringBuilder();

		Integer line = tryGetLineNumber(exception);
		String statement = tryGetStatement(exception);

		if (line != null && line > 0) {
			result.append("At line ").append(line).append(": ");
		}

		if (statement != null && !statement.isBlank()) {
			result.append(truncate(statement.trim(), 600));

		} else if (exception.getMessage() != null && result.length() == 0) {
			result.append(exception.getMessage());
		}

		Throwable root = rootCause(exception);

		if (root instanceof SQLException sqlException) {
			result.append("\n").append(buildDetailedOracleSqlErrorMessage(sqlException));

		} else if (root != null && root != exception) {
			result.append("\nCause=").append(root.getClass().getSimpleName()).append(": ").append(root.getMessage());
		}

		return result.toString();
	}

	private String buildDetailedOracleSqlErrorMessage(SQLException sqlException) {
		StringBuilder result = new StringBuilder();

		appendOracleSqlException(result, sqlException, "ORACLE SQL ERROR");

		SQLException next = sqlException.getNextException();
		int index = 1;

		while (next != null) {
			result.append("\n\nNEXT EXCEPTION ").append(index).append(":");

			appendOracleSqlException(result, next, "ORACLE SQL ERROR");
			next = next.getNextException();
			index++;
		}

		Throwable root = rootCause(sqlException);

		if (root != null && root != sqlException && !(root instanceof SQLException)) {
			result.append("\nCause=").append(root.getClass().getSimpleName()).append(": ").append(root.getMessage());
		}

		return result.toString();
	}

	private static void appendOracleSqlException(StringBuilder result, SQLException sqlException, String title) {

		String message = sqlException.getMessage();

		result.append(title).append("\nSQLState=").append(sqlException.getSQLState()).append(" | ErrorCode=")
				.append(sqlException.getErrorCode());

		String oraCode = extractOracleErrorCode(message);

		if (oraCode != null) {
			result.append(" | ").append(oraCode);
		}

		result.append("\nMessage=").append(message);
	}

	private static String extractOracleErrorCode(String message) {
		if (message == null) {
			return null;
		}

		Matcher matcher = ORACLE_ERROR_PATTERN.matcher(message);

		return matcher.find() ? matcher.group() : null;
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}

		if (value.length() <= max) {
			return value;
		}

		return value.substring(0, max) + "...";
	}

	private static Throwable rootCause(Throwable throwable) {
		Throwable root = throwable;

		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}

		return root;
	}

	private static String rootMessage(Throwable throwable) {
		Throwable root = rootCause(throwable);

		return root == null ? throwable.getMessage() : root.getMessage();
	}

	private Integer tryGetLineNumber(ScriptException exception) {
		if (!(exception instanceof ScriptStatementFailedException failedException)) {
			return null;
		}

		try {
			Method method = failedException.getClass().getMethod("getLineNumber");
			Object value = method.invoke(failedException);

			if (value instanceof Number number) {
				return number.intValue();
			}

		} catch (ReflectiveOperationException ignored) {
			// Version-safe fallback.
		}

		return parseLineFromMessage(exception.getMessage());
	}

	private String tryGetStatement(ScriptException exception) {
		if (!(exception instanceof ScriptStatementFailedException failedException)) {
			return null;
		}

		try {
			Method method = failedException.getClass().getMethod("getStatement");
			Object value = method.invoke(failedException);

			return value != null ? value.toString() : null;

		} catch (ReflectiveOperationException ignored) {
			// Version-safe fallback.
		}

		return null;
	}

	private Integer parseLineFromMessage(String message) {
		if (message == null) {
			return null;
		}

		Matcher matcher = LINE_NUMBER_PATTERN.matcher(message);

		if (matcher.find()) {
			try {
				return Integer.parseInt(matcher.group(1));

			} catch (NumberFormatException ignored) {
				// Ignore invalid line number.
			}
		}

		return null;
	}
}