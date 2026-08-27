package eu.sovereigntylens.adapter.web.security;

import eu.sovereigntylens.domain.DomainException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Gate in front of the presenter API.
 *
 * <p>Authentication is enforced here rather than in each handler so that adding an admin endpoint
 * cannot accidentally add an unauthenticated one. The interceptor is registered for the whole
 * {@code /api/admin} tree by {@code config.AdminWebConfig}, which is also the only place the two
 * unauthenticated endpoints - login and logout - are named.
 *
 * <p>Every rejection is the same generic 401 produced by {@link DomainException#unauthorized()}: a
 * missing cookie, a forged one and an expired one are indistinguishable to the caller.
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

  private final AdminSessionCookie sessionCookie;

  public AdminAuthInterceptor(AdminSessionCookie sessionCookie) {
    this.sessionCookie = sessionCookie;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    // A preflight carries no cookies by definition, so rejecting it would only replace a readable
    // CORS answer with an opaque failure in the browser's console.
    if (CorsUtils.isPreFlightRequest(request)) {
      return true;
    }
    if (!hasValidSession(request)) {
      throw DomainException.unauthorized();
    }
    return true;
  }

  /**
   * Whether the request carries a session cookie this service signed.
   *
   * <p>Every cookie of that name is considered rather than only the first. A browser can hold more
   * than one - a stale copy left over from a different scheme or port on the same host - and the
   * order they arrive in is not something the presenter can influence mid-talk.
   */
  private boolean hasValidSession(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }
    for (Cookie cookie : cookies) {
      if (AdminSessionCookie.NAME.equals(cookie.getName())
          && sessionCookie.isValid(cookie.getValue())) {
        return true;
      }
    }
    return false;
  }
}
