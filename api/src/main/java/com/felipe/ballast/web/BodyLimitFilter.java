package com.felipe.ballast.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// O Spring MVC lê corpo JSON sem teto. Em toda rota o maior DTO válido (em /auth/*: e-mail 254 +
// senha 128 + nome 100 caracteres, cada um no pior caso escapado em 6 bytes) cabe em ~3 KB;
// 4 KiB dá folga para espaço e formatação e continua pequeno para quem quer esgotar memória.
// O envio de extrato (POST /importacoes, F22; CSV e prévia, F23) tem exceção própria de 1 MiB, e
// o PDF de fatura (F26) de 5 MiB; nenhuma outra rota tem.
// Sem o cabeçalho Content-Length (chunked) o tamanho só aparece lendo, então a leitura é contada
// e aborta no estouro, sem juntar o corpo na memória.
@Component
// Logo depois do RateLimitFilter: requisição já recusada por limite nem chega a ter o corpo medido.
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class BodyLimitFilter extends OncePerRequestFilter {

  static final int BODY_LIMIT_BYTES = 4096;
  static final int UPLOAD_LIMIT_BYTES = 1 << 20;
  static final int PDF_LIMIT_BYTES = 5 << 20;
  private static final String PDF_PATH = "/importacoes/pdf";
  private static final Set<String> UPLOAD_PATHS =
      Set.of("/importacoes", "/importacoes/csv", "/importacoes/csv/previa");
  static final String MESSAGE = "{\"erro\":\"corpo grande demais\"}";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    int limit = limitFor(request);
    if (request.getContentLengthLong() > limit) {
      reject(response);
      return;
    }
    chain.doFilter(new LimitedRequest(request, limit), response);
  }

  // Caminho exato e só POST: /importacoes/{id}/... e as outras rotas seguem em 4 KiB. Os envios
  // de CSV (F23) e a prévia têm o mesmo teto do OFX.
  private static int limitFor(HttpServletRequest request) {
    if (!"POST".equals(request.getMethod())) {
      return BODY_LIMIT_BYTES;
    }
    String uri = request.getRequestURI();
    if (PDF_PATH.equals(uri)) {
      return PDF_LIMIT_BYTES;
    }
    return UPLOAD_PATHS.contains(uri) ? UPLOAD_LIMIT_BYTES : BODY_LIMIT_BYTES;
  }

  private static void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    // O corpo não será lido; fechar a conexão evita o servidor engolir o resto do upload.
    response.setHeader("Connection", "close");
    response.getWriter().write(MESSAGE);
  }

  private static final class LimitedRequest extends HttpServletRequestWrapper {

    private final int limit;
    private ServletInputStream stream;

    LimitedRequest(HttpServletRequest request, int limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        stream = new LimitedStream(super.getInputStream(), limit);
      }
      return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class LimitedStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final int limit;
    private long consumed;

    LimitedStream(ServletInputStream delegate, int limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    private void count(long bytes) throws IOException {
      if (bytes > 0) {
        consumed += bytes;
        if (consumed > limit) {
          throw new RequestBodyTooLargeException(limit);
        }
      }
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value >= 0) {
        count(1);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
      // Pede no máximo o que falta (mais 1, para detectar o estouro): o servidor nunca lê muito
      // além do limite.
      int allowed = (int) Math.min(len, (long) limit - consumed + 1);
      int read = delegate.read(buffer, off, allowed);
      count(read);
      return read;
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }
  }
}
