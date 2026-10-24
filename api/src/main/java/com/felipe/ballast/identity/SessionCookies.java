package com.felipe.ballast.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
class SessionCookies {

  static final String NAME = "ballast_sessao";
  // Desafio do segundo fator, entre a senha certa e o código (ADR-050).
  static final String CHALLENGE_NAME = "ballast_mfa";

  private final boolean secure;

  SessionCookies(IdentityProperties properties) {
    this.secure = properties.isCookieSecure();
  }

  // Cookie repetido é tratado como sem sessão, qualquer que seja a ordem: escolher um dos valores
  // deixaria quem injeta um cookie no host derrubar a sessão da vítima ou escolher a que vale.
  static Optional<String> tokenOf(HttpServletRequest request) {
    return valueOf(request, NAME);
  }

  static Optional<String> challengeOf(HttpServletRequest request) {
    return valueOf(request, CHALLENGE_NAME);
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
    return base(NAME, token).maxAge(lifetime).build().toString();
  }

  String expire() {
    return base(NAME, "").maxAge(Duration.ZERO).build().toString();
  }

  String openChallenge(String token, Duration lifetime) {
    return base(CHALLENGE_NAME, token).maxAge(lifetime).build().toString();
  }

  String expireChallenge() {
    return base(CHALLENGE_NAME, "").maxAge(Duration.ZERO).build().toString();
  }

  // Secure só é desligado em desenvolvimento; IdentityProperties recusa false fora dele.
  private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
    return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Lax").path("/");
  }
}
