package com.felipe.ballast.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.felipe.ballast.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// A cadeia de segurança já põe o cabeçalho; aqui ficam as respostas que saem antes dela.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NosniffTest {

  @Autowired MockMvc mvc;

  @Test
  void bodyTooLargeAnswerCarriesNosniff() throws Exception {
    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + "a".repeat(5000) + "\"}"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  @Test
  void healthzAndUnknownRoutesStillCarryIt() throws Exception {
    mvc.perform(get("/healthz")).andExpect(header().string("X-Content-Type-Options", "nosniff"));
    mvc.perform(get("/nao-existe")).andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }
}
