package com.felipe.ballast.identity;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Atraso progressivo por e-mail digitado e IP, exista conta ou não (ADR-053). O ip chega já
// normalizado por ClientKey. A hora vem sempre do relógio da
// aplicação (parâmetro), nunca de now() do banco: gravar e comparar usam a mesma fonte, e é ela
// que o teste controla.
@Repository
class LoginAttempts {

  // Até 3 falhas seguidas sem atraso; da 4ª em diante, 2^(falhas-3) segundos.
  static final int FREE_FAILURES = 3;
  static final Duration MAX_DELAY = Duration.ofMinutes(15);

  // 2^10 s já passa do teto de 15 min; limitar o expoente antes de calcular evita o estouro.
  static final int MAX_EXPONENT = 10;

  record Delay(Duration remaining, int failures) {}

  private final JdbcClient jdbc;

  LoginAttempts(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static Duration delayAfter(int failures) {
    if (failures <= FREE_FAILURES) {
      return Duration.ZERO;
    }
    int exponent = failures - FREE_FAILURES;
    if (exponent >= MAX_EXPONENT) {
      return MAX_DELAY;
    }
    Duration delay = Duration.ofSeconds(1L << exponent);
    return delay.compareTo(MAX_DELAY) < 0 ? delay : MAX_DELAY;
  }

  // Um único UPSERT: ou a linha está fora do atraso e o incremento acontece (devolve a contagem
  // nova), ou está dentro e nada muda (vazio). É isso que faz tentativas simultâneas contarem uma
  // a uma. O WHERE repete delayAfter em SQL; os testes cobrem as duas pontas.
  OptionalInt reserve(String email, String ip, Instant now) {
    return jdbc.sql(
            """
            INSERT INTO tentativa_login (email, ip, falhas, ultima_falha_em) VALUES (:email, :ip, 1, :now)
            ON CONFLICT (email, ip) DO UPDATE
            SET falhas = tentativa_login.falhas + 1, ultima_falha_em = EXCLUDED.ultima_falha_em
            WHERE tentativa_login.falhas <= :free
               OR tentativa_login.ultima_falha_em
                  + make_interval(secs => least(power(2, least(tentativa_login.falhas - :free, :maxExponent)), :maxSeconds))
                  <= EXCLUDED.ultima_falha_em
            RETURNING falhas
            """)
        .param("email", email)
        .param("ip", ip)
        .param("now", utc(now))
        .param("free", FREE_FAILURES)
        .param("maxExponent", MAX_EXPONENT)
        .param("maxSeconds", (int) MAX_DELAY.toSeconds())
        .query(Integer.class)
        .optional()
        .map(OptionalInt::of)
        .orElseGet(OptionalInt::empty);
  }

  // Lido só depois de uma reserva recusada. O atraso pode ter vencido entre a recusa e esta
  // leitura; quem chama trata recusado como recusado.
  Optional<Delay> findDelay(String email, String ip, Instant now) {
    return jdbc.sql(
            "SELECT falhas, ultima_falha_em FROM tentativa_login WHERE email = :email AND ip = :ip")
        .param("email", email)
        .param("ip", ip)
        .query(
            (rs, row) -> {
              Instant lastFailure =
                  rs.getObject("ultima_falha_em", OffsetDateTime.class).toInstant();
              int failures = rs.getInt("falhas");
              Duration remaining = Duration.between(now, lastFailure.plus(delayAfter(failures)));
              return new Delay(remaining, failures);
            })
        .optional();
  }

  // Só a linha do IP do login que deu certo: a de outro endereço é de outra pessoa.
  void clear(String email, String ip) {
    jdbc.sql("DELETE FROM tentativa_login WHERE email = :email AND ip = :ip")
        .param("email", email)
        .param("ip", ip)
        .update();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
