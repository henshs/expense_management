package com.navan.billing.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money is BigDecimal with scale 2 (cents), stored as DECIMAL(19,2).
 * Inputs with more than 2 decimal places are rejected rather than silently rounded.
 */
public final class Money {
    public static final int SCALE = 2;
    public static final int RATE_SCALE = 6;

    private Money() {}

    public static BigDecimal amount(BigDecimal value, String field) {
        if (value == null) {
            throw ApiException.badRequest(field + " is required");
        }
        if (value.signum() < 0) {
            throw ApiException.badRequest(field + " must not be negative");
        }
        try {
            return value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest(field + " must have at most " + SCALE + " decimal places");
        }
    }

    public static BigDecimal rate(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw ApiException.badRequest(field + " must not be negative");
        }
        try {
            return value.setScale(RATE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest(field + " must have at most " + RATE_SCALE + " decimal places");
        }
    }

    /** Display form of a rate read from the DB: 0.070000 -> 0.07. */
    public static BigDecimal displayRate(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        BigDecimal stripped = rate.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
