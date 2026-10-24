package com.felipe.ballast.categories;

import java.util.List;

// Única lista de sementes no código. A V6 (usuários que já existiam) e docs/contracts/dados.md
// repetem a mesma lista, e SeedParityTest trava a divergência. "Outros" é despesa e a receita
// genérica chama "Outras receitas" porque o nome é único por usuário (ADR-024). As três que o
// código procura carregam a marca da V24 (ADR-057).
final class CategorySeeds {

  // marker é o valor de categoria.semente, ou nulo para a semente que o código não procura.
  record Seed(String name, CategoryType type, String marker) {
    Seed(String name, CategoryType type) {
      this(name, type, null);
    }
  }

  static final List<Seed> ALL =
      List.of(
          new Seed("Salário", CategoryType.INCOME, "salario"),
          new Seed("Aluguel recebido", CategoryType.INCOME),
          new Seed("Benefício", CategoryType.INCOME),
          new Seed("Rendimento", CategoryType.INCOME),
          new Seed("Outras receitas", CategoryType.INCOME, "outras_receitas"),
          new Seed("Alimentação", CategoryType.EXPENSE),
          new Seed("Refeição", CategoryType.EXPENSE),
          new Seed("Mercado", CategoryType.EXPENSE),
          new Seed("Combustível", CategoryType.EXPENSE),
          new Seed("Transporte", CategoryType.EXPENSE),
          new Seed("Moradia", CategoryType.EXPENSE),
          new Seed("Saúde", CategoryType.EXPENSE),
          new Seed("Lazer", CategoryType.EXPENSE),
          new Seed("Assinatura", CategoryType.EXPENSE),
          new Seed("Educação", CategoryType.EXPENSE),
          new Seed("Outros", CategoryType.EXPENSE, "outros"));

  private CategorySeeds() {}
}
