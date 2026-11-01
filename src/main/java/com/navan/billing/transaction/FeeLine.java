package com.navan.billing.transaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** A carrier/merchant fee on a transaction (YQ surcharge, booking fee). Never mixed into taxes. */
public record FeeLine(@NotBlank String name, @NotNull BigDecimal amount) {}
