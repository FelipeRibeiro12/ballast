package com.felipe.ballast.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.felipe.ballast.TestcontainersConfiguration;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

  @Autowired MockMvc mvc;
  @Autowired DataSource dataSource;

  // Sem teto de socket, uma conexão presa num banco congelado nunca volta ao pool.
  @Test
  void poolConnectionsHaveASocketTimeoutOfThirtySeconds() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getNetworkTimeout()).isEqualTo(30_000);
    }
  }

  @Test
  void returnsSeededMessageFromDatabase() throws Exception {
    mvc.perform(get("/healthz"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("{\"mensagem\":\"ok\"}", true));
  }

  @Test
  void rejectsClientThatOnlyAcceptsOtherMediaType() throws Exception {
    mvc.perform(get("/healthz").accept(MediaType.TEXT_HTML)).andExpect(status().isNotAcceptable());
  }
}
