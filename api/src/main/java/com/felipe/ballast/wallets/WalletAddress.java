package com.felipe.ballast.wallets;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

// Valida e normaliza o endereço antes de gravar (ADR-071). Endereço digitado errado daria saldo
// zero sem explicação, e um parecido é vetor de confusão.
final class WalletAddress {

  private static final Pattern EVM = Pattern.compile("0x[0-9a-fA-F]{40}");
  private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  private static final int SOLANA_KEY_BYTES = 32;
  // 32 bytes em base58 têm 43 ou 44 caracteres; os zeros à frente viram "1" e encurtam o que
  // sobra. O teto evita decodificar texto enorme.
  private static final int SOLANA_MAX_CHARS = 44;

  private WalletAddress() {}

  // O endereço na forma que o banco guarda, ou vazio quando não serve para a rede.
  static Optional<String> normalize(WalletNetwork network, String address) {
    if (address == null) {
      return Optional.empty();
    }
    if (network.isEvm()) {
      return normalizeEvm(address);
    }
    return isSolanaKey(address) ? Optional.of(address) : Optional.empty();
  }

  // Tudo minúsculo ou tudo maiúsculo passa sem checksum. Caixa mista é o checksum do EIP-55 e tem
  // de bater. Gravado em minúsculas.
  private static Optional<String> normalizeEvm(String address) {
    if (!EVM.matcher(address).matches()) {
      return Optional.empty();
    }
    String digits = address.substring(2);
    boolean mixed =
        !digits.equals(digits.toLowerCase(Locale.ROOT))
            && !digits.equals(digits.toUpperCase(Locale.ROOT));
    if (mixed && !digits.equals(checksum(digits.toLowerCase(Locale.ROOT)))) {
      return Optional.empty();
    }
    return Optional.of("0x" + digits.toLowerCase(Locale.ROOT));
  }

  // EIP-55: a letra do endereço fica maiúscula quando o nibble correspondente do hash do texto em
  // minúsculas vale 8 ou mais.
  static String checksum(String lowerDigits) {
    byte[] hash = Keccak256.hash(lowerDigits.getBytes(StandardCharsets.US_ASCII));
    StringBuilder out = new StringBuilder(lowerDigits.length());
    for (int i = 0; i < lowerDigits.length(); i++) {
      char c = lowerDigits.charAt(i);
      int nibble = (hash[i / 2] >> (i % 2 == 0 ? 4 : 0)) & 0x0f;
      out.append(c >= 'a' && nibble >= 8 ? Character.toUpperCase(c) : c);
    }
    return out.toString();
  }

  private static boolean isSolanaKey(String address) {
    return decodeSolanaKey(address).isPresent();
  }

  // Os 32 bytes da chave pública, ou vazio se o texto não for base58 de 32 bytes.
  static Optional<byte[]> decodeSolanaKey(String address) {
    if (address == null || address.isEmpty() || address.length() > SOLANA_MAX_CHARS) {
      return Optional.empty();
    }
    BigInteger value = BigInteger.ZERO;
    BigInteger base = BigInteger.valueOf(58);
    int leadingZeros = 0;
    boolean leading = true;
    for (int i = 0; i < address.length(); i++) {
      int digit = BASE58.indexOf(address.charAt(i));
      if (digit < 0) {
        return Optional.empty();
      }
      if (leading && digit == 0) {
        leadingZeros++;
      } else {
        leading = false;
      }
      value = value.multiply(base).add(BigInteger.valueOf(digit));
    }
    byte[] significant = value.signum() == 0 ? new byte[0] : unsigned(value);
    if (leadingZeros + significant.length != SOLANA_KEY_BYTES) {
      return Optional.empty();
    }
    byte[] key = new byte[SOLANA_KEY_BYTES];
    System.arraycopy(significant, 0, key, leadingZeros, significant.length);
    return Optional.of(key);
  }

  // BigInteger.toByteArray põe um byte de sinal à frente quando o bit alto está ligado.
  private static byte[] unsigned(BigInteger value) {
    byte[] raw = value.toByteArray();
    return raw[0] == 0 ? java.util.Arrays.copyOfRange(raw, 1, raw.length) : raw;
  }
}
