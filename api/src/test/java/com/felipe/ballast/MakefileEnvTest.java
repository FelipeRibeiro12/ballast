package com.felipe.ballast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// O .env tem segredo de desenvolvimento (senha do banco, chave do MFA). Só os alvos que sobem a
// aplicação o leem; make test e make lint não devem receber nada dele no ambiente.
class MakefileEnvTest {

  private static final String MARKER = "SEGREDO_DE_TESTE_DO_MAKEFILE";

  // make -pn imprime o banco de dados do make sem executar receita; variável que veio do .env
  // aparece nele com o valor.
  private static String databaseFor(Path dir, String target) throws Exception {
    Files.copy(Path.of("..", "Makefile"), dir.resolve("Makefile"));
    Files.writeString(dir.resolve(".env"), MARKER + "=valor-secreto\n", StandardCharsets.UTF_8);
    Process process =
        new ProcessBuilder("make", "-pn", target)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    process.waitFor();
    return output;
  }

  private static void requireMake() {
    try {
      assumeTrue(new ProcessBuilder("make", "--version").start().waitFor() == 0);
    } catch (Exception e) {
      assumeTrue(false, "make não está instalado");
    }
  }

  @ParameterizedTest(name = "make {0} não lê o .env")
  @ValueSource(strings = {"setup", "test", "test-api", "test-web", "lint", "lint-api", "build"})
  void targetsThatDoNotNeedTheEnvDoNotReadIt(String target, @TempDir Path dir) throws Exception {
    requireMake();

    assertThat(databaseFor(dir, target)).doesNotContain("valor-secreto");
  }

  @ParameterizedTest(name = "make {0} lê o .env")
  @ValueSource(strings = {"dev", "dev-api", "dev-web", "seed"})
  void targetsThatStartTheAppReadTheEnv(String target, @TempDir Path dir) throws Exception {
    requireMake();

    assertThat(databaseFor(dir, target)).contains("valor-secreto");
  }
}
