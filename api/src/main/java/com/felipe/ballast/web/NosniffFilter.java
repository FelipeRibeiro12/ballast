package com.felipe.ballast.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// O Spring Security já manda o cabeçalho, mas só nas respostas que passam pela cadeia dele. O 429
// do limite por IP e o 413 do corpo grande saem antes, então este filtro roda à frente de todos.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class NosniffFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    chain.doFilter(request, response);
  }
}
