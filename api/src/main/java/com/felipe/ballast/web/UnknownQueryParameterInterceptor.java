package com.felipe.ballast.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

// Um parâmetro que a rota não declara não chega a nenhum controller, então o Spring o ignora e a
// listagem sai sem o filtro que o chamador achou que aplicou (?contaid= no lugar de ?contaId=).
// Vale para todo handler do app: a lista de nomes vem dos @RequestParam do próprio método, sem
// manter uma tabela paralela. Handlers de outras bibliotecas (springdoc) ficam de fora.
@Component
class UnknownQueryParameterInterceptor implements HandlerInterceptor {

  private static final String APP_PACKAGE = "com.felipe.ballast.";
  private static final DefaultParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws ServletRequestBindingException {
    String query = request.getQueryString();
    if (query == null
        || !(handler instanceof HandlerMethod method)
        || !method.getBeanType().getName().startsWith(APP_PACKAGE)) {
      return true;
    }
    Set<String> declared = declaredNames(method);
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      String rawName = equals < 0 ? pair : pair.substring(0, equals);
      if (!declared.contains(decode(rawName))) {
        // Mensagem fixa: o nome enviado não volta ao cliente nem vai para o log.
        throw new ServletRequestBindingException("parâmetro de consulta desconhecido");
      }
    }
    return true;
  }

  private static Set<String> declaredNames(HandlerMethod method) {
    Set<String> names = new HashSet<>();
    for (MethodParameter parameter : method.getMethodParameters()) {
      RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
      if (annotation == null) {
        continue;
      }
      String name = annotation.name();
      if (name.isEmpty()) {
        parameter.initParameterNameDiscovery(NAMES);
        name = parameter.getParameterName();
      }
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  // Escape malformado nunca casa com nome declarado.
  private static String decode(String rawName) {
    try {
      return URLDecoder.decode(rawName, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return "\u0000invalido";
    }
  }
}
