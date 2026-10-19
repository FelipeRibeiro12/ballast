package com.felipe.ballast.categories;

import java.util.List;

// Única lista de sementes no código. A V6 (usuários que já existiam) e docs/contracts/dados.md
// repetem a mesma lista, e SeedParityTest trava a divergência. "Outros" é despesa e a receita
// genérica chama "Outras receitas" porque o nome é único por usuário (ADR-024).
final class CategorySeeds {

  record Seed(String name, CategoryType type) {}

  static final List<Seed> ALL =
      List.of(
          new Seed("Salário", CategoryType.INCOME),
          new Seed("Aluguel recebido", CategoryType.INCOME),
          new Seed("Benefício", CategoryType.INCOME),
          new Seed("Rendimento", CategoryType.INCOME),
          new Seed("Outras receitas", CategoryType.INCOME),
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
          new Seed("Outros", CategoryType.EXPENSE));

  private CategorySeeds() {}
}
