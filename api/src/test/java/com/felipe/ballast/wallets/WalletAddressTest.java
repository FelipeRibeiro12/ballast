package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WalletAddressTest {

  // Vetores do próprio EIP-55.
  private static final String[] EIP55 = {
    "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
    "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
    "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
    "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
  };

  private static final String USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

  @Test
  void acceptsTheOfficialEip55VectorsAndStoresThemInLowerCase() {
    for (String address : EIP55) {
      assertThat(WalletAddress.normalize(WalletNetwork.ETHEREUM, address))
          .contains(address.toLowerCase(Locale.ROOT));
    }
  }

  @Test
  void acceptsAllUpperAndAllLowerWithoutChecksum() {
    assertThat(
            WalletAddress.normalize(
                WalletNetwork.BASE, "0x52908400098527886E0F7030069857D2E4169EE7"))
        .isPresent();
    assertThat(
            WalletAddress.normalize(
                WalletNetwork.BASE, "0xde709f2102306220921060314715629080e2fb77"))
        .isPresent();
  }

  @Test
  void rejectsAWrongChecksum() {
    // Uma letra trocada de caixa no primeiro vetor oficial.
    assertThat(
            WalletAddress.normalize(
                WalletNetwork.ETHEREUM, "0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "0x",
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAe",
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAedd",
        "5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        "0xZZAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
      })
  void rejectsMalformedEvmAddresses(String address) {
    assertThat(WalletAddress.normalize(WalletNetwork.ETHEREUM, address)).isEmpty();
  }

  @Test
  void acceptsSolanaKeysThatDecodeTo32Bytes() {
    assertThat(WalletAddress.normalize(WalletNetwork.SOLANA, USDC_MINT)).contains(USDC_MINT);
    // 32 zeros em base58 são 32 caracteres "1".
    assertThat(WalletAddress.normalize(WalletNetwork.SOLANA, "1".repeat(32))).isPresent();
  }

  @Test
  void rejectsSolanaKeysWithTheWrongByteCount() {
    assertThat(WalletAddress.normalize(WalletNetwork.SOLANA, "1".repeat(31))).isEmpty();
    assertThat(WalletAddress.normalize(WalletNetwork.SOLANA, "1".repeat(33))).isEmpty();
    // 31 bytes: a chave real sem o último caractere decodifica em menos bytes.
    assertThat(WalletAddress.normalize(WalletNetwork.SOLANA, USDC_MINT.substring(0, 42))).isEmpty();
    // 0, O, I e l não existem em base58.
    assertThat(
            WalletAddress.normalize(
                WalletNetwork.SOLANA, "0PjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))
        .isEmpty();
  }

  @Test
  void doesNotTakeAnEvmAddressAsSolana() {
    assertThat(
            WalletAddress.normalize(
                WalletNetwork.SOLANA, "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
        .isEmpty();
  }
}
