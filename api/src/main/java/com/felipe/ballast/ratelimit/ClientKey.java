package com.felipe.ballast.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;

public final class ClientKey {

  private static final int MAX_LENGTH = 64;

  private ClientKey() {}

  // Chave do cliente no limite por IP e no atraso do login (ADR-053). Recebe o RemoteAddr, que em
  // produção o Tomcat já trocou pelo IP do visitante (ADR-044). IPv6
  // conta pelo /64, que é o que um provedor entrega a um cliente só. ofLiteral nunca consulta DNS.
  public static String of(String remoteAddr) {
    InetAddress address;
    try {
      address = InetAddress.ofLiteral(remoteAddr);
    } catch (IllegalArgumentException e) {
      return remoteAddr.length() > MAX_LENGTH ? remoteAddr.substring(0, MAX_LENGTH) : remoteAddr;
    }
    if (!(address instanceof Inet6Address)) {
      return address.getHostAddress();
    }
    byte[] bytes = address.getAddress();
    StringBuilder prefix = new StringBuilder();
    for (int i = 0; i < 8; i += 2) {
      prefix.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff)));
      prefix.append(':');
    }
    return prefix.append(":/64").toString();
  }
}
