package ma.bkam.db.upgrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories({ "ma.bkam.db.upgrade.repositories" })
@ComponentScan({ "ma.bkam.db.upgrade" })
@EntityScan({ "ma.bkam.db.upgrade.entities" })
public class MMCDbUpgradeApplication {
  public static void main(String[] args) {
    SpringApplication.run(MMCDbUpgradeApplication.class, args);
  }
}
