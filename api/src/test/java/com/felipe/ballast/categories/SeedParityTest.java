package com.felipe.ballast.categories;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// A lista de sementes existe em três lugares: o código, a V6 (usuários antigos) e dados.md
// (a fonte). Aqui as três precisam dizer o mesmo, na mesma ordem.
class SeedParityTest {

  // O surefire roda com o diretório de trabalho em api/.
  private static final Path DATA_DOC = Path.of("..", "docs", "contracts", "dados.md");

  private static List<String> codeSeeds() {
    return CategorySeeds.ALL.stream().map(seed -> seed.type().value() + ":" + seed.name()).toList();
  }

  private static List<String> migrationSeeds() throws IOException {
    String sql =
        new ClassPathResource("db/migration/V6__cria_categoria_e_lancamento.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    // Só o bloco VALUES: os CHECKs da mesma migration também têm pares ('receita', 'despesa').
    int start = sql.indexOf("FROM (VALUES");
    int end = sql.indexOf(") AS lista");
    assertThat(start).isPositive();
    assertThat(end).isGreaterThan(start);
    Matcher matcher =
        Pattern.compile("\\('([^']+)', '(receita|despesa)'\\)").matcher(sql.substring(start, end));
    List<String> seeds = new ArrayList<>();
    while (matcher.find()) {
      seeds.add(matcher.group(2) + ":" + matcher.group(1));
    }
    return seeds;
  }

  private static List<String> documentedSeeds() throws IOException {
    String doc = Files.readString(DATA_DOC, StandardCharsets.UTF_8);
    int start = doc.indexOf("- Receita: ");
    int end = doc.indexOf("\n\nSão 16");
    assertThat(start).isPositive();
    assertThat(end).isGreaterThan(start);
    String[] parts = doc.substring(start, end).split("- Despesa: ");
    List<String> seeds = new ArrayList<>();
    addNames(seeds, "receita", parts[0].replace("- Receita: ", ""));
    addNames(seeds, "despesa", parts[1]);
    return seeds;
  }

  private static void addNames(List<String> seeds, String type, String list) {
    for (String name : list.replace("\n", " ").split(",")) {
      seeds.add(type + ":" + name.strip());
    }
  }

  @Test
  void theCodeListHasSixteenSeeds() {
    assertThat(codeSeeds()).hasSize(16).doesNotHaveDuplicates();
  }

  @Test
  void theCodeListMatchesTheMigration() throws IOException {
    assertThat(codeSeeds()).containsExactlyElementsOf(migrationSeeds());
  }

  @Test
  void theCodeListMatchesTheDocumentedOne() throws IOException {
    assertThat(codeSeeds()).containsExactlyElementsOf(documentedSeeds());
  }
}
