package com.felipe.ballast.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Primeiro filtro de todos (ADR-049): a requisição recusada aqui não lê corpo, não consulta sessão
// e não toca no banco. Quem limita antes dele é o Vercel; ele protege a VM e o Neon.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  static final String MESSAGE = "{\"erro\":\"muitas requisições, tente mais tarde\"}";

  private static final String ALL_CLIENTS = "todos";

  private record Rule(String name, FixedWindowLimiter limiter) {}

  private final Rule general;
  private final Rule login;
  private final Rule registration;
  private final Rule auth;
  private final Clock clock;

  RateLimitFilter(RateLimitProperties properties, Clock clock) {
    Duration minute = Duration.ofMinutes(1);
    int max = properties.maxClients();
    this.general =
        new Rule("geral", new FixedWindowLimiter(properties.generalPerMinute(), minute, max));
    this.login =
        new Rule("login", new FixedWindowLimiter(properties.loginPerMinute(), minute, max));
    this.registration =
        new Rule(
            "cadastro",
            new FixedWindowLimiter(properties.registrationPerHour(), Duration.ofHours(1), max));
    this.auth = new Rule("auth", new FixedWindowLimiter(properties.authPerMinute(), minute, 1));
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String client = ClientKey.of(request.getRemoteAddr());
    Instant now = clock.instant();
    Rule route = routeRule(request);
    // O teto somado só gasta depois que o limite do próprio IP aceitou: senão um IP já barrado
    // esgotaria o teto de todo mundo.
    if (refused(general, client, now, response)
        || (route != null
            && (refused(route, client, now, response)
                || refused(auth, ALL_CLIENTS, now, response)))) {
      return;
    }
    chain.doFilter(request, response);
  }

  // Comparação exata: variações como "/auth//login" ou "/auth/login;x" são recusadas pelo firewall
  // do Spring Security antes de chegar ao argon2, e ainda contam no limite geral.
  private Rule routeRule(HttpServletRequest request) {
    if (!"POST".equals(request.getMethod())) {
      return null;
    }
    return switch (request.getRequestURI()) {
      case "/auth/login" -> login;
      case "/auth/cadastro" -> registration;
      default -> null;
    };
  }

  private boolean refused(Rule rule, String key, Instant now, HttpServletResponse response)
      throws IOException {
    FixedWindowLimiter.Decision decision = rule.limiter().tryAcquire(key, now);
    if (decision.allowed()) {
      return false;
    }
    // Uma linha por chave e janela: o flood não vira flood de log.
    if (decision.firstRefusal()) {
      log.warn("limite de requisições atingido regra={} ip={}", rule.name(), key);
    }
    response.setStatus(429);
    // Para cima: dizer 0 faria o cliente tentar de novo ainda dentro da janela.
    long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(MESSAGE);
    return true;
  }

  void clear() {
    general.limiter().clear();
    login.limiter().clear();
    registration.limiter().clear();
    auth.limiter().clear();
  }
}
