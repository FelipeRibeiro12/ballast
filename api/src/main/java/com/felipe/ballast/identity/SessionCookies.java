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

  private final boolean secure;

  SessionCookies(IdentityProperties properties) {
    this.secure = properties.isCookieSecure();
  }

  // Cookie repetido é tratado como sem sessão, qualquer que seja a ordem: escolher um dos valores
  // deixaria quem injeta um cookie no host derrubar a sessão da vítima ou escolher a que vale.
  static Optional<String> tokenOf(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    String token = null;
    for (Cookie cookie : cookies) {
      if (!NAME.equals(cookie.getName())) {
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
    return base(token).maxAge(lifetime).build().toString();
  }

  String expire() {
    return base("").maxAge(Duration.ZERO).build().toString();
  }

  // Secure só é desligado em desenvolvimento; IdentityProperties recusa false fora dele.
  private ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Lax").path("/");
  }
}
