package com.felipe.ballast.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(name = "ImportacaoLinha")
public record ImportLineResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Posição da transação no arquivo, a partir de 1",
            minimum = "1")
        int ordem,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            type = "string",
            format = "date")
        LocalDate data,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            allowableValues = {"receita", "despesa"},
            description = "Nulo só em linha inválida")
        String tipo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Centavos de real, sempre positivo; o sinal vem do tipo. Nulo em linha inválida",
            minimum = "1",
            maximum = "9007199254740991")
        Long valorCentavos,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        String titulo,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 500)
        String descricao,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean valida,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Por que a linha é inválida; texto fixo, sem trecho do arquivo")
        String erro,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"importar", "ignorar", "conciliar"})
        String proposta,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            allowableValues = {"ja_importada", "invalida", "duplicada_no_arquivo"},
            description = "Por que a proposta é ignorar; nulo nas outras")
        String motivo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            allowableValues = {"importar", "ignorar", "conciliar"},
            description = "Escolha do usuário; nulo enquanto vale a proposta")
        String decisao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"importar", "ignorar", "conciliar"},
            description = "Ação vigente: a decisão, ou a proposta quando não há decisão")
        String acao,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Categoria com que a linha vira lançamento: a escolhida, ou Outros / Outras"
                    + " receitas. Nulo em linha inválida")
        UUID categoriaId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Lançamento existente com que a linha concilia; depois da confirmação, o"
                    + " lançamento criado")
        UUID lancamentoId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            allowableValues = {"criada", "conciliada", "ignorada", "duplicada"},
            description = "O que a confirmação fez com a linha; nulo antes dela")
        String resultado) {

  // Única conversão para centavos, na borda de saída (CLAUDE.md).
  static ImportLineResponse from(ImportQueries.LineRow row) {
    String action = row.decision() != null ? row.decision() : row.proposal();
    return new ImportLineResponse(
        row.id(),
        row.order(),
        row.date(),
        row.type(),
        row.amount() == null ? null : row.amount().movePointRight(2).longValueExact(),
        row.title(),
        row.description(),
        row.invalidReason() == null,
        row.invalidReason(),
        row.proposal(),
        row.reason(),
        row.decision(),
        action,
        row.type() == null
            ? null
            : row.categoryId() != null ? row.categoryId() : row.defaultCategoryId(),
        row.entryId(),
        row.result());
  }
}
