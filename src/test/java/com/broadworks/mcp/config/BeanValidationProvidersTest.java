package com.broadworks.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Guards the dual Bean Validation setup this project needs on a single classpath:
 * <ul>
 *   <li>a JAKARTA namespace provider (Hibernate Validator, via spring-boot-starter-validation) for
 *       Spring's own validation - without it startup logs
 *       "Failed to set up a Bean Validation provider: jakarta.validation.NoProviderFoundException"
 *       and constraints such as {@code @NotEmpty} on our controllers are never enforced;</li>
 *   <li>a LEGACY javax namespace provider (Apache BVal) for the Alpaca toolkit, whose
 *       {@code Request.validate()} calls {@code javax.validation.Validation.buildDefaultValidatorFactory()}.</li>
 * </ul>
 * Both must resolve, hence the belt-and-braces assertions on each namespace.
 */
class BeanValidationProvidersTest {

    @Test
    void jakartaValidationProviderIsAvailable() {
        assertThatCode(() -> {
                    try (jakarta.validation.ValidatorFactory factory =
                            jakarta.validation.Validation.buildDefaultValidatorFactory()) {
                        assertThat(factory.getValidator().validate(new JakartaBean(" ")))
                                .isNotEmpty();
                        assertThat(factory.getValidator().validate(new JakartaBean("value")))
                                .isEmpty();
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    void legacyJavaxValidationProviderIsAvailable() {
        assertThatCode(() -> {
                    javax.validation.ValidatorFactory factory =
                            javax.validation.Validation.buildDefaultValidatorFactory();
                    assertThat(factory.getValidator().validate(new JavaxBean(" "))).isNotEmpty();
                    assertThat(factory.getValidator().validate(new JavaxBean("value")))
                            .isEmpty();
                })
                .doesNotThrowAnyException();
    }

    private record JakartaBean(@jakarta.validation.constraints.NotBlank String name) {}

    private record JavaxBean(@javax.validation.constraints.NotBlank String name) {}
}
