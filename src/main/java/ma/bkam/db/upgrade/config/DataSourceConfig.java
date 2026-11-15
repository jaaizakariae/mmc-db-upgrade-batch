package ma.bkam.db.upgrade.config;

import javax.sql.DataSource;

import org.jasypt.encryption.StringEncryptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.RequiredArgsConstructor;

/**
 * DataSource configuration class.
 *
 * Important: Because the DataSource is created manually to decrypt the
 * password, Spring Boot does not automatically bind spring.datasource.hikari.*.
 * Therefore, HikariConfig must be explicitly bound
 * with @ConfigurationProperties.
 */
@Configuration
@RequiredArgsConstructor
public class DataSourceConfig {

	@Value("${spring.datasource.url}")
	private String dbUrl;

	@Value("${spring.datasource.username}")
	private String dbUsername;

	@Value("${spring.datasource.encryptedPassword}")
	private String encryptedDbPassword;

	@Value("${spring.datasource.driver-class-name}")
	private String driverClassName;

	@Qualifier("stringEncryptor")
	private final StringEncryptor stringEncryptor;

	@Bean
	@ConfigurationProperties(prefix = "spring.datasource.hikari")
	HikariConfig hikariConfig() {
		return new HikariConfig();
	}

	@Bean
	DataSource dataSource(HikariConfig hikariConfig) {
		hikariConfig.setJdbcUrl(dbUrl);
		hikariConfig.setUsername(dbUsername);
		hikariConfig.setDriverClassName(driverClassName);

		String decryptedPassword = stringEncryptor.decrypt(encryptedDbPassword);
		hikariConfig.setPassword(decryptedPassword);

		return new HikariDataSource(hikariConfig);
	}
}