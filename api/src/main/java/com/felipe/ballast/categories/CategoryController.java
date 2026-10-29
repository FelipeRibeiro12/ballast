package com.felipe.ballast.categories;

import com.felipe.ballast.identity.AuthenticatedUser;
import com.felipe.ballast.web.CanonicalUuid;
import com.felipe.ballast.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// O dono vem sempre da sessão (AuthenticatedUser), nunca de corpo, query ou cabeçalho.
@Tag(name = "categorias")
@RestController
class CategoryController {

  private static final Logger log = LoggerFactory.getLogger(CategoryController.class);

  private static final ErrorResponse INVALID_TYPE_FILTER =
      new ErrorResponse("tipo deve ser receita ou despesa");

  private static final ErrorResponse NOT_FOUND = new ErrorResponse("categoria não encontrada");
  private static final ErrorResponse PROTECTED =
      new ErrorResponse("Outros e Outras receitas não podem ser alteradas nem apagadas");
  private static final ErrorResponse DUPLICATE =
      new ErrorResponse("já existe categoria com esse nome");
  private static final ErrorResponse LISTED =
      new ErrorResponse("categoria está na lista de uma conta de benefício");
  private static final ErrorResponse CHANGED =
      new ErrorResponse("a categoria mudou enquanto era apagada; tente de novo");
  private static final ErrorResponse HAS_CHILDREN =
      new ErrorResponse("categoria tem subcategorias");

  private final CategoryRepository categories;
  private final CategoryService service;

  CategoryController(CategoryRepository categories, CategoryService service) {
    this.categories = categories;
    this.service = service;
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

  @Operation(
      operationId = "postCategorias",
      summary = "Cria uma categoria",
      description = "Até 20 por usuário, sem contar Outros e Outras receitas (ADR-064).",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Categoria criada",
      content = @Content(schema = @Schema(implementation = CategoryResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Nome repetido ou limite de categorias atingido",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping(
      path = "/categorias",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CategoryRequest request) {
    CategoryService.Result result = service.create(user.id(), request.name(), request.type());
    if (result instanceof CategoryService.Result.Done done) {
      log.info("categoria criada usuario_id={} categoria_id={}", user.id(), done.id());
      return ResponseEntity.status(HttpStatus.CREATED).body(find(user, done.id()));
    }
    return failure(result);
  }

  @Operation(
      operationId = "putCategoriasById",
      summary = "Renomeia a categoria",
      description = "Só o nome muda. Outros e Outras receitas não podem ser renomeadas.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(
      responseCode = "200",
      description = "Categoria renomeada",
      content = @Content(schema = @Schema(implementation = CategoryResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Entrada inválida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Categoria de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Nome repetido ou categoria protegida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PutMapping(
      path = "/categorias/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> rename(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable("id") String id,
      @Valid @RequestBody CategoryRenameRequest request) {
    Optional<UUID> categoryId = CanonicalUuid.parse(id);
    if (categoryId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    CategoryService.Result result = service.rename(user.id(), categoryId.get(), request.name());
    if (result instanceof CategoryService.Result.Done) {
      return ResponseEntity.ok(find(user, categoryId.get()));
    }
    return failure(result);
  }

  @Operation(
      operationId = "deleteCategoriasById",
      summary = "Apaga a categoria",
      description =
          "Lançamentos e recorrências vão para Outros (ou Outras receitas) e o teto de orçamento"
              + " soma na Outros. Categoria listada em conta de benefício não se apaga.",
      security = @SecurityRequirement(name = "cookieAuth"))
  @ApiResponse(responseCode = "204", description = "Categoria apagada")
  @ApiResponse(
      responseCode = "401",
      description = "Sem sessão válida",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Categoria de outro usuário, inexistente ou id que não é UUID; mesmo corpo",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Protegida, com subcategorias ou listada em conta de benefício",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping(path = "/categorias/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> delete(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") String id) {
    Optional<UUID> categoryId = CanonicalUuid.parse(id);
    if (categoryId.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
    CategoryService.Result result = service.delete(user.id(), categoryId.get());
    if (result instanceof CategoryService.Result.Done) {
      log.info("categoria apagada usuario_id={} categoria_id={}", user.id(), categoryId.get());
      return ResponseEntity.noContent().build();
    }
    return failure(result);
  }

  private CategoryResponse find(AuthenticatedUser user, UUID id) {
    return categories.findAllByUser(user.id()).stream()
        .filter(category -> category.getId().equals(id))
        .findFirst()
        .map(CategoryResponse::from)
        .orElseThrow();
  }

  private static ResponseEntity<?> failure(CategoryService.Result result) {
    return switch (result) {
      case CategoryService.Result.NotFound ignored ->
          ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
      case CategoryService.Result.Protected ignored ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(PROTECTED);
      case CategoryService.Result.DuplicateName ignored ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(DUPLICATE);
      case CategoryService.Result.Listed ignored ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(LISTED);
      case CategoryService.Result.HasChildren ignored ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(HAS_CHILDREN);
      case CategoryService.Result.Changed ignored ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(CHANGED);
      case CategoryService.Result.Done ignored -> throw new IllegalStateException();
    };
  }
}
