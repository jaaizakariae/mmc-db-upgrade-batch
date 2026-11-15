package ma.bkam.db.upgrade.entities;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ma.bkam.db.upgrade.enums.ErrorCodeEnum;
import ma.bkam.db.upgrade.enums.ExecutionStatusEnum;

@Entity
@Table(schema = "cotmad", name = "SQL_EXECUTION_HISTORY", indexes = {
		@Index(name = "IDX_SQL_HIST_SEQ", columnList = "SEQUENCE_NUMBER"),
		@Index(name = "IDX_SQL_HIST_FILENAME", columnList = "FILENAME"),
		@Index(name = "IDX_SQL_HIST_CHECKSUM", columnList = "CHECKSUM_SHA256") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SqlExecutionHistory {

	@Id
	@SequenceGenerator(name = "sql_execution_history_seq_gen", sequenceName = "cotmad.sql_execution_history_seq", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sql_execution_history_seq_gen")
	@Column(name = "ID", nullable = false)
	private Long id;

	@Column(name = "FILENAME", nullable = false, length = 512)
	private String filename;

	@Column(name = "SEQUENCE_NUMBER", nullable = false)
	private Integer sequenceNumber;

	@Column(name = "CHECKSUM_SHA256", nullable = false, length = 64)
	private String checksumSha256;

	@Column(name = "EXECUTED_AT", nullable = false)
	private Instant executedAt;

	@Column(name = "DURATION_MS", nullable = false)
	private Long durationMs;

	@Enumerated(EnumType.STRING)
	@Column(name = "STATUS", nullable = false, length = 32)
	private ExecutionStatusEnum status;

	@Enumerated(EnumType.STRING)
	@Column(name = "ERROR_CODE", length = 64)
	private ErrorCodeEnum errorCode;

	@Lob
	@Column(name = "ERROR_MESSAGE")
	private String errorMessage;
}