package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PossessionVerifierTest {

  private static final String MESSAGE = "mensagem de teste\ncom duas linhas";

  @Test
  void acceptsAnEvmSignatureFromTheAddressOwnerWithEitherVConvention() {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();

    assertThat(verifyEvm(keys.address(), keys.sign(MESSAGE, 27, false))).isTrue();
    assertThat(verifyEvm(keys.address(), keys.sign(MESSAGE, 0, false))).isTrue();
  }

  @Test
  void rejectsAnEvmSignatureFromAnotherAddress() {
    TestWalletKeys.Evm owner = new TestWalletKeys.Evm();
    TestWalletKeys.Evm other = new TestWalletKeys.Evm();

    assertThat(verifyEvm(owner.address(), other.sign(MESSAGE))).isFalse();
  }

  @Test
  void rejectsAnEvmSignatureOverAnotherMessage() {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();

    assertThat(
            PossessionVerifier.verify(
                WalletNetwork.ETHEREUM, keys.address(), MESSAGE + "x", keys.sign(MESSAGE)))
        .isFalse();
  }

  @Test
  void rejectsTheMalleableHighSForm() {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();

    assertThat(verifyEvm(keys.address(), keys.sign(MESSAGE, 27, true))).isFalse();
  }

  @Test
  void rejectsMalformedEvmSignatures() {
    TestWalletKeys.Evm keys = new TestWalletKeys.Evm();
    String valid = keys.sign(MESSAGE);
    String zeroed = "0x" + "00".repeat(65);
    String badV = valid.substring(0, 130) + "05";
    String[] bad = {
      "",
      "0x",
      valid.substring(2),
      valid.substring(0, valid.length() - 2),
      valid + "00",
      zeroed,
      badV,
      "0x" + "zz".repeat(65),
    };
    for (String signature : bad) {
      assertThat(verifyEvm(keys.address(), signature)).as(signature).isFalse();
    }
    // r fora da curva (acima de n) também não pode passar nem lançar.
    String hugeR = "0x" + "ff".repeat(32) + valid.substring(66);
    assertThat(verifyEvm(keys.address(), hugeR)).isFalse();
    assertThat(new BigInteger(1, HexFormat.of().parseHex("ff".repeat(32)))).isPositive();
  }

  @Test
  void acceptsASolanaSignatureFromTheKeyOwner() throws Exception {
    TestWalletKeys.Solana keys = new TestWalletKeys.Solana();

    assertThat(
            PossessionVerifier.verify(
                WalletNetwork.SOLANA, keys.address(), MESSAGE, keys.sign(MESSAGE)))
        .isTrue();
  }

  @Test
  void rejectsSolanaSignaturesFromAnotherKeyOrOverAnotherMessageOrMalformed() throws Exception {
    TestWalletKeys.Solana owner = new TestWalletKeys.Solana();
    TestWalletKeys.Solana other = new TestWalletKeys.Solana();
    String valid = owner.sign(MESSAGE);

    assertThat(
            PossessionVerifier.verify(
                WalletNetwork.SOLANA, owner.address(), MESSAGE, other.sign(MESSAGE)))
        .isFalse();
    assertThat(
            PossessionVerifier.verify(WalletNetwork.SOLANA, owner.address(), MESSAGE + "x", valid))
        .isFalse();
    for (String bad : new String[] {"", "AAAA", "não é base64!", valid + valid}) {
      assertThat(PossessionVerifier.verify(WalletNetwork.SOLANA, owner.address(), MESSAGE, bad))
          .as(bad)
          .isFalse();
    }
  }

  private static boolean verifyEvm(String address, String signature) {
    return PossessionVerifier.verify(WalletNetwork.ETHEREUM, address, MESSAGE, signature);
  }
}
