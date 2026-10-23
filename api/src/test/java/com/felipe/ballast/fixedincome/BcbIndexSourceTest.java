package com.felipe.ballast.fixedincome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// Sem rede: o servidor simulado do Spring responde no lugar do Banco Central.
class BcbIndexSourceTest {

  private MockRestServiceServer server;
  private BcbIndexSource source;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bcb.gov.br");
    server = MockRestServiceServer.bindTo(builder).build();
    source = new BcbIndexSource(builder.build());
  }

  @Test
  void asksTheSgsSeriesWithBrazilianDatesAndReadsTheValuesAsText() {
    server
        .expect(
            requestTo(
                "https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados?formato=json"
                    + "&dataInicial=01%2F10%2F2026&dataFinal=05%2F10%2F2026"))
        .andRespond(
            withSuccess(
                "[{\"data\":\"01/10/2026\",\"valor\":\"0.050788\"},"
                    + "{\"data\":\"02/10/2026\",\"valor\":\"0.050788\"}]",
                MediaType.APPLICATION_JSON));

    List<IndexPoint> points =
        source.fetch(IndexSeries.CDI, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5));

    assertThat(points)
        .containsExactly(
            new IndexPoint(LocalDate.of(2026, 10, 1), new BigDecimal("0.050788")),
            new IndexPoint(LocalDate.of(2026, 10, 2), new BigDecimal("0.050788")));
    server.verify();
  }

  @Test
  void aWindowWithoutValuesComesAs404AndIsEmpty() {
    server.expect(requestTo(containsString("bcdata.sgs.433"))).andRespond(withResourceNotFound());

    assertThat(source.fetch(IndexSeries.IPCA, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5)))
        .isEmpty();
  }

  @Test
  void skipsAMalformedItemAndKeepsTheRest() {
    server
        .expect(requestTo(containsString("bcdata.sgs.433")))
        .andRespond(
            withSuccess(
                "[{\"data\":\"01/08/2026\",\"valor\":\"-0.32\"},"
                    + "{\"data\":\"2026-09-01\",\"valor\":\"0.82\"},"
                    + "{\"data\":\"01/10/2026\",\"valor\":\"abc\"}]",
                MediaType.APPLICATION_JSON));

    assertThat(source.fetch(IndexSeries.IPCA, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 5)))
        .containsExactly(new IndexPoint(LocalDate.of(2026, 8, 1), new BigDecimal("-0.32")));
  }

  @Test
  void skipsAValueOutsideThePlausibleRangeOfTheSeries() {
    server
        .expect(requestTo(containsString("bcdata.sgs.12")))
        .andRespond(
            withSuccess(
                "[{\"data\":\"01/10/2026\",\"valor\":\"5000\"},"
                    + "{\"data\":\"02/10/2026\",\"valor\":\"-0.01\"},"
                    + "{\"data\":\"05/10/2026\",\"valor\":\"0.05\"}]",
                MediaType.APPLICATION_JSON));

    assertThat(source.fetch(IndexSeries.CDI, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5)))
        .containsExactly(new IndexPoint(LocalDate.of(2026, 10, 5), new BigDecimal("0.05")));
  }

  @Test
  void aServerErrorIsThrownForTheUpdaterToLog() {
    server.expect(requestTo(containsString("bcdata.sgs.12"))).andRespond(withServerError());

    assertThatThrownBy(
            () ->
                source.fetch(IndexSeries.CDI, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5)))
        .isInstanceOf(RuntimeException.class);
  }
}
