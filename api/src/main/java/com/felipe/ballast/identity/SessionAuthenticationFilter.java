package com.felipe.ballast.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
import org.springframework.dao.DataAccessResourceFailureException;
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
    Optional<String> token = SessionCookies.tokenOf(request);
    if (token.isPresent()) {
      Optional<UUID> userId;
      try {
        userId = identity.findUserIdBySession(token.get());
      } catch (DataAccessResourceFailureException e) {
        log.error("sessão: banco indisponível ip={}", ClientAddress.of(request), e);
        writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "serviço indisponível");
        return;
      } catch (DataAccessException e) {
        log.error("sessão: falha ao consultar a sessão ip={}", ClientAddress.of(request), e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "erro interno");
        return;
      }
      userId.ifPresent(SessionAuthenticationFilter::authenticate);
    }
    chain.doFilter(request, response);
  }

  private static void writeError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write("{\"erro\":\"" + message + "\"}");
  }

  private static void authenticate(UUID userId) {
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUser(userId), null, List.of()));
    SecurityContextHolder.setContext(context);
  }
}
