package com.felipe.ballast.security;

import com.felipe.ballast.identity.IdentityProperties;
import com.felipe.ballast.identity.IdentityService;
import com.felipe.ballast.identity.SessionAuthenticationFilter;
import com.felipe.ballast.web.IdempotencyFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

// ADR-020: cadeia sem formLogin, sem httpBasic e sem HttpSession. O token de CSRF fica
// desligado; a defesa é o SameSite=Lax do cookie mais o Content-Type application/json.
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {

  private static final String NOT_AUTHENTICATED = "{\"erro\":\"não autenticado\"}";
  private static final String FORBIDDEN = "{\"erro\":\"acesso negado\"}";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      IdentityProperties properties,
      IdentityService identity,
      Environment environment,
      JdbcClient jdbc,
      Clock clock)
      throws Exception {
    // Vazio ou ausente vale falso: só "true" explícito abre a spec.
    boolean apiDocsEnabled =
        environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false);
    RequestMatcher publicRoutes = publicRoutes(properties, apiDocsEnabled);
    return http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .headers(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new SessionAuthenticationFilter(identity, publicRoutes, properties.isCookieSecure()),
            AuthorizationFilter.class)
        // Depois da autorização: o escopo da chave de idempotência é o usuário da sessão.
        .addFilterAfter(new IdempotencyFilter(jdbc, clock), AuthorizationFilter.class)
        .authorizeHttpRequests(
            auth -> auth.requestMatchers(publicRoutes).permitAll().anyRequest().authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) ->
                            writeJson(
                                response, HttpServletResponse.SC_UNAUTHORIZED, NOT_AUTHENTICATED))
                    .accessDeniedHandler(
                        (request, response, ex) ->
                            writeJson(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN)))
        .build();
  }

  // Rotas públicas listadas uma a uma; o que não está aqui exige sessão. Fechado, o cadastro
  // nem entra na lista e cai em anyRequest, igual a uma rota inexistente; a spec idem.
  private static RequestMatcher publicRoutes(
      IdentityProperties properties, boolean apiDocsEnabled) {
    PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
    List<RequestMatcher> routes = new ArrayList<>();
    routes.add(paths.matcher(HttpMethod.GET, "/healthz"));
    routes.add(paths.matcher(HttpMethod.HEAD, "/healthz"));
    if (apiDocsEnabled) {
      routes.add(paths.matcher("/v3/api-docs*"));
      routes.add(paths.matcher("/v3/api-docs/**"));
    }
    routes.add(paths.matcher(HttpMethod.POST, "/auth/login"));
    // Segunda etapa do login com MFA: ainda não há sessão, só o cookie do desafio (ADR-050).
    routes.add(paths.matcher(HttpMethod.POST, "/auth/login/mfa"));
    routes.add(paths.matcher(HttpMethod.POST, "/auth/logout"));
    if (properties.isRegistrationOpen()) {
      routes.add(paths.matcher(HttpMethod.POST, "/auth/cadastro"));
    }
    return new OrRequestMatcher(routes);
  }

  private static void writeJson(HttpServletResponse response, int status, String body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(body);
  }
}
