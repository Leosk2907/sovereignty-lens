package eu.sovereigntylens;

import eu.sovereigntylens.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Backend service for the Sovereignty Lens audience-participation demo. */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class SovereigntyLensApplication {

  public static void main(String[] args) {
    SpringApplication.run(SovereigntyLensApplication.class, args);
  }
}
