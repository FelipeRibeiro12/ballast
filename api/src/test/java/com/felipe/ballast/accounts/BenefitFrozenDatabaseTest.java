package com.felipe.ballast.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import com.felipe.ballast.TestcontainersConfiguration;
import com.felipe.ballast.identity.TestUsers;
import com.github.dockerjava.api.DockerClient;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Container próprio, como em AccountsFrozenDatabaseTest: a primeira chamada depois do
// congelamento responde 503 em cerca de 2 s, também no PUT da lista de categorias.
@SpringBootTest(
    properties = {
      "ballast.registration-open=true",
      "ballast.password.memory-kib=8",
      "ballast.password.iterations=1",
      "ballast.password.parallelism=1"
    })
@AutoConfigureMockMvc
@Testcontainers
class BenefitFrozenDatabaseTest {

  @Container static PostgreSQLContainer postgres = TestcontainersConfiguration.newPostgres();

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    TestcontainersConfiguration.registerProperties(registry, postgres);
  }

  @Autowired MockMvc mvc;

  @Test
  @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void putOfTheListAnswers503WithinAboutTwoSecondsWhileTheDatabaseIsFrozen() throws Exception {
    TestUsers users = new TestUsers(mvc);
    Cookie session = users.session(users.register());
    BenefitApi api = new BenefitApi(mvc);
    String account = api.createOk(session, "VR", List.of());
    String food = api.categoryId(session, "Alimentação");
    DockerClient docker = DockerClientFactory.instance().client();
    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    try {
      long start = System.nanoTime();
      MockHttpServletResponse response = api.putIds(session, account, List.of(food));
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getContentAsString(StandardCharsets.UTF_8))
          .isEqualTo("{\"erro\":\"serviço indisponível\"}");
      assertThat(elapsed).isLessThan(Duration.ofMillis(2500));
    } finally {
      docker.unpauseContainerCmd(postgres.getContainerId()).exec();
    }
  }
}
