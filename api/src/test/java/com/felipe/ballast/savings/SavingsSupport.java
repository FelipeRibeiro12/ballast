package com.felipe.ballast.savings;

import com.felipe.ballast.entries.BusinessDays;
import com.felipe.ballast.identity.TestUsers;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

// Monta o cenário por SQL, como o ProjectionFixture: o teste não depende das rotas de escrita.
final class SavingsSupport {

  record Actor(TestUsers.Registered user, Cookie session) {}

  record Yield(LocalDate date, BigDecimal amount, String title) {}

  // A janela de CDI dos testes: o indice é compartilhado entre as classes, então cada teste limpa
  // só estas datas.
  private static final LocalDate CDI_FROM = LocalDate.of(2026, 9, 1);
  private static final LocalDate CDI_TO = LocalDate.of(2026, 12, 31);

  private final MockMvc mvc;
  private final JdbcClient jdbc;

  SavingsSupport(MockMvc mvc, JdbcClient jdbc) {
    this.mvc = mvc;
    this.jdbc = jdbc;
  }

  Actor newActor() throws Exception {
    TestUsers users = new TestUsers(mvc);
    TestUsers.Registered user = users.register();
    return new Actor(user, users.session(user));
  }

  UUID pot(Actor actor, String initialBalance, String percent) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO conta (id, usuario_id, nome, tipo, saldo_inicial, percentual_cdi)
            VALUES (:id, :u, :nome, 'cofrinho', :initial, :percent)
            """)
        .param("id", id)
        .param("u", actor.user().id())
        .param("nome", "Cofrinho " + id)
        .param("initial", new BigDecimal(initialBalance))
        .param("percent", percent == null ? null : new BigDecimal(percent))
        .update();
    return id;
  }

  UUID checking(Actor actor, String initialBalance) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO conta (id, usuario_id, nome, tipo, saldo_inicial)
            VALUES (:id, :u, :nome, 'corrente', :initial)
            """)
        .param("id", id)
        .param("u", actor.user().id())
        .param("nome", "Conta " + id)
        .param("initial", new BigDecimal(initialBalance))
        .update();
    return id;
  }

  // Um CDI por dia útil em [from, to], o mesmo valor diário (em % ao dia, como o SGS publica).
  void publishCdi(LocalDate from, LocalDate to, String daily) {
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      if (BusinessDays.isBusinessDay(day)) {
        publishCdi(day, daily);
      }
    }
  }

  void publishCdi(LocalDate day, String daily) {
    jdbc.sql(
            "INSERT INTO indice (serie, data, valor) VALUES ('cdi', :d, :v)"
                + " ON CONFLICT (serie, data) DO UPDATE SET valor = EXCLUDED.valor")
        .param("d", day)
        .param("v", new BigDecimal(daily))
        .update();
  }

  void clearCdi() {
    jdbc.sql("DELETE FROM indice WHERE serie = 'cdi' AND data BETWEEN :a AND :b")
        .param("a", CDI_FROM)
        .param("b", CDI_TO)
        .update();
  }

  void realized(Actor actor, UUID account, String type, String amount, LocalDate day) {
    jdbc.sql(
            """
            INSERT INTO lancamento (id, usuario_id, tipo, titulo, categoria_id, conta_id, valor,
                                    data_competencia, data_caixa, status)
            SELECT :id, :u, :type, 'teste', c.id, :account, :amount, :d, :d, 'realizado'
            FROM categoria c WHERE c.usuario_id = :u AND c.tipo = :type AND c.nome <> 'Rendimento'
            ORDER BY c.id LIMIT 1
            """)
        .param("id", UUID.randomUUID())
        .param("u", actor.user().id())
        .param("type", type)
        .param("account", account)
        .param("amount", new BigDecimal(amount))
        .param("d", day)
        .update();
  }

  void marker(Actor actor, UUID account, LocalDate day) {
    jdbc.sql(
            "INSERT INTO rendimento_cofrinho (id, usuario_id, conta_id, data) VALUES (:id, :u, :c,"
                + " :d)")
        .param("id", UUID.randomUUID())
        .param("u", actor.user().id())
        .param("c", account)
        .param("d", day)
        .update();
  }

  long markers(UUID account) {
    return jdbc.sql("SELECT count(*) FROM rendimento_cofrinho WHERE conta_id = :c")
        .param("c", account)
        .query(Long.class)
        .single();
  }

  long markersWithoutEntry(UUID account) {
    return jdbc.sql(
            "SELECT count(*) FROM rendimento_cofrinho WHERE conta_id = :c AND lancamento_id IS NULL")
        .param("c", account)
        .query(Long.class)
        .single();
  }

  // Os lançamentos de rendimento da conta, do dia mais antigo para o mais novo.
  List<Yield> yields(UUID account) {
    return jdbc.sql(
            """
            SELECT l.data_caixa, l.valor, l.titulo FROM lancamento l
            JOIN categoria c ON c.id = l.categoria_id AND c.usuario_id = l.usuario_id
            WHERE l.conta_id = :a AND c.nome = 'Rendimento' ORDER BY l.data_caixa
            """)
        .param("a", account)
        .query(
            (rs, row) ->
                new Yield(
                    rs.getObject("data_caixa", LocalDate.class),
                    rs.getBigDecimal("valor"),
                    rs.getString("titulo")))
        .list();
  }

  // Independente do CdiYield: saldo x ((1 + CDI x %)^dias - 1), com CDI diário igual em todos os
  // dias da janela, cortado nos centavos para baixo.
  static BigDecimal expectedYield(
      String balance, String percent, String dailyCdi, int businessDays) {
    BigDecimal rate =
        new BigDecimal(dailyCdi)
            .divide(new BigDecimal("100"))
            .multiply(new BigDecimal(percent).divide(new BigDecimal("100")));
    BigDecimal factor = BigDecimal.ONE.add(rate).pow(businessDays);
    return new BigDecimal(balance)
        .multiply(factor.subtract(BigDecimal.ONE))
        .setScale(2, java.math.RoundingMode.DOWN);
  }
}
