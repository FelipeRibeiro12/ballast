package com.felipe.ballast.identity;

import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
class SessionCookies {

  static final String NAME = "ballast_sessao";

  private final boolean secure;

  SessionCookies(IdentityProperties properties) {
    this.secure = properties.isCookieSecure();
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
