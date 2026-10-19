package com.felipe.ballast.categories;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "categorias")
@RestController
class CategoryController {

  private static final ErrorResponse INVALID_TYPE_FILTER =
      new ErrorResponse("tipo deve ser receita ou despesa");

  private final CategoryRepository categories;

  CategoryController(CategoryRepository categories) {
    this.categories = categories;
  }

  @Operation(
      operationId = "getCategorias",
      summary = "Lista as categorias do usuário",
      description =
          "Ordenada por tipo (despesa antes de receita) e nome sem diferenciar caixa. Sem o"
              + " parâmetro, devolve os dois tipos.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Categorias do usuário",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = CategoryResponse.class))))
  @ApiResponse(
      responseCode = "400",
      description = "tipo com valor diferente de receita e despesa",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Banco indisponível",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping(path = "/categorias", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Schema(allowableValues = {"receita", "despesa"})
          @RequestParam(name = "tipo", required = false)
          String type) {
    // Lido como texto e comparado por igualdade exata, como o filtro de contas.
    List<Category> found;
    if (type == null) {
      found = categories.findAllByUser(user.id());
    } else if (type.equals(CategoryType.INCOME.value())
        || type.equals(CategoryType.EXPENSE.value())) {
      found = categories.findAllByUserAndType(user.id(), type);
    } else {
      return ResponseEntity.badRequest().body(INVALID_TYPE_FILTER);
    }
    return ResponseEntity.ok(found.stream().map(CategoryResponse::from).toList());
  }
}
