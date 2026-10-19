package com.felipe.ballast.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

// Persistência apenas: nunca sai na API. criado_em fica de fora porque nenhum código o lê.
@Entity
@Table(name = "usuario")
class UserAccount {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "email", nullable = false)
  private String email;

  @Column(name = "senha_hash", nullable = false)
  private String passwordHash;

  @Column(name = "nome")
  private String name;

  protected UserAccount() {}

  UserAccount(String email, String passwordHash, String name) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.name = name;
  }

  UUID getId() {
    return id;
  }

  String getEmail() {
    return email;
  }

  String getPasswordHash() {
    return passwordHash;
  }

  String getName() {
    return name;
  }
}
