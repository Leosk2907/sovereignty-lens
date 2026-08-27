package eu.sovereigntylens.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin configuration.
 *
 * <p>The audience form, presentation and admin UI are served from a different origin than this API,
 * and the admin session travels in a cookie, so credentials must be allowed and the origin list has
 * to be explicit rather than a wildcard.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final AppProperties properties;

  public WebConfig(AppProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(properties.corsAllowedOrigins().toArray(String[]::new))
        .allowedMethods("GET", "POST", "PATCH", "OPTIONS")
        .allowedHeaders("Content-Type", "Last-Event-ID")
        .allowCredentials(true)
        .maxAge(3600);
  }
}
