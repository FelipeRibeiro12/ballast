package com.felipe.ballast.web;

import java.io.IOException;
import java.io.Writer;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.http.HttpStatus;

// O Tomcat recusa certos caminhos (barra codificada, chave, barra vertical) antes de o Spring
// existir, e responde com a página HTML dele, que mostra versão e texto interno. Esta válvula
// troca a página pelo mesmo JSON de erro do resto da API. Instanciada pelo Tomcat pelo nome da
// classe, por isso pública e com construtor vazio.
public class JsonErrorReportValve extends ErrorReportValve {

  @Override
  protected void report(Request request, Response response, Throwable throwable) {
    int status = response.getStatus();
    if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
      return;
    }
    try {
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      Writer writer = response.getReporter();
      if (writer != null) {
        writer.write(
            "{\"erro\":\"" + ApiExceptionHandler.messageFor(HttpStatus.valueOf(status)) + "\"}");
        response.finishResponse();
      }
    } catch (IOException | IllegalStateException e) {
      // Cliente já foi embora ou a resposta já foi fechada: não há o que reportar.
    }
  }
}
