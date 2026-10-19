package com.felipe.ballast.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

// Persistência apenas: nunca sai na API. Só escrita; a leitura (com saldo) é SQL em
// AccountQueries. usuario_id é coluna simples, sem relação, para não carregar o usuário. moeda e
// criada_em ficam de fora: o banco preenche pelo DEFAULT.
@Entity
@Table(name = "conta")
class Account {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "usuario_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "nome", nullable = false)
  private String name;

  @Column(name = "tipo", nullable = false)
  private String type;

  @Column(name = "saldo_inicial", nullable = false, precision = 18, scale = 2, updatable = false)
  private BigDecimal initialBalance;

  @Column(name = "arquivada", nullable = false)
  private boolean archived;

  protected Account() {}

  Account(UUID userId, String name, AccountType type, BigDecimal initialBalance) {
    this.userId = userId;
    this.name = name;
    this.type = type.value();
    this.initialBalance = initialBalance;
  }

  UUID getId() {
    return id;
  }
}
