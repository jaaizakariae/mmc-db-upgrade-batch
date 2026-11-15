package ma.bkam.db.upgrade.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import ma.bkam.db.upgrade.entities.SqlExecutionHistory;
import ma.bkam.db.upgrade.enums.ExecutionStatusEnum;

@Repository
public interface SqlExecutionHistoryRepository extends JpaRepository<SqlExecutionHistory, Long> {

	Optional<SqlExecutionHistory> findByFilename(String filename);

	boolean existsByFilenameAndStatus(String filename, ExecutionStatusEnum status);

	Optional<SqlExecutionHistory> findFirstByChecksumSha256AndStatusOrderByExecutedAtDesc(String checksumSha256,
			ExecutionStatusEnum status);
}