package com.felipe.ballast.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

// Lê o cookie, busca a sessão válida pelo SHA-256 e autentica. Sem cookie ou com sessão
// inválida, deixa a requisição anônima: quem responde 401 é a cadeia, igual para os quatro casos.
// Não é @Component de propósito: o Boot o registraria também como filtro de servlet, fora da
// cadeia.
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(SessionAuthenticationFilter.class);

  private final IdentityService identity;
  private final RequestMatcher publicRoutes;

  public SessionAuthenticationFilter(IdentityService identity, RequestMatcher publicRoutes) {
    this.identity = identity;
    this.publicRoutes = publicRoutes;
  }

  // Rota pública não precisa de sessão; consultar o banco ali só faria o /healthz cair junto
  // com ele.
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return publicRoutes.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = tokenOf(request);
    if (token != null) {
      Optional<UUID> userId;
      try {
        userId = identity.findUserIdBySession(token);
      } catch (DataAccessException e) {
        log.error("sessão: falha ao consultar a sessão ip={}", ClientAddress.of(request), e);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"erro\":\"erro interno\"}");
        return;
      }
      userId.ifPresent(SessionAuthenticationFilter::authenticate);
    }
    chain.doFilter(request, response);
  }

  private static void authenticate(UUID userId) {
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUser(userId), null, List.of()));
    SecurityContextHolder.setContext(context);
  }

  private static String tokenOf(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (SessionCookies.NAME.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
