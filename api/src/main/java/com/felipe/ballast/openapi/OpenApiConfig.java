package com.felipe.ballast.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
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
                        .name("__Host-ballast_sessao")));
  }

  // O springdoc gera "type: null" junto do $ref para referência anulável, e em 3.1 isso nega o
  // objeto. A forma certa é anyOf com o $ref e o tipo nulo.
  @Bean
  OpenApiCustomizer nullableReferences() {
    return openApi -> {
      makeNullable(openApi, "Recorrencia", "divisao", "DivisaoRecorrencia");
      makeNullable(openApi, "RecorrenciaRequest", "divisao", "DivisaoRequest");
      makeNullable(openApi, "Conta", "cartao", "Cartao");
    };
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void makeNullable(OpenAPI openApi, String schema, String property, String target) {
    Schema<?> parent = openApi.getComponents().getSchemas().get(schema);
    Schema original = (Schema) parent.getProperties().get(property);
    Schema reference = new Schema<>();
    reference.set$ref("#/components/schemas/" + target);
    Schema nullType = new Schema<>();
    nullType.setTypes(Set.of("null"));
    Schema replaced = new Schema<>();
    replaced.setDescription(original.getDescription());
    replaced.setAnyOf(List.of(reference, nullType));
    parent.getProperties().put(property, replaced);
  }
}
