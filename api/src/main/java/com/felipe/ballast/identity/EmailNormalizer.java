package com.felipe.ballast.identity;

import java.text.Normalizer;
import java.util.Locale;

// O e-mail é a identidade do login e o banco confere email = lower(email). NFKC primeiro, para a
// forma de largura total ("ＡＢ") e as ligaturas virarem o texto comum antes de caírem em minúscula.
final class EmailNormalizer {

  private EmailNormalizer() {}

  // O Java troca "İ" (U+0130) por "i" mais U+0307 e o Postgres por "i": o mesmo e-mail viraria
  // duas identidades conforme quem normaliza. Quando o minúsculo completo difere do minúsculo por
  // code point, devolve o texto sem minúscula e o BasicEmail recusa (não é igual ao próprio
  // minúsculo).
  static String normalize(String email) {
    if (email == null) {
      return null;
    }
    String normalized = Normalizer.normalize(email.strip(), Normalizer.Form.NFKC);
    String lower = normalized.toLowerCase(Locale.ROOT);
    return lower.equals(lowerByCodePoint(normalized)) ? lower : normalized;
  }

  private static String lowerByCodePoint(String text) {
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints().forEach(cp -> out.appendCodePoint(Character.toLowerCase(cp)));
    return out.toString();
  }
}
