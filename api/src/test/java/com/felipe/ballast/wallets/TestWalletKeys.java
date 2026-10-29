package com.felipe.ballast.wallets;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECPoint;

// Pares de chaves descartáveis gerados a cada teste. Keccak e assinatura vêm do Bouncy Castle
// direto, para o teste não depender do Keccak256 da aplicação que ele ajuda a validar.
final class TestWalletKeys {

  private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
  private static final ECDomainParameters DOMAIN =
      new ECDomainParameters(CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());
  private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

  private TestWalletKeys() {}

  static final class Evm {
    private final BigInteger privateKey;
    private final String address;

    Evm() {
      BigInteger key;
      do {
        key = new BigInteger(256, new SecureRandom());
      } while (key.signum() == 0 || key.compareTo(CURVE.getN()) >= 0);
      this.privateKey = key;
      ECPoint publicPoint = CURVE.getG().multiply(key).normalize();
      byte[] uncompressed = publicPoint.getEncoded(false);
      byte[] hash = keccak(Arrays.copyOfRange(uncompressed, 1, 65));
      this.address = "0x" + HexFormat.of().formatHex(hash, 12, 32);
    }

    String address() {
      return address;
    }

    // v em 27 ou 28, como o personal_sign das carteiras.
    String sign(String message) {
      return sign(message, 27, false);
    }

    String sign(String message, int vBase, boolean highS) {
      byte[] hash = personalHash(message);
      ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
      signer.init(true, new ECPrivateKeyParameters(privateKey, DOMAIN));
      BigInteger[] rs = signer.generateSignature(hash);
      BigInteger r = rs[0];
      BigInteger s = rs[1];
      BigInteger half = CURVE.getN().shiftRight(1);
      if (s.compareTo(half) > 0) {
        s = CURVE.getN().subtract(s);
      }
      // O v certo é o que faz a recuperação voltar neste endereço; a recuperação em si é do
      // verificador, mas o endereço esperado vem da chave pública daqui.
      for (int recoveryId = 0; recoveryId < 2; recoveryId++) {
        String candidate = encode(r, s, vBase + recoveryId);
        if (PossessionVerifier.recoverEvmAddress(personalHash(message), candidate)
            .map(address::equals)
            .orElse(false)) {
          if (highS) {
            // Mesma assinatura em forma maleável: s' = n - s e v invertido.
            return encode(r, CURVE.getN().subtract(s), vBase + (1 - recoveryId));
          }
          return candidate;
        }
      }
      throw new IllegalStateException("nenhum v recupera o endereço");
    }

    private static String encode(BigInteger r, BigInteger s, int v) {
      byte[] out = new byte[65];
      System.arraycopy(toFixed(r), 0, out, 0, 32);
      System.arraycopy(toFixed(s), 0, out, 32, 32);
      out[64] = (byte) v;
      return "0x" + HexFormat.of().formatHex(out);
    }
  }

  static final class Solana {
    private final KeyPair pair;

    Solana() throws Exception {
      this.pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    String address() {
      byte[] encoded = pair.getPublic().getEncoded();
      return base58(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    String sign(String message) throws Exception {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(pair.getPrivate());
      signature.update(message.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(signature.sign());
    }
  }

  static byte[] personalHash(String message) {
    byte[] body = message.getBytes(StandardCharsets.UTF_8);
    byte[] prefix =
        ("\u0019Ethereum Signed Message:\n" + body.length).getBytes(StandardCharsets.UTF_8);
    byte[] all = new byte[prefix.length + body.length];
    System.arraycopy(prefix, 0, all, 0, prefix.length);
    System.arraycopy(body, 0, all, prefix.length, body.length);
    return keccak(all);
  }

  private static byte[] keccak(byte[] input) {
    KeccakDigest digest = new KeccakDigest(256);
    digest.update(input, 0, input.length);
    byte[] out = new byte[32];
    digest.doFinal(out, 0);
    return out;
  }

  private static byte[] toFixed(BigInteger value) {
    byte[] raw = value.toByteArray();
    byte[] out = new byte[32];
    int length = Math.min(raw.length, 32);
    System.arraycopy(raw, raw.length - length, out, 32 - length, length);
    return out;
  }

  static String base58(byte[] bytes) {
    BigInteger value = new BigInteger(1, bytes);
    StringBuilder out = new StringBuilder();
    BigInteger base = BigInteger.valueOf(58);
    while (value.signum() > 0) {
      BigInteger[] divided = value.divideAndRemainder(base);
      out.append(BASE58.charAt(divided[1].intValue()));
      value = divided[0];
    }
    for (byte b : bytes) {
      if (b != 0) {
        break;
      }
      out.append('1');
    }
    return out.reverse().toString();
  }
}
