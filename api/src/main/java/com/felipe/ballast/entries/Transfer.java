package com.felipe.ballast.entries;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

// Persistência apenas, só escrita; a leitura é SQL em TransferQueries. A coluna tipo da tabela é
// gerada pelo banco e fica fora do mapeamento: a aplicação não escreve nela.
@Entity
@Table(name = "transferencia")
class Transfer {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "usuario_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "saida_id", nullable = false)
  private UUID outEntryId;

  @Column(name = "entrada_id", nullable = false)
  private UUID inEntryId;

  @Column(name = "conta_saida_id", nullable = false)
  private UUID outAccountId;

  @Column(name = "conta_entrada_id", nullable = false)
  private UUID inAccountId;

  protected Transfer() {}

  Transfer(UUID userId, UUID outEntryId, UUID inEntryId, UUID outAccountId, UUID inAccountId) {
    this.userId = userId;
    this.outEntryId = outEntryId;
    this.inEntryId = inEntryId;
    this.outAccountId = outAccountId;
    this.inAccountId = inAccountId;
  }

  UUID getId() {
    return id;
  }
}
