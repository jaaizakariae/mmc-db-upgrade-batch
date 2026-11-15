package ma.bkam.db.upgrade.config;

import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * @author z.jaai 21/09/2023
 */

@Data
@Configuration
public class EncryptionConfig {

	@Value("${jasypt.encryptor.password}")
	private String encryptorPassword;

	@Value("${jasypt.encryptor.algorithm}")
	private String encryptorAlgorithm;

	@Bean
	StringEncryptor stringEncryptor() {
		PooledPBEStringEncryptor encryptor = new PooledPBEStringEncryptor();
		SimpleStringPBEConfig config = new SimpleStringPBEConfig();
		config.setPassword(encryptorPassword);
		config.setAlgorithm(encryptorAlgorithm);
		config.setKeyObtentionIterations("1000");
		config.setPoolSize("1");
		config.setProviderName("SunJCE");
		config.setSaltGeneratorClassName("org.jasypt.salt.RandomSaltGenerator");
		config.setStringOutputType("base64");
		encryptor.setConfig(config);
		return encryptor;
	}
}
