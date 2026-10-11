package pt.diamondcars.catalogbackend.web.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Year;

/**
 * Rejects a model year further in the future than next calendar year, mirroring the browser's
 * dynamic upper bound {@code LIMITS.YEAR_MAX = new Date().getFullYear() + 1} ({@code
 * dcbo/src/utils/validation.js}, enforced by {@code validateYear}). Copied from {@code
 * dcbo-backend} (TASK-003, AC A.3).
 *
 * <p>A plain {@code jakarta.validation.constraints.Max} cannot express this rule because its bound
 * must be a compile-time constant, while the frontend's bound advances every calendar year; this
 * annotation recomputes the bound at validation time instead.
 */
@Target({
	ElementType.METHOD,
	ElementType.FIELD,
	ElementType.ANNOTATION_TYPE,
	ElementType.CONSTRUCTOR,
	ElementType.PARAMETER,
	ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxCurrentYearPlusOne.Validator.class)
public @interface MaxCurrentYearPlusOne {

	/**
	 * @return the default message template, overridden per violation with the computed bound
	 */
	String message() default "must be less than or equal to the current year plus one";

	/**
	 * @return validation groups this constraint belongs to (unused by this API)
	 */
	Class<?>[] groups() default {};

	/**
	 * @return payload metadata attached to a violation (unused by this API)
	 */
	Class<? extends Payload>[] payload() default {};

	/** Validates that an {@link Integer} year is not more than one calendar year in the future. */
	class Validator implements ConstraintValidator<MaxCurrentYearPlusOne, Integer> {

		/**
		 * @param value the year to validate, or {@code null} (nullability is left to {@code @NotNull}
		 *     on the same field)
		 * @param context the validation context, used to report the computed bound in the violation
		 *     message instead of the static default
		 * @return {@code true} if {@code value} is {@code null} or does not exceed the current year
		 *     plus one
		 */
		@Override
		public boolean isValid(Integer value, ConstraintValidatorContext context) {
			if (value == null) {
				return true;
			}
			int max = Year.now().getValue() + 1;
			if (value <= max) {
				return true;
			}
			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate("must be less than or equal to " + max).addConstraintViolation();
			return false;
		}
	}
}
