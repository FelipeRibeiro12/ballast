package com.felipe.ballast.identity;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Cc cobre NUL, DEL e o NEL (U+0085). Os separadores U+2028 e U+2029 não são Cc, mas quebram
// linha em log e em CSV como se fossem controle. O Postgres recusa NUL em text, o que viraria 500.
// Surrogate solto também sai: não é UTF-8 válido, e o driver o troca por "?" em silêncio.
// Cf (formato) também: U+200B e U+FEFF são invisíveis, então "Conta" e "Conta" com um deles parecem
// o mesmo nome, e U+202E inverte a ordem de exibição do que vem depois. Os tags U+E0000 a U+E007F
// escondem texto inteiro. ZWJ (U+200D) e ZWNJ (U+200C) ficam de fora porque o emoji de sequência e
// a grafia persa dependem deles.
@Documented
@Constraint(validatedBy = NoControlCharacters.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoControlCharacters {

  String message() default "caractere inválido";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<NoControlCharacters, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null || value.codePoints().noneMatch(Validator::isControl);
    }

    private static boolean isControl(int codePoint) {
      return Character.getType(codePoint) == Character.CONTROL
          || (Character.getType(codePoint) == Character.FORMAT && !isJoiner(codePoint))
          || isLoneSurrogate(codePoint)
          || codePoint == 0x2028
          || codePoint == 0x2029;
    }

    private static boolean isJoiner(int codePoint) {
      return codePoint == 0x200C || codePoint == 0x200D;
    }

    // Um par válido já chega aqui como um code point só (acima de U+FFFF); sobra na faixa de
    // surrogate apenas o solto. Não dá para usar (char) codePoint: corta os 16 bits de cima e
    // recusa letras atribuídas como U+1D800.
    private static boolean isLoneSurrogate(int codePoint) {
      return codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
    }
  }
}
