package com.felipe.ballast.wallets;

import com.fasterxml.jackson.annotation.JsonValue;
import com.felipe.ballast.crypto.CryptoNetwork;
import java.util.Optional;

// As redes que a F24 lê (ADR-071). Valor igual ao do CHECK carteira_observada_rede_valida (V36).
// Rede nova entra aqui junto com a migration que alarga o CHECK.
public enum WalletNetwork {
  ETHEREUM("ethereum", CryptoNetwork.ETHEREUM, Family.EVM, "ETH", "Ether", 18, "eth-mainnet"),
  BASE("base", CryptoNetwork.BASE, Family.EVM, "ETH", "Ether", 18, "base-mainnet"),
  ARBITRUM("arbitrum", CryptoNetwork.ARBITRUM, Family.EVM, "ETH", "Ether", 18, "arb-mainnet"),
  POLYGON("polygon", CryptoNetwork.POLYGON, Family.EVM, "POL", "POL", 18, "polygon-mainnet"),
  AVALANCHE(
      "avalanche", CryptoNetwork.AVALANCHE, Family.EVM, "AVAX", "Avalanche", 18, "avax-mainnet"),
  ROBINHOOD(
      "robinhood", CryptoNetwork.ROBINHOOD, Family.EVM, "ETH", "Ether", 18, "robinhood-mainnet"),
  SOLANA("solana", CryptoNetwork.SOLANA, Family.SOLANA, "SOL", "Solana", 9, "solana-mainnet"),
  // O Bitcoin não passa pela Alchemy: saldo por endereço pede um índice que o nó dela não tem.
  // Lê pela API Esplora pública (ADR-080).
  BITCOIN("bitcoin", CryptoNetwork.BITCOIN, Family.BITCOIN, "BTC", "Bitcoin", 8, null);

  enum Family {
    EVM,
    SOLANA,
    BITCOIN
  }

  private final String value;
  private final CryptoNetwork asset;
  private final Family family;
  private final String nativeSymbol;
  private final String nativeName;
  private final int nativeDecimals;
  private final String alchemyHost;

  WalletNetwork(
      String value,
      CryptoNetwork asset,
      Family family,
      String nativeSymbol,
      String nativeName,
      int nativeDecimals,
      String alchemyHost) {
    this.value = value;
    this.asset = asset;
    this.family = family;
    this.nativeSymbol = nativeSymbol;
    this.nativeName = nativeName;
    this.nativeDecimals = nativeDecimals;
    this.alchemyHost = alchemyHost;
  }

  @JsonValue
  public String value() {
    return value;
  }

  // A rede do ativo no banco: a identidade dele é rede mais contrato.
  CryptoNetwork asset() {
    return asset;
  }

  boolean isEvm() {
    return family == Family.EVM;
  }

  boolean isBitcoin() {
    return family == Family.BITCOIN;
  }

  // Raiz da Alchemy para a rede, sem a chave; nulo no Bitcoin.
  String defaultUrl() {
    return alchemyHost == null ? null : "https://" + alchemyHost + ".g.alchemy.com";
  }

  String nativeSymbol() {
    return nativeSymbol;
  }

  String nativeName() {
    return nativeName;
  }

  int nativeDecimals() {
    return nativeDecimals;
  }

  static Optional<WalletNetwork> fromValue(String value) {
    for (WalletNetwork network : values()) {
      if (network.value.equals(value)) {
        return Optional.of(network);
      }
    }
    return Optional.empty();
  }
}
