package com.felipe.ballast.entries;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

// Persistência apenas: nunca sai na API. Só escrita; a leitura é SQL em EntryQueries. As FKs são
// colunas simples, sem relação, para não carregar conta, categoria nem usuário. status e criado_em
// ficam de fora: o banco preenche pelo DEFAULT.
@Entity
@Table(name = "lancamento")
class Entry {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "usuario_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "tipo", nullable = false)
  private String type;

  @Column(name = "titulo", nullable = false)
  private String title;

  @Column(name = "descricao")
  private String description;

  // Nulo só na perna de transferência (CHECK lancamento_categoria_conforme_tipo).
  @Column(name = "categoria_id")
  private UUID categoryId;

  @Column(name = "conta_id", nullable = false)
  private UUID accountId;

  @Column(name = "valor", nullable = false, precision = 18, scale = 2)
  private BigDecimal amount;

  @Column(name = "data_competencia", nullable = false)
  private LocalDate competenceDate;

  @Column(name = "data_caixa", nullable = false)
  private LocalDate cashDate;

  protected Entry() {}

  // A F3 grava a mesma data nas duas colunas; elas só divergem com o cartão.
  Entry(UUID userId, EntryInput input) {
    this.userId = userId;
    this.type = input.type().value();
    this.title = input.title();
    this.description = input.description();
    this.categoryId = input.categoryId();
    this.accountId = input.accountId();
    this.amount = input.amount();
    this.competenceDate = input.date();
    this.cashDate = input.date();
  }

  UUID getId() {
    return id;
  }
}
