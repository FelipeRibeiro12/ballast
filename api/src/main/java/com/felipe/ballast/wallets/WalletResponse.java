package com.felipe.ballast.wallets;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "Carteira")
public record WalletResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contaId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WalletNetwork rede,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "EVM em minúsculas; Solana como foi cadastrado")
        String endereco,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, maxLength = 60)
        String rotulo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Prova de posse por assinatura (F25); nulo se nunca provou")
        Instant verificadaEm,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Última leitura que deu certo; nulo se nunca leu")
        Instant sincronizadaEm,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "A última tentativa de leitura falhou: os saldos são os de sincronizadaEm, não zero")
        boolean leituraFalhou,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description =
                "Por que a última leitura falhou; nulo se deu certo ou nunca foi tentada."
                    + " Em POST /carteiras/{id}/sincronizar barrado pelos 60 s, vale LIMITE_DE_60S"
                    + " quando não há falha gravada")
        ReadingReason motivo,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Última tentativa de leitura, boa ou ruim; nulo se nunca tentou")
        Instant ultimaTentativaEm,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "Quando uma nova leitura manual é aceita; nulo se já pode")
        Instant proximaLeituraEm,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "O servidor tem chave do indexador para a rede desta carteira. Falso: nenhuma"
                    + " leitura acontece até o dono configurar ALCHEMY_API_KEY")
        boolean indexadorConfigurado,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "O servidor lê as carteiras sozinho de 6 em 6 horas")
        boolean sincronizacaoAutomatica,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Balance> saldos,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "NFT e posição DeFi da última leitura; vazio sem chave da Zerion")
        List<Position> posicoes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "O servidor tem ZERION_API_KEY; falso deixa NFT e DeFi desligados")
        boolean posicoesConfiguradas) {

  @Schema(name = "PosicaoCarteira")
  public record Position(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              allowableValues = {"nft", "defi"})
          String tipo,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              allowableValues = {
                "nft",
                "liquidez",
                "stake",
                "deposito",
                "emprestimo",
                "rendimento",
                "bloqueado",
                "recompensa",
                "outro"
              })
          String categoria,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description = "Protocolo (DeFi) ou coleção (NFT)")
          String protocolo,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Texto de terceiro, limpo")
          String nome,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String detalhe,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description = "Em texto; nulo em pool de liquidez, que tem vários tokens")
          String quantidade,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              description =
                  "Centavos de dólar inteiros; negativo em empréstimo; nulo se o provedor não sabe")
          Long valorUsdCentavos) {}

  @Schema(name = "SaldoCarteira")
  public record Balance(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ativoId,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Texto do indexador, limpo")
          String simbolo,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String contrato,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Em texto, sem zero à direita",
              example = "0.12345678")
          String quantidade,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Falso fica fora do patrimônio até o usuário promover o ativo")
          boolean confiavel,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "Falta informar o custo de alguma entrada ou saída deste saldo")
          boolean custoPendente) {}

  static WalletResponse from(WalletService.View view) {
    WalletQueries.Wallet wallet = view.wallet();
    return new WalletResponse(
        wallet.id(),
        wallet.accountId(),
        wallet.network(),
        wallet.address(),
        wallet.label(),
        wallet.verifiedAt(),
        wallet.syncedAt(),
        wallet.lastError() != null,
        motivoOf(view),
        wallet.attemptedAt(),
        view.nextReadAt(),
        view.configured(),
        view.automatic(),
        view.balances().stream()
            .map(
                balance ->
                    new Balance(
                        balance.assetId(),
                        balance.symbol(),
                        balance.contract(),
                        balance.quantity().stripTrailingZeros().toPlainString(),
                        balance.trusted(),
                        balance.costPending()))
            .toList(),
        view.positions().stream()
            .map(
                row ->
                    new Position(
                        row.id(),
                        row.kind(),
                        row.category(),
                        row.protocol(),
                        row.name(),
                        row.detail(),
                        row.quantity() == null
                            ? null
                            : row.quantity().stripTrailingZeros().toPlainString(),
                        row.valueUsdCents()))
            .toList(),
        view.positionsConfigured());
  }

  private static ReadingReason motivoOf(WalletService.View view) {
    return ReadingReason.fromCode(view.wallet().lastError())
        .orElse(view.throttled() ? ReadingReason.LIMITE_DE_60S : null);
  }
}
