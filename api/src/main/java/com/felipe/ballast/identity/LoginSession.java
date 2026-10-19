package com.felipe.ballast.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

// Só insere. Leitura e revogação são SQL direto (SessionQueries), pelo hash do token.
@Entity
@Table(name = "sessao")
class LoginSession {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "usuario_id", nullable = false)
  private UserAccount user;

  @Column(name = "token_hash", nullable = false)
  private byte[] tokenHash;

  @Column(name = "expira_em", nullable = false)
  private Instant expiresAt;

  protected LoginSession() {}

  LoginSession(UserAccount user, byte[] tokenHash, Instant expiresAt) {
    this.user = user;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }
}
