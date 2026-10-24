package com.felipe.ballast.fixedincome;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// API pública do SGS, sem chave: /dados/serie/bcdata.sgs.{n}/dados?formato=json&dataInicial=...
// Cada item vem como {"data": "dd/MM/aaaa", "valor": "0.050788"}, o valor em texto.
@Component
class BcbIndexSource implements IndexSource {

  private static final Logger log = LoggerFactory.getLogger(BcbIndexSource.class);

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");

  // O Banco Central fora não pode prender a thread do job.
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  // O timeout acima vale por leitura: um servidor que pinga um byte por vez passaria dele. O prazo
  // total e o teto de bytes limitam a busca inteira (ADR-058). 5 anos de CDI diário têm uns 150 KB.
  private static final Duration DEADLINE = Duration.ofSeconds(15);
  private static final int MAX_BYTES = 2 * 1024 * 1024;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  // Uma thread virtual por busca, para poder interromper a que passou do prazo.
  private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();

  private final RestClient client;
  private final Duration deadline;
  private final int maxBytes;

  @Autowired
  BcbIndexSource(IndicesProperties properties) {
    this(
        withTimeouts(RestClient.builder().baseUrl(properties.baseUrl())).build(),
        DEADLINE,
        MAX_BYTES);
  }

  // O teste entrega um cliente ligado ao servidor simulado do Spring.
  BcbIndexSource(RestClient client) {
    this(client, DEADLINE, MAX_BYTES);
  }

  BcbIndexSource(RestClient client, Duration deadline, int maxBytes) {
    this.client = client;
    this.deadline = deadline;
    this.maxBytes = maxBytes;
  }

  private static RestClient.Builder withTimeouts(RestClient.Builder builder) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    factory.setReadTimeout(TIMEOUT);
    return builder.requestFactory(factory);
  }

  @Override
  public List<IndexPoint> fetch(IndexSeries series, LocalDate from, LocalDate to) {
    byte[] raw = downloadWithinDeadline(series, from, to);
    List<Map<String, Object>> body =
        raw.length == 0 ? List.of() : JSON.readValue(raw, new TypeReference<>() {});

    List<IndexPoint> points = new ArrayList<>();
    for (Map<String, Object> item : body) {
      try {
        LocalDate date = LocalDate.parse(String.valueOf(item.get("data")), DATE);
        BigDecimal value = new BigDecimal(String.valueOf(item.get("valor")));
        if (series.plausible(value)) {
          points.add(new IndexPoint(date, value));
        } else {
          log.warn("valor do banco central fora da faixa ignorado: serie={}", series.value());
        }
      } catch (DateTimeParseException | NumberFormatException e) {
        log.warn("valor do banco central ignorado: serie={} formato inesperado", series.value());
      }
    }
    return points;
  }

  private byte[] downloadWithinDeadline(IndexSeries series, LocalDate from, LocalDate to) {
    Future<byte[]> download = READERS.submit(() -> download(series, from, to));
    try {
      return download.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      download.cancel(true);
      throw new IllegalStateException("banco central passou do prazo");
    } catch (InterruptedException e) {
      download.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("busca ao banco central interrompida");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("banco central não respondeu");
    }
  }

  // Vazio quando a janela não tem valor: o SGS responde 404 (fim de semana, mês sem IPCA). A
  // mensagem do erro não leva a URL nem o corpo da resposta.
  private byte[] download(IndexSeries series, LocalDate from, LocalDate to) {
    return client
        .get()
        .uri(
            "/dados/serie/bcdata.sgs.{code}/dados?formato=json&dataInicial={from}&dataFinal={to}",
            series.sgsCode(),
            from.format(DATE),
            to.format(DATE))
        .exchange(
            (request, response) -> {
              if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                return new byte[0];
              }
              if (response.getStatusCode().isError()) {
                throw new IllegalStateException(
                    "banco central respondeu " + response.getStatusCode().value());
              }
              // Um byte a mais que o teto: passou dele, a resposta é descartada sem ler o resto.
              byte[] read = response.getBody().readNBytes(maxBytes + 1);
              if (read.length > maxBytes) {
                throw new IllegalStateException("resposta do banco central maior que o teto");
              }
              return read;
            });
  }
}
