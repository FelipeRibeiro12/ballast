package com.felipe.ballast.entries;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// A data chega como texto e não como LocalDate: o Jackson aceitaria também lista de inteiros e
// data com hora. Nulo passa, o @NotNull do campo cuida dele.
@Documented
@Constraint(validatedBy = ValidIsoDate.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidIsoDate {

  String message() default "data inválida";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<ValidIsoDate, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null || IsoDates.parse(value).isPresent();
    }
  }
}
