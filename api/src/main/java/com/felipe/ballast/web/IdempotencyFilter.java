package com.felipe.ballast.web;

import com.felipe.ballast.identity.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

// Idempotency-Key nas escritas de dinheiro (ADR-056). Roda depois da autorização, porque o
// escopo da chave é o usuário da sessão. Não é @Component de propósito: o Boot o registraria
// também como filtro de servlet, fora da cadeia (mesmo caso do SessionAuthenticationFilter).
// A escrita em si roda como sempre, nas transações dos serviços; o filtro só guarda a resposta 2xx.
public class IdempotencyFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

  public static final String KEY_HEADER = "Idempotency-Key";
  static final String REPLAYED_HEADER = "Idempotent-Replayed";

  private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,80}");

  // Rotas que gravam valor. O resto do POST (cadastro, MFA, cotação manual) ignora o cabeçalho.
  public static final List<String> ROUTES =
      List.of(
          "/contas",
          "/lancamentos",
          "/transferencias",
          "/compras-parceladas",
          "/recorrencias",
          "/metas",
          "/metas/{id}/aportes",
          "/movimentos-cripto",
          "/titulos-renda-fixa",
          "/titulos-renda-fixa/{id}/marcacoes",
          "/titulos-renda-fixa/{id}/resgate");

  // Cabe na coluna rota (200). Caminho maior que isto não é de recurso válido: o controller
  // responderia 404 e nada seria gravado.
  private static final int MAX_ROUTE_LENGTH = 200;

  // Resposta maior que isto não é guardada; nenhuma rota atual chega perto.
  private static final int MAX_STORED_RESPONSE_BYTES = 1 << 20;

  // Quanto uma requisição espera a outra, de mesma chave, terminar. Passado o prazo responde 409
  // e o cliente tenta de novo.
  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final long POLL_MILLIS = 50;

  private static final ErrorResponse INVALID_KEY = new ErrorResponse("Idempotency-Key inválida");
  private static final ErrorResponse REUSED =
      new ErrorResponse("Idempotency-Key já usada com outra requisição");
  private static final ErrorResponse IN_PROGRESS =
      new ErrorResponse("requisição com esta Idempotency-Key ainda em andamento");
  private static final ErrorResponse UNAVAILABLE = new ErrorResponse("serviço indisponível");
  private static final ErrorResponse INTERNAL = new ErrorResponse("erro interno");
  private static final ErrorResponse TOO_LARGE = new ErrorResponse("corpo grande demais");

  private final IdempotencyStore store;
  private final Clock clock;
  private final List<RequestMatcher> routes;

  public IdempotencyFilter(JdbcClient jdbc, Clock clock) {
    this.store = new IdempotencyStore(jdbc);
    this.clock = clock;
    PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
    this.routes =
        ROUTES.stream().<RequestMatcher>map(path -> paths.matcher(HttpMethod.POST, path)).toList();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getHeader(KEY_HEADER) == null
        || request.getRequestURI().length() > MAX_ROUTE_LENGTH
        || routes.stream().noneMatch(route -> route.matches(request));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication != null
        && authentication.getPrincipal() instanceof AuthenticatedUser user)) {
      chain.doFilter(request, response);
      return;
    }
    Optional<String> key = validKey(request);
    if (key.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_KEY);
      return;
    }
    byte[] body;
    try {
      body = request.getInputStream().readAllBytes();
    } catch (RequestBodyTooLargeException e) {
      writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, TOO_LARGE);
      return;
    }
    try {
      handle(user.id(), key.get(), request, response, chain, body);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE);
    } catch (DataAccessResourceFailureException e) {
      log.error("idempotência: banco indisponível", e);
      writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE);
    } catch (DataAccessException e) {
      UnexpectedErrorLog.error(log, "idempotência: falha ao consultar a chave", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL);
    }
  }

  private void handle(
      UUID userId,
      String key,
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain chain,
      byte[] body)
      throws ServletException, IOException, InterruptedException {
    String route = request.getRequestURI();
    String bodyHash = sha256Hex(body);
    Instant deadline = clock.instant().plus(WAIT);
    while (true) {
      Optional<OffsetDateTime> owner = store.claim(userId, key, route, bodyHash);
      if (owner.isPresent()) {
        execute(userId, key, owner.get(), request, response, chain, body);
        return;
      }
      Optional<IdempotencyStore.Stored> existing = store.find(userId, key);
      if (existing.isEmpty()) {
        // A dona desistiu entre as duas consultas: a vaga abriu.
        continue;
      }
      IdempotencyStore.Stored stored = existing.get();
      if (!sameRequest(stored, route, bodyHash)) {
        writeError(response, 422, REUSED);
        return;
      }
      if (stored.isDone()) {
        replay(userId, stored, response);
        return;
      }
      Optional<OffsetDateTime> taken = store.takeOver(userId, key, stored.createdAt());
      if (taken.isPresent()) {
        log.warn("idempotência: chave em andamento abandonada, assumida usuario_id={}", userId);
        execute(userId, key, taken.get(), request, response, chain, body);
        return;
      }
      if (!clock.instant().isBefore(deadline)) {
        response.setHeader("Retry-After", "1");
        writeError(response, HttpServletResponse.SC_CONFLICT, IN_PROGRESS);
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
  }

  private void execute(
      UUID userId,
      String key,
      OffsetDateTime owner,
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain chain,
      byte[] body)
      throws ServletException, IOException {
    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    boolean finished = false;
    try {
      chain.doFilter(new CachedBodyRequest(request, body), wrapper);
      int status = wrapper.getStatus();
      byte[] content = wrapper.getContentAsByteArray();
      if (status >= 200 && status < 300 && content.length <= MAX_STORED_RESPONSE_BYTES) {
        finished =
            store.finish(userId, key, owner, status, new String(content, StandardCharsets.UTF_8));
      }
    } catch (DataAccessException e) {
      // A escrita já respondeu; o que falhou foi só guardar. A linha em andamento envelhece e o
      // próximo pedido com a chave a assume.
      UnexpectedErrorLog.error(log, "idempotência: falha ao guardar a resposta", e);
    } finally {
      if (!finished) {
        abandon(userId, key, owner);
      }
      wrapper.copyBodyToResponse();
    }
  }

  private void abandon(UUID userId, String key, OffsetDateTime owner) {
    try {
      store.abandon(userId, key, owner);
    } catch (DataAccessException e) {
      UnexpectedErrorLog.error(log, "idempotência: falha ao liberar a chave", e);
    }
  }

  private static void replay(
      UUID userId, IdempotencyStore.Stored stored, HttpServletResponse response)
      throws IOException {
    log.info("idempotência: resposta repetida usuario_id={}", userId);
    response.setStatus(stored.status());
    response.setHeader(REPLAYED_HEADER, "true");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getOutputStream().write(stored.responseBody().getBytes(StandardCharsets.UTF_8));
  }

  // Hash comparado em tempo constante, como todo hash do app.
  private static boolean sameRequest(
      IdempotencyStore.Stored stored, String route, String bodyHash) {
    return stored.route().equals(route)
        && MessageDigest.isEqual(
            stored.bodyHash().getBytes(StandardCharsets.UTF_8),
            bodyHash.getBytes(StandardCharsets.UTF_8));
  }

  // Dois cabeçalhos, ou um fora do formato, são recusados: escolher um deles seria adivinhar.
  private static Optional<String> validKey(HttpServletRequest request) {
    List<String> values = Collections.list(request.getHeaders(KEY_HEADER));
    if (values.size() != 1 || !KEY_FORMAT.matcher(values.get(0)).matches()) {
      return Optional.empty();
    }
    return Optional.of(values.get(0));
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 sempre existe na JVM", e);
    }
  }

  private static void writeError(HttpServletResponse response, int status, ErrorResponse error)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write("{\"erro\":\"" + error.erro() + "\"}");
  }

  // Devolve o corpo já lido à cadeia, que o lê como se fosse o original.
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream source = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return source.read();
        }

        @Override
        public int read(byte[] buffer, int off, int len) {
          return source.read(buffer, off, len);
        }

        @Override
        public boolean isFinished() {
          return source.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("leitura assíncrona não é usada");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }
}
