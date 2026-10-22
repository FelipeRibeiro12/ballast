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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

// API pública do SGS, sem chave: /dados/serie/bcdata.sgs.{n}/dados?formato=json&dataInicial=...
// Cada item vem como {"data": "dd/MM/aaaa", "valor": "0.050788"}, o valor em texto.
@Component
class BcbIndexSource implements IndexSource {

  private static final Logger log = LoggerFactory.getLogger(BcbIndexSource.class);

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");

  // numeric(12,8) da V14: até 9999,99999999. Fora disso o dado veio errado e não entra.
  private static final BigDecimal LIMIT = new BigDecimal("10000");

  // O Banco Central fora não pode prender a thread do job nem a requisição de quem cadastra.
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final RestClient client;

  @Autowired
  BcbIndexSource(IndicesProperties properties) {
    this(withTimeouts(RestClient.builder().baseUrl(properties.baseUrl())).build());
  }

  // O teste entrega um cliente ligado ao servidor simulado do Spring.
  BcbIndexSource(RestClient client) {
    this.client = client;
  }

  private static RestClient.Builder withTimeouts(RestClient.Builder builder) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    factory.setReadTimeout(TIMEOUT);
    return builder.requestFactory(factory);
  }

  @Override
  public List<IndexPoint> fetch(IndexSeries series, LocalDate from, LocalDate to) {
    List<Map<String, Object>> body;
    try {
      body =
          client
              .get()
              .uri(
                  "/dados/serie/bcdata.sgs.{code}/dados?formato=json&dataInicial={from}"
                      + "&dataFinal={to}",
                  series.sgsCode(),
                  from.format(DATE),
                  to.format(DATE))
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});
    } catch (HttpClientErrorException e) {
      // O SGS responde 404 quando a janela não tem nenhum valor (fim de semana, mês sem IPCA).
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        return List.of();
      }
      throw e;
    }
    List<IndexPoint> points = new ArrayList<>();
    for (Map<String, Object> item : body == null ? List.<Map<String, Object>>of() : body) {
      try {
        LocalDate date = LocalDate.parse(String.valueOf(item.get("data")), DATE);
        BigDecimal value = new BigDecimal(String.valueOf(item.get("valor")));
        if (value.abs().compareTo(LIMIT) < 0) {
          points.add(new IndexPoint(date, value));
        }
      } catch (DateTimeParseException | NumberFormatException e) {
        log.warn("valor do banco central ignorado: serie={} formato inesperado", series.value());
      }
    }
    return points;
  }
}
