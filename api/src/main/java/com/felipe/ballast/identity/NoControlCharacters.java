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
@Documented
@Constraint(validatedBy = NoControlCharacters.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface NoControlCharacters {

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
          || codePoint == 0x2028
          || codePoint == 0x2029;
    }
  }
}
