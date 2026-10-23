package com.felipe.ballast.identity;

import jakarta.servlet.http.HttpServletRequest;

final class ClientAddress {

  private ClientAddress() {}

  // Par direto da conexão. No perfil producao o Tomcat troca pelo X-Forwarded-For que o Caddy
  // manda, e só confia em endereço interno (ADR-044, que fecha o achado B3 da auditoria da F1).
  static String of(HttpServletRequest request) {
    return request.getRemoteAddr();
  }
}
