package io.github.matejpopovski.ledgerline.invariants;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Runs a test once per concurrency strategy: an invariant has to hold under all of them. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedTest(name = "{displayName} {0}")
@EnumSource
@interface StrategiesTest {}
