package com.felipe.ballast.wallets;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

// Confere a assinatura da prova de posse (ADR-072). Só devolve verdadeiro ou falso: o motivo da
// recusa não sai daqui, e nada deste texto vai para log.
final class PossessionVerifier {

  private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
  private static final BigInteger ORDER = CURVE.getN();
  private static final BigInteger HALF_ORDER = ORDER.shiftRight(1);
  private static final Pattern EVM_SIGNATURE = Pattern.compile("0x[0-9a-fA-F]{130}");
  private static final int ED25519_SIGNATURE_BYTES = 64;
  // Cabeçalho DER (SubjectPublicKeyInfo) de uma chave Ed25519; os 32 bytes crus vêm depois.
  private static final byte[] ED25519_X509_PREFIX =
      HexFormat.of().parseHex("302a300506032b6570032100");

  private PossessionVerifier() {}

  static boolean verify(WalletNetwork network, String address, String message, String signature) {
    if (signature == null || message == null || address == null) {
      return false;
    }
    try {
      return network.isEvm()
          ? verifyEvm(address, message, signature)
          : verifySolana(address, message, signature);
    } catch (RuntimeException | GeneralSecurityException e) {
      // Entrada torta (ponto fora da curva, base64 inválido) é assinatura inválida, não erro.
      return false;
    }
  }

  private static boolean verifyEvm(String address, String message, String signature) {
    byte[] body = message.getBytes(StandardCharsets.UTF_8);
    byte[] prefix =
        ("\u0019Ethereum Signed Message:\n" + body.length).getBytes(StandardCharsets.UTF_8);
    byte[] signed = new byte[prefix.length + body.length];
    System.arraycopy(prefix, 0, signed, 0, prefix.length);
    System.arraycopy(body, 0, signed, prefix.length, body.length);
    return recoverEvmAddress(Keccak256.hash(signed), signature)
        .map(
            recovered ->
                MessageDigest.isEqual(
                    recovered.getBytes(StandardCharsets.US_ASCII),
                    address.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
        .orElse(false);
  }

  // Endereço em minúsculas de quem assinou o hash, ou vazio se a assinatura não vale. Rejeita o s
  // alto: toda assinatura tem uma gêmea (n - s) que também passa, e aceitar as duas deixaria um
  // terceiro gerar outra assinatura válida a partir de uma capturada.
  static Optional<String> recoverEvmAddress(byte[] hash, String signature) {
    if (!EVM_SIGNATURE.matcher(signature).matches()) {
      return Optional.empty();
    }
    byte[] raw = HexFormat.of().parseHex(signature.substring(2));
    BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32));
    BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64));
    int v = raw[64] & 0xff;
    int recoveryId = v >= 27 ? v - 27 : v;
    if (recoveryId > 1
        || r.signum() == 0
        || r.compareTo(ORDER) >= 0
        || s.signum() == 0
        || s.compareTo(HALF_ORDER) > 0) {
      return Optional.empty();
    }
    ECPoint publicKey = recoverPublicKey(recoveryId, r, s, hash);
    if (publicKey == null || publicKey.isInfinity()) {
      return Optional.empty();
    }
    byte[] uncompressed = publicKey.getEncoded(false);
    byte[] digest = Keccak256.hash(Arrays.copyOfRange(uncompressed, 1, uncompressed.length));
    return Optional.of("0x" + HexFormat.of().formatHex(digest, 12, 32));
  }

  // SEC 1, seção 4.1.6. Como r < n < p, só existem dois candidatos para o ponto R (paridade do y).
  private static ECPoint recoverPublicKey(int recoveryId, BigInteger r, BigInteger s, byte[] hash) {
    ECCurve curve = CURVE.getCurve();
    X9IntegerConverter converter = new X9IntegerConverter();
    byte[] compressed = converter.integerToBytes(r, 1 + converter.getByteLength(curve));
    compressed[0] = (byte) (recoveryId == 1 ? 0x03 : 0x02);
    ECPoint point = curve.decodePoint(compressed);
    if (!point.multiply(ORDER).isInfinity()) {
      return null;
    }
    BigInteger e = new BigInteger(1, hash);
    BigInteger rInverse = r.modInverse(ORDER);
    BigInteger gFactor = ORDER.subtract(e.mod(ORDER)).multiply(rInverse).mod(ORDER);
    BigInteger rFactor = s.multiply(rInverse).mod(ORDER);
    return ECAlgorithms.sumOfTwoMultiplies(CURVE.getG(), gFactor, point, rFactor).normalize();
  }

  private static boolean verifySolana(String address, String message, String signature)
      throws GeneralSecurityException {
    Optional<byte[]> key = WalletAddress.decodeSolanaKey(address);
    if (key.isEmpty()) {
      return false;
    }
    byte[] raw = Base64.getDecoder().decode(signature);
    if (raw.length != ED25519_SIGNATURE_BYTES) {
      return false;
    }
    byte[] encoded = new byte[ED25519_X509_PREFIX.length + key.get().length];
    System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
    System.arraycopy(key.get(), 0, encoded, ED25519_X509_PREFIX.length, key.get().length);
    PublicKey publicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(publicKey);
    verifier.update(message.getBytes(StandardCharsets.UTF_8));
    return verifier.verify(raw);
  }
}
