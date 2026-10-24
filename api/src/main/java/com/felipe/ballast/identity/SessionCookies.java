package com.felipe.ballast.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
class SessionCookies {

  private static final String NAME = "ballast_sessao";
  // Desafio do segundo fator, entre a senha certa e o código (ADR-050).
  private static final String CHALLENGE_NAME = "ballast_mfa";
  // __Host- só vale com Secure, Path=/ e sem Domain, que é o que base() monta. Sem Secure (só em
  // desenvolvimento) o navegador descartaria o cookie, então o nome simples fica.
  private static final String HOST_PREFIX = "__Host-";

  private final boolean secure;

  SessionCookies(IdentityProperties properties) {
    this.secure = properties.isCookieSecure();
  }

  static String sessionName(boolean secure) {
    return secure ? HOST_PREFIX + NAME : NAME;
  }

  private static String challengeName(boolean secure) {
    return secure ? HOST_PREFIX + CHALLENGE_NAME : CHALLENGE_NAME;
  }

  // Cookie repetido é tratado como sem sessão, qualquer que seja a ordem: escolher um dos valores
  // deixaria quem injeta um cookie no host derrubar a sessão da vítima ou escolher a que vale.
  static Optional<String> tokenOf(HttpServletRequest request, boolean secure) {
    return valueOf(request, sessionName(secure));
  }

  Optional<String> tokenOf(HttpServletRequest request) {
    return tokenOf(request, secure);
  }

  Optional<String> challengeOf(HttpServletRequest request) {
    return valueOf(request, challengeName(secure));
  }

  private static Optional<String> valueOf(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    String token = null;
    for (Cookie cookie : cookies) {
      if (!name.equals(cookie.getName())) {
        continue;
      }
      if (token != null) {
        return Optional.empty();
      }
      token = cookie.getValue();
    }
    return Optional.ofNullable(token).filter(value -> !value.isEmpty());
  }

  String open(String token, Duration lifetime) {
    return base(sessionName(secure), token).maxAge(lifetime).build().toString();
  }

  String expire() {
    return base(sessionName(secure), "").maxAge(Duration.ZERO).build().toString();
  }

  String openChallenge(String token, Duration lifetime) {
    return base(challengeName(secure), token).maxAge(lifetime).build().toString();
  }

  String expireChallenge() {
    return base(challengeName(secure), "").maxAge(Duration.ZERO).build().toString();
  }

  // Secure só é desligado em desenvolvimento; IdentityProperties recusa false fora dele.
  private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
    return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Lax").path("/");
  }
}
