package com.felipe.ballast.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Defesa de CSRF além do SameSite=Lax (ADR-020, ADR-051): método que muda estado só passa se o
// Origin for o do próprio servidor ou estiver na lista. Sem Origin (curl, cliente que não é
// navegador) passa, a menos que o navegador diga que o pedido é cross-site no Sec-Fetch-Site.
// Roda antes da segurança: um pedido de outra origem nem chega a consultar a sessão.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class OriginFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(OriginFilter.class);

  static final String MESSAGE = "{\"erro\":\"origem não permitida\"}";

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
  private static final Pattern ORIGIN =
      Pattern.compile("(https?)://([^/@?#\\s]+)", Pattern.CASE_INSENSITIVE);

  private final Set<String> allowed;

  OriginFilter(OriginProperties properties) {
    this.allowed = Set.copyOf(properties.allowed());
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return SAFE_METHODS.contains(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isAllowed(request)) {
      chain.doFilter(request, response);
      return;
    }
    // Sem o valor do Origin no log: vem do cliente e não precisa de mais que o fato.
    log.warn("origem recusada metodo={} ip={}", request.getMethod(), request.getRemoteAddr());
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(MESSAGE);
  }

  private boolean isAllowed(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    if (origin == null) {
      return !"cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"));
    }
    var matcher = ORIGIN.matcher(origin);
    if (!matcher.matches()) {
      // "null" (iframe com sandbox, redirecionamento), vazio e lixo.
      return false;
    }
    String authority = matcher.group(2).toLowerCase(Locale.ROOT);
    String normalized = (matcher.group(1) + "://" + authority).toLowerCase(Locale.ROOT);
    if (allowed.contains(normalized)) {
      return true;
    }
    // Mesma origem: o navegador só põe no Origin o host que o usuário abriu, que é o do Host.
    // O esquema fica de fora porque atrás do proxy a API vê http.
    String host = request.getHeader("Host");
    return host != null && authority.equals(host.toLowerCase(Locale.ROOT));
  }
}
