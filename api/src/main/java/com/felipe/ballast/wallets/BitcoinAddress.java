package com.felipe.ballast.wallets;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

// Endereço Bitcoin de mainnet: legado (base58check, prefixo 1 ou 3) e SegWit (bech32 e bech32m,
// prefixo bc1). Valida o checksum para um endereço digitado errado não virar saldo zero mudo.
final class BitcoinAddress {

  private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  private static final String BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
  private static final int BECH32_CONST = 1;
  private static final int BECH32M_CONST = 0x2bc830a3;

  private BitcoinAddress() {}

  static boolean isValid(String address) {
    if (address == null || address.length() < 26 || address.length() > 90) {
      return false;
    }
    if (address.regionMatches(true, 0, "bc1", 0, 3)) {
      return isSegwit(address);
    }
    char first = address.charAt(0);
    return (first == '1' || first == '3') && isBase58Check(address);
  }

  private static boolean isBase58Check(String address) {
    if (address.length() > 35) {
      return false;
    }
    BigInteger value = BigInteger.ZERO;
    for (int i = 0; i < address.length(); i++) {
      int digit = BASE58.indexOf(address.charAt(i));
      if (digit < 0) {
        return false;
      }
      value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
    }
    byte[] raw = value.toByteArray();
    if (raw.length > 1 && raw[0] == 0) {
      raw = Arrays.copyOfRange(raw, 1, raw.length);
    }
    int leadingOnes = 0;
    while (leadingOnes < address.length() && address.charAt(leadingOnes) == '1') {
      leadingOnes++;
    }
    byte[] decoded = new byte[leadingOnes + raw.length];
    System.arraycopy(raw, 0, decoded, leadingOnes, raw.length);
    // 1 byte de versão, 20 de hash e 4 de checksum.
    if (decoded.length != 25) {
      return false;
    }
    byte[] body = Arrays.copyOfRange(decoded, 0, 21);
    byte[] check = doubleSha256(body);
    for (int i = 0; i < 4; i++) {
      if (check[i] != decoded[21 + i]) {
        return false;
      }
    }
    return true;
  }

  private static byte[] doubleSha256(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return digest.digest(digest.digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 sempre existe no JDK", e);
    }
  }

  // BIP-173 e BIP-350. Caixa mista é inválida; a versão 0 usa bech32 e a 1 ou mais, bech32m.
  private static boolean isSegwit(String address) {
    String lower = address.toLowerCase(Locale.ROOT);
    if (!address.equals(lower) && !address.equals(address.toUpperCase(Locale.ROOT))) {
      return false;
    }
    int separator = lower.lastIndexOf('1');
    String hrp = lower.substring(0, separator);
    String data = lower.substring(separator + 1);
    if (!hrp.equals("bc") || data.length() < 7) {
      return false;
    }
    int[] values = new int[data.length()];
    for (int i = 0; i < data.length(); i++) {
      int v = BECH32.indexOf(data.charAt(i));
      if (v < 0) {
        return false;
      }
      values[i] = v;
    }
    int version = values[0];
    if (version > 16) {
      return false;
    }
    int expected = version == 0 ? BECH32_CONST : BECH32M_CONST;
    if (polymod(hrp, values) != expected) {
      return false;
    }
    // O programa de witness (sem a versão e sem os 6 de checksum) tem 2 a 40 bytes, e
    // a versão 0 só aceita 20 ou 32.
    int programBytes = (values.length - 7) * 5 / 8;
    if (programBytes < 2 || programBytes > 40) {
      return false;
    }
    return version != 0 || programBytes == 20 || programBytes == 32;
  }

  private static int polymod(String hrp, int[] values) {
    int[] generator = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
    int check = 1;
    for (int i = 0; i < hrp.length(); i++) {
      check = step(check, hrp.charAt(i) >> 5, generator);
    }
    check = step(check, 0, generator);
    for (int i = 0; i < hrp.length(); i++) {
      check = step(check, hrp.charAt(i) & 31, generator);
    }
    for (int v : values) {
      check = step(check, v, generator);
    }
    return check;
  }

  private static int step(int check, int value, int[] generator) {
    int top = check >>> 25;
    int next = ((check & 0x1ffffff) << 5) ^ value;
    for (int i = 0; i < 5; i++) {
      if (((top >>> i) & 1) != 0) {
        next ^= generator[i];
      }
    }
    return next;
  }
}
