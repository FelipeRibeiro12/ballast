package com.felipe.ballast.accounts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.felipe.ballast.identity.CodePointSize;
import com.felipe.ballast.identity.NoControlCharacters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

@Schema(name = "CriarContaRequest")
public record CreateAccountRequest(
    @JsonProperty("nome")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100)
        @NotNull(message = NAME_MESSAGE)
        @CodePointSize(min = 1, max = 100, message = NAME_MESSAGE)
        @NoControlCharacters(message = NAME_MESSAGE)
        String name,
    @JsonProperty("tipo")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = TYPE_MESSAGE)
        AccountType type,
    @JsonProperty("saldoInicialCentavos")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            defaultValue = "0",
            minimum = "-9007199254740991",
            maximum = "9007199254740991")
        @Min(value = -MAX_CENTS, message = BALANCE_MESSAGE)
        @Max(value = MAX_CENTS, message = BALANCE_MESSAGE)
        Long initialBalanceCents,
    @JsonProperty("cartao")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description = "Obrigatório quando tipo é cartao_credito e proibido nos outros tipos")
        @Valid
        CardRequest card,
    @JsonProperty("categoriasPermitidas")
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description =
                "Só para tipo beneficio: ids de categorias de despesa que a conta aceita. Ausente"
                    + " ou vazia aceita qualquer categoria. Proibido nos outros tipos")
        @Size(max = PermittedCategories.MAX, message = PermittedCategories.SIZE_MESSAGE)
        List<String> permittedCategories) {

  // Number.MAX_SAFE_INTEGER: acima disso o JavaScript perde o centavo (ADR-023).
  static final long MAX_CENTS = 9_007_199_254_740_991L;

  static final String NAME_MESSAGE =
      "nome deve ter entre 1 e 100 caracteres e não pode ter caracteres de controle";
  static final String TYPE_MESSAGE =
      "tipo deve ser corrente, poupanca, dinheiro, cartao_credito, beneficio, cripto, investimento ou cofrinho";
  static final String BALANCE_MESSAGE =
      "saldoInicialCentavos deve ser um inteiro entre -9007199254740991 e 9007199254740991";

  // O CHECK do banco recusa espaço nas pontas: tirar aqui evita 500. Ausente vale zero.
  public CreateAccountRequest {
    name = name == null ? null : name.strip();
    initialBalanceCents = initialBalanceCents == null ? 0L : initialBalanceCents;
  }

  // Centavos inteiros entram como numeric(18,2) exato, sem passar por double.
  BigDecimal initialBalance() {
    return BigDecimal.valueOf(initialBalanceCents, 2);
  }
}
