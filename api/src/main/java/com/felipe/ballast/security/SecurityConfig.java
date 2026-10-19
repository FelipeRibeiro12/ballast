package com.felipe.ballast.security;

import com.felipe.ballast.identity.IdentityProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// ADR-020: cadeia sem formLogin, sem httpBasic e sem HttpSession. O token de CSRF fica
// desligado; a defesa é o SameSite=Lax do cookie mais o Content-Type application/json.
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {

  private static final String NOT_AUTHENTICATED = "{\"erro\":\"não autenticado\"}";
  private static final String FORBIDDEN = "{\"erro\":\"acesso negado\"}";

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, IdentityProperties properties)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .headers(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Públicas uma a uma; o que não está aqui exige sessão.
        .authorizeHttpRequests(
            auth -> {
              auth.requestMatchers(HttpMethod.GET, "/healthz").permitAll();
              auth.requestMatchers("/v3/api-docs*", "/v3/api-docs/**").permitAll();
              // Fechado, a rota nem é pública: cai em anyRequest, igual a uma rota inexistente.
              if (properties.isRegistrationOpen()) {
                auth.requestMatchers(HttpMethod.POST, "/auth/cadastro").permitAll();
              }
              auth.requestMatchers(HttpMethod.POST, "/auth/login").permitAll();
              auth.anyRequest().authenticated();
            })
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

  private static void writeJson(HttpServletResponse response, int status, String body)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(body);
  }
}
