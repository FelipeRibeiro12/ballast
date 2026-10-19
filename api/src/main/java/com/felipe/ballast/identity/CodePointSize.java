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

// O @Size conta unidades UTF-16 (um emoji vale 2), e o char_length do CHECK do banco conta code
// points. Contar do mesmo jeito evita recusar um nome que o banco aceitaria.
@Documented
@Constraint(validatedBy = CodePointSize.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {

  int min() default 0;

  int max() default Integer.MAX_VALUE;

  String message() default "tamanho inválido";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<CodePointSize, String> {

    private int min;
    private int max;

    @Override
    public void initialize(CodePointSize annotation) {
      min = annotation.min();
      max = annotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      if (value == null) {
        return true;
      }
      // Teto de UTF-16 antes de contar: cada code point ocupa pelo menos uma unidade.
      if (value.length() > max * 2L) {
        return false;
      }
      int size = value.codePointCount(0, value.length());
      return size >= min && size <= max;
    }
  }
}
