package com.felipe.ballast.wallets;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// O texto que o usuário assina, sempre montado aqui (ADR-072). O cliente assina o que recebe;
// domínio e rede dentro do texto impedem reaproveitar a assinatura em outro site ou rede.
final class PossessionMessage {

  static final String STATEMENT =
      "Provar que esta carteira é minha no Ballast. Isto não é uma transação e não custa nada.";

  private PossessionMessage() {}

  static String build(
      WalletNetwork network,
      String address,
      PosseProperties posse,
      String nonce,
      Instant issuedAt,
      Instant expiresAt) {
    String issued = issuedAt.truncatedTo(ChronoUnit.SECONDS).toString();
    String expires = expiresAt.truncatedTo(ChronoUnit.SECONDS).toString();
    if (network.isEvm()) {
      // EIP-4361. O endereço vai com o checksum EIP-55, que é como as carteiras o mostram.
      String checksummed = "0x" + WalletAddress.checksum(address.substring(2).toLowerCase());
      return posse.effectiveDomain()
          + " wants you to sign in with your Ethereum account:\n"
          + checksummed
          + "\n\n"
          + STATEMENT
          + "\n\nURI: "
          + posse.uri()
          + "\nVersion: 1\nChain ID: "
          + chainId(network)
          + "\nNonce: "
          + nonce
          + "\nIssued At: "
          + issued
          + "\nExpiration Time: "
          + expires;
    }
    return posse.effectiveDomain()
        + " quer que você prove a posse desta carteira Solana:\n"
        + address
        + "\n\n"
        + STATEMENT
        + "\n\nURI: "
        + posse.uri()
        + "\nRede: "
        + network.value()
        + "\nNonce: "
        + nonce
        + "\nEmitida em: "
        + issued
        + "\nExpira em: "
        + expires;
  }

  private static int chainId(WalletNetwork network) {
    return switch (network) {
      case ETHEREUM -> 1;
      case BASE -> 8453;
      case SOLANA -> throw new IllegalArgumentException("Solana não tem chain id");
    };
  }
}
