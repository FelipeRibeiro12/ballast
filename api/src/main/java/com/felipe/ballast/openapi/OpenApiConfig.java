package com.felipe.ballast.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI ballastOpenApi() {
    return new OpenAPI()
        .info(new Info().title("Ballast API").version("0.1.0"))
        // Sem isto o springdoc preenche servers com host e porta da requisição, e a
        // spec mudaria a cada porta aleatória de teste.
        .servers(List.of(new Server().url("/")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "cookieAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("ballast_sessao")));
  }
}
