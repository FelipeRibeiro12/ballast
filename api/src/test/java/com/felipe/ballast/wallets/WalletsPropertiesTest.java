package com.felipe.ballast.wallets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WalletsPropertiesTest {

  @Test
  void onlyTheKeyMakesEveryNetworkPointAtAlchemy() {
    WalletsProperties properties =
        new WalletsProperties("k", "", " ", null, false, false, null, null);

    assertThat(properties.urlOf(WalletNetwork.ETHEREUM))
        .isEqualTo("https://eth-mainnet.g.alchemy.com");
    assertThat(properties.urlOf(WalletNetwork.BASE))
        .isEqualTo("https://base-mainnet.g.alchemy.com");
    assertThat(properties.urlOf(WalletNetwork.SOLANA))
        .isEqualTo("https://solana-mainnet.g.alchemy.com");
    assertThat(properties.hasKey()).isTrue();
  }

  @Test
  void anExplicitUrlWinsOverTheDefault() {
    WalletsProperties properties =
        new WalletsProperties(
            "k",
            "https://eth.exemplo.test",
            null,
            "https://sol.exemplo.test",
            false,
            false,
            null,
            null);

    assertThat(properties.urlOf(WalletNetwork.ETHEREUM)).isEqualTo("https://eth.exemplo.test");
    assertThat(properties.urlOf(WalletNetwork.BASE))
        .isEqualTo("https://base-mainnet.g.alchemy.com");
    assertThat(properties.urlOf(WalletNetwork.SOLANA)).isEqualTo("https://sol.exemplo.test");
  }

  @Test
  void withoutAKeyThereIsNoKey() {
    assertThat(new WalletsProperties(null, null, null, null, false, false, null, null).hasKey())
        .isFalse();
    assertThat(new WalletsProperties("  ", null, null, null, false, false, null, null).hasKey())
        .isFalse();
  }
}
