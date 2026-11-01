package com.navan.billing.transaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** A government tax on a transaction (VAT, airport tax). rate is optional, e.g. 0.07 = 7 %. */
public record TaxLine(@NotBlank String name, BigDecimal rate, @NotNull BigDecimal amount) {}
