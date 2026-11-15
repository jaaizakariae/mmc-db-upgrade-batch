package ma.bkam.db.upgrade.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCodeEnum {
  FILE_NAME_INVALID("Filename does not match required pattern ^\\d{4}_.+\\.sql$"),
  
  FILE_ALREADY_EXECUTED("An identical script was already executed (filename or checksum)"),
  
  IO_ERROR("I/O error while reading/moving files"),
  
  SQL_EXECUTION_ERROR("Error while executing SQL script"),
  
  HISTORY_SAVE_ERROR("Could not persist execution history"),
  
  DB_CONNECTION_ERROR("Database connection/transaction error");

  private final String description;
  
}