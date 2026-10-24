package com.felipe.ballast.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientKeyTest {

  @Test
  void keepsIpv4AsIs() {
    assertThat(ClientKey.of("203.0.113.7")).isEqualTo("203.0.113.7");
  }

  // Um provedor entrega um /64 inteiro para cada cliente: contar endereço por endereço deixaria
  // um só atacante trocar de IP à vontade.
  @Test
  void groupsIpv6ByTheSlash64() {
    assertThat(ClientKey.of("2001:db8:1:2:aaaa::1"))
        .isEqualTo(ClientKey.of("2001:db8:1:2:ffff:ffff:ffff:ffff"))
        .isEqualTo("2001:db8:1:2::/64");
    assertThat(ClientKey.of("2001:db8:1:3::1")).isNotEqualTo(ClientKey.of("2001:db8:1:2::1"));
  }

  @Test
  void readsIpv4MappedIpv6AsIpv4() {
    assertThat(ClientKey.of("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
  }

  @Test
  void keepsAnythingElseCutToSixtyFourCharacters() {
    assertThat(ClientKey.of("não-é-ip")).isEqualTo("não-é-ip");
    assertThat(ClientKey.of("x".repeat(500))).hasSize(64);
  }
}
