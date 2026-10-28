package com.felipe.ballast.categories;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

// Persistência apenas: nunca sai na API. nivel e pai_nivel (colunas geradas do banco) ficam de
// fora de propósito: o Hibernate não as cita no INSERT e nenhum código as lê. usuario_id é coluna
// simples, sem relação, para não carregar o usuário.
@Entity
@Table(name = "categoria")
class Category {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "usuario_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "nome", nullable = false)
  private String name;

  @Column(name = "tipo", nullable = false)
  private String type;

  @Column(name = "cor")
  private String color;

  @Column(name = "icone")
  private String icon;

  @Column(name = "semente")
  private String seed;

  @Column(name = "pai_id")
  private UUID parentId;

  protected Category() {}

  Category(UUID userId, String name, CategoryType type, String seed) {
    this.userId = userId;
    this.seed = seed;
    this.name = name;
    this.type = type.value();
  }

  UUID getId() {
    return id;
  }

  String getName() {
    return name;
  }

  CategoryType getType() {
    return CategoryType.fromValue(type);
  }

  String getColor() {
    return color;
  }

  String getIcon() {
    return icon;
  }

  boolean isProtected() {
    return "outros".equals(seed) || "outras_receitas".equals(seed);
  }

  UUID getParentId() {
    return parentId;
  }
}
