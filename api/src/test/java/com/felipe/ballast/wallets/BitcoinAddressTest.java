package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BitcoinAddressTest {

  @Test
  void acceptsKnownMainnetAddresses() {
    assertThat(BitcoinAddress.isValid("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")).isTrue();
    assertThat(BitcoinAddress.isValid("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")).isTrue();
    assertThat(BitcoinAddress.isValid("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")).isTrue();
    assertThat(
            BitcoinAddress.isValid(
                "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"))
        .isTrue();
  }

  @Test
  void rejectsBadChecksumTestnetAndMixedCase() {
    assertThat(BitcoinAddress.isValid("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb")).isFalse();
    assertThat(BitcoinAddress.isValid("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdr")).isFalse();
    assertThat(BitcoinAddress.isValid("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx")).isFalse();
    assertThat(BitcoinAddress.isValid("bc1QAR0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")).isFalse();
    assertThat(BitcoinAddress.isValid(null)).isFalse();
  }
}
