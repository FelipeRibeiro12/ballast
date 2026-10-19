package com.felipe.ballast.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.felipe.ballast.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// O web gera o cliente TS de api/openapi.yaml. Este teste é o gate do ADR-019:
// a spec commitada tem que ser a que o código gera hoje.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTest {

  private static final String UPDATE_PROPERTY = "spec.atualizar";

  // O surefire roda com o diretório de trabalho em api/.
  private static final Path COMMITTED_SPEC = Path.of("openapi.yaml");

  @Autowired MockMvc mvc;

  @Test
  void committedSpecMatchesTheOneGeneratedFromCode() throws Exception {
    String generated =
        mvc.perform(get("/v3/api-docs.yaml"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    if (Boolean.getBoolean(UPDATE_PROPERTY)) {
      Files.writeString(COMMITTED_SPEC, generated, StandardCharsets.UTF_8);
      return;
    }

    String committed =
        Files.exists(COMMITTED_SPEC) ? Files.readString(COMMITTED_SPEC, StandardCharsets.UTF_8) : "";

    assertThat(generated)
        .withFailMessage(
            "api/openapi.yaml diverge da spec gerada do código. Regenere, dentro de api/, com:"
                + " ./mvnw test -Dtest=OpenApiSpecTest -Dspec.atualizar=true"
                + " e depois rode: pnpm --dir web generate:api")
        .isEqualTo(committed);
  }

  @Test
  void generatedSpecIsDeterministicAcrossRequests() throws Exception {
    String first = fetchSpec();
    String second = fetchSpec();

    assertThat(second).isEqualTo(first);
  }

  private String fetchSpec() throws Exception {
    return mvc.perform(get("/v3/api-docs.yaml"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }
}
