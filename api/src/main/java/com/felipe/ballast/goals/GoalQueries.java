package com.felipe.ballast.goals;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// SQL direto (ADR-017). Toda consulta filtra por usuario_id; a FK composta da V19 garante que o
// aporte é de meta do mesmo usuário.
@Repository
class GoalQueries {

  record Fields(String title, BigDecimal target, LocalDate targetDate, BigDecimal monthlyPlan) {}

  // saved é a soma de todos os aportes; recent, a dos três meses fechados antes do atual.
  record Goal(
      UUID id,
      String title,
      BigDecimal target,
      LocalDate targetDate,
      BigDecimal monthlyPlan,
      BigDecimal saved,
      BigDecimal recent) {}

  record Deposit(UUID id, BigDecimal value, LocalDate date, UUID transferId) {}

  private static final String GOAL_COLUMNS =
      """
      SELECT m.id, m.titulo, m.valor_alvo, m.data_alvo, m.aporte_mensal,
        COALESCE(SUM(a.valor), 0) AS acumulado,
        COALESCE(SUM(a.valor) FILTER (
          WHERE a.data >= :paceStart AND a.data < :currentStart), 0) AS recente
      FROM meta m
      LEFT JOIN aporte_meta a ON a.usuario_id = m.usuario_id AND a.meta_id = m.id
      """;

  private final JdbcClient jdbc;

  GoalQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // currentStart é o dia 1 do mês atual; o ritmo usa os três meses antes dele.
  List<Goal> list(UUID userId, LocalDate currentStart) {
    return jdbc.sql(
            GOAL_COLUMNS
                + " WHERE m.usuario_id = :userId GROUP BY m.id ORDER BY lower(m.titulo), m.id")
        .param("userId", userId)
        .param("currentStart", currentStart)
        .param("paceStart", currentStart.minusMonths(3))
        .query(GoalQueries::mapGoal)
        .list();
  }

  Optional<Goal> find(UUID userId, UUID id, LocalDate currentStart) {
    return jdbc.sql(GOAL_COLUMNS + " WHERE m.usuario_id = :userId AND m.id = :id GROUP BY m.id")
        .param("userId", userId)
        .param("id", id)
        .param("currentStart", currentStart)
        .param("paceStart", currentStart.minusMonths(3))
        .query(GoalQueries::mapGoal)
        .optional();
  }

  // Trava a meta até o fim da transação: dois aportes na mesma meta conferem o acumulado um de
  // cada vez. Devolve o acumulado, vazio para meta alheia ou inexistente.
  Optional<BigDecimal> lockAndSum(UUID userId, UUID id) {
    boolean found =
        jdbc.sql("SELECT 1 FROM meta WHERE usuario_id = :userId AND id = :id FOR UPDATE")
            .param("userId", userId)
            .param("id", id)
            .query(Integer.class)
            .optional()
            .isPresent();
    if (!found) {
      return Optional.empty();
    }
    return Optional.of(
        jdbc.sql(
                "SELECT COALESCE(SUM(valor), 0) FROM aporte_meta"
                    + " WHERE usuario_id = :userId AND meta_id = :id")
            .param("userId", userId)
            .param("id", id)
            .query(BigDecimal.class)
            .single());
  }

  UUID insert(UUID userId, Fields fields) {
    UUID id = newId();
    jdbc.sql(
            """
            INSERT INTO meta (id, usuario_id, titulo, valor_alvo, data_alvo, aporte_mensal)
            VALUES (:id, :userId, :title, :target, :targetDate, :plan)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("title", fields.title())
        .param("target", fields.target())
        .param("targetDate", fields.targetDate())
        .param("plan", fields.monthlyPlan())
        .update();
    return id;
  }

  int update(UUID userId, UUID id, Fields fields) {
    return jdbc.sql(
            """
            UPDATE meta SET titulo = :title, valor_alvo = :target, data_alvo = :targetDate,
              aporte_mensal = :plan
            WHERE usuario_id = :userId AND id = :id
            """)
        .param("title", fields.title())
        .param("target", fields.target())
        .param("targetDate", fields.targetDate())
        .param("plan", fields.monthlyPlan())
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  int delete(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM meta WHERE usuario_id = :userId AND id = :id")
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  // transferId nulo é aporte sem vínculo. A FK composta da V27 recusa transferência de outro
  // usuário; a conferência do serviço só dá a mensagem.
  void insertDeposit(UUID userId, UUID goalId, BigDecimal value, LocalDate date, UUID transferId) {
    jdbc.sql(
            """
            INSERT INTO aporte_meta (id, usuario_id, meta_id, valor, data, transferencia_id)
            VALUES (:id, :userId, :goalId, :value, :date, :transferId)
            """)
        .param("transferId", transferId, java.sql.Types.OTHER)
        .param("id", newId())
        .param("userId", userId)
        .param("goalId", goalId)
        .param("value", value)
        .param("date", date)
        .update();
  }

  // Do mais novo ao mais antigo; no mesmo dia, o último gravado primeiro.
  List<Deposit> deposits(UUID userId, UUID goalId) {
    return jdbc.sql(
            "SELECT id, valor, data, transferencia_id FROM aporte_meta WHERE usuario_id = :userId"
                + " AND meta_id = :goalId ORDER BY data DESC, criado_em DESC, id DESC")
        .param("userId", userId)
        .param("goalId", goalId)
        .query(
            (rs, row) ->
                new Deposit(
                    rs.getObject("id", UUID.class),
                    rs.getBigDecimal("valor"),
                    rs.getObject("data", LocalDate.class),
                    rs.getObject("transferencia_id", UUID.class)))
        .list();
  }

  boolean transferExists(UUID userId, UUID transferId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM transferencia WHERE usuario_id = :userId AND id = :id)")
        .param("userId", userId)
        .param("id", transferId)
        .query(Boolean.class)
        .single();
  }

  boolean transferIsLinked(UUID userId, UUID transferId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM aporte_meta WHERE usuario_id = :userId"
                + " AND transferencia_id = :id)")
        .param("userId", userId)
        .param("id", transferId)
        .query(Boolean.class)
        .single();
  }

  Optional<BigDecimal> depositValue(UUID userId, UUID goalId, UUID id) {
    return jdbc.sql(
            "SELECT valor FROM aporte_meta"
                + " WHERE usuario_id = :userId AND meta_id = :goalId AND id = :id")
        .param("userId", userId)
        .param("goalId", goalId)
        .param("id", id)
        .query(BigDecimal.class)
        .optional();
  }

  int deleteDeposit(UUID userId, UUID goalId, UUID id) {
    return jdbc.sql(
            "DELETE FROM aporte_meta"
                + " WHERE usuario_id = :userId AND meta_id = :goalId AND id = :id")
        .param("userId", userId)
        .param("goalId", goalId)
        .param("id", id)
        .update();
  }

  private static Goal mapGoal(ResultSet rs, int row) throws SQLException {
    return new Goal(
        rs.getObject("id", UUID.class),
        rs.getString("titulo"),
        rs.getBigDecimal("valor_alvo"),
        rs.getObject("data_alvo", LocalDate.class),
        rs.getBigDecimal("aporte_mensal"),
        rs.getBigDecimal("acumulado"),
        rs.getBigDecimal("recente"));
  }

  private static UUID newId() {
    // O gerador do Hibernate não usa a sessão; é o mesmo dos @UuidGenerator das entidades.
    return UuidVersion7Strategy.INSTANCE.generateUuid(null);
  }
}
