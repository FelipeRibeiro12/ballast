package com.felipe.ballast.crypto;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.regex.Pattern;

// O valor do JSON é o mesmo do CHECK ativo_cripto_rede_valida (V13). Rede nova entra aqui junto
// com a migration que alarga o CHECK.
public enum CryptoNetwork {
  BITCOIN("bitcoin", null),
  ETHEREUM("ethereum", Format.EVM),
  BASE("base", Format.EVM),
  ARBITRUM("arbitrum", Format.EVM),
  POLYGON("polygon", Format.EVM),
  SOLANA("solana", Format.SOLANA);

  // Base58 não tem 0, O, I e l.
  private enum Format {
    EVM(Pattern.compile("0x[0-9a-fA-F]{40}")),
    SOLANA(Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,44}"));

    private final Pattern pattern;

    Format(Pattern pattern) {
      this.pattern = pattern;
    }
  }

  private final String value;
  private final Format contractFormat;

  CryptoNetwork(String value, Format contractFormat) {
    this.value = value;
    this.contractFormat = contractFormat;
  }

  @JsonValue
  public String value() {
    return value;
  }

  // Contrato na forma que o banco guarda, ou nulo quando não serve para a rede. O checksum EIP-55
  // é caixa mista; a V13 guarda em minúsculas para o unique não deixar o mesmo token entrar duas
  // vezes.
  String normalizeContract(String contract) {
    if (contractFormat == null || !contractFormat.pattern.matcher(contract).matches()) {
      return null;
    }
    return contractFormat == Format.EVM ? contract.toLowerCase(Locale.ROOT) : contract;
  }

  static CryptoNetwork fromValue(String value) {
    for (CryptoNetwork network : values()) {
      if (network.value.equals(value)) {
        return network;
      }
    }
    throw new IllegalStateException("rede desconhecida no banco");
  }
}
