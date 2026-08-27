package eu.sovereigntylens.config;

import eu.sovereigntylens.adapter.web.security.AdminAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the presenter authentication gate.
 *
 * <p>The pattern is the whole {@code /api/admin} tree with two named exceptions, rather than a list
 * of protected endpoints. A new admin endpoint is therefore protected by default, and opening one
 * up is a visible edit to this file.
 *
 * <p>Login and logout are excluded by path, which also excludes any other method on those two
 * paths. Neither has another mapping, so an unauthenticated {@code GET /api/admin/login} produces a
 * 405 rather than reaching anything.
 *
 * <p>Kept separate from {@link WebConfig} because that file is the CORS policy and this one is the
 * authentication boundary; a change to one should never require reading the other.
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

  private final AdminAuthInterceptor adminAuthInterceptor;

  public AdminWebConfig(AdminAuthInterceptor adminAuthInterceptor) {
    this.adminAuthInterceptor = adminAuthInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(adminAuthInterceptor)
        .addPathPatterns("/api/admin/**")
        .excludePathPatterns("/api/admin/login", "/api/admin/logout");
  }
}
