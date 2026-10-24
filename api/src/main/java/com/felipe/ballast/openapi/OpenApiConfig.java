package com.felipe.ballast.openapi;

import com.felipe.ballast.web.IdempotencyFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
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

  // Documenta o cabeçalho nas rotas que o filtro de idempotência atende (ADR-056). A lista vem
  // do próprio filtro, para a spec não divergir do que o servidor faz.
  @Bean
  OpenApiCustomizer idempotencyKeyHeader() {
    return openApi ->
        IdempotencyFilter.ROUTES.forEach(
            path -> {
              PathItem item = openApi.getPaths().get(path);
              if (item == null || item.getPost() == null) {
                return;
              }
              Operation post = item.getPost();
              post.addParametersItem(
                  new HeaderParameter()
                      .name(IdempotencyFilter.KEY_HEADER)
                      .required(false)
                      .description(
                          "Chave de idempotência, de 1 a 80 caracteres entre letras, dígitos e"
                              + " . _ : -. Mesma chave, usuário, rota e corpo devolve a resposta"
                              + " 2xx original (cabeçalho Idempotent-Replayed: true) sem gravar de"
                              + " novo; vale por 24 h.")
                      .schema(new StringSchema().minLength(1).maxLength(80)));
              addResponseIfAbsent(
                  post,
                  "409",
                  "Chave com outra requisição igual ainda em andamento; tentar de novo");
              addResponseIfAbsent(
                  post, "422", "Idempotency-Key já usada com outra rota ou outro corpo");
            });
  }

  private static void addResponseIfAbsent(Operation operation, String code, String description) {
    if (operation.getResponses().containsKey(code)) {
      return;
    }
    operation
        .getResponses()
        .addApiResponse(
            code,
            new ApiResponse()
                .description(description)
                .content(
                    new Content()
                        .addMediaType(
                            "application/json",
                            new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/Erro")))));
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
