package ma.bkam.db.upgrade.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "data-srcipts")
public class DbUpgradeProperties {
  private String inputFolder;
  
  private String archiveFolder;
  
  private String errorFolder;
  
  private String filenameRegex;
  
  private boolean stopOnError = false;
}
