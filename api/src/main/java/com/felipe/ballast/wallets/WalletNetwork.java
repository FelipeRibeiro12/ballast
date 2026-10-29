package com.felipe.ballast.wallets;

import com.fasterxml.jackson.annotation.JsonValue;
import com.felipe.ballast.crypto.CryptoNetwork;
import java.util.Optional;

// As redes que a F24 lê (ADR-071). Valor igual ao do CHECK carteira_observada_rede_valida (V36).
// Rede nova entra aqui junto com a migration que alarga o CHECK.
public enum WalletNetwork {
  ETHEREUM("ethereum", CryptoNetwork.ETHEREUM, true, "ETH", "Ether", 18),
  BASE("base", CryptoNetwork.BASE, true, "ETH", "Ether", 18),
  SOLANA("solana", CryptoNetwork.SOLANA, false, "SOL", "Solana", 9);

  private final String value;
  private final CryptoNetwork asset;
  private final boolean evm;
  private final String nativeSymbol;
  private final String nativeName;
  private final int nativeDecimals;

  WalletNetwork(
      String value,
      CryptoNetwork asset,
      boolean evm,
      String nativeSymbol,
      String nativeName,
      int nativeDecimals) {
    this.value = value;
    this.asset = asset;
    this.evm = evm;
    this.nativeSymbol = nativeSymbol;
    this.nativeName = nativeName;
    this.nativeDecimals = nativeDecimals;
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
    return evm;
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
