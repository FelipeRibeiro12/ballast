package com.felipe.ballast.identity;

import jakarta.servlet.http.HttpServletRequest;

final class ClientAddress {

  private ClientAddress() {}

  // Par direto da conexão. Atrás de proxy passa a ser o IP do proxy, e ler X-Forwarded-For
  // exige decidir quais proxies são confiáveis (achado B3 da auditoria da F1).
  static String of(HttpServletRequest request) {
    return request.getRemoteAddr();
  }
}
