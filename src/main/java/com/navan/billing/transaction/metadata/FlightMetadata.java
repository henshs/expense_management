package com.navan.billing.transaction.metadata;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** metadata for type=flight. A round trip has two or more coupons (segments). */
public record FlightMetadata(
        @NotBlank String origin,
        @NotBlank String destination,
        @NotNull BigDecimal fare,
        @NotEmpty List<@Valid @NotNull Coupon> coupons) {

    public record Coupon(
            @NotBlank String flightNumber,
            String carrier,
            @NotBlank String from,
            @NotBlank String to,
            @NotNull OffsetDateTime departure,
            @NotNull OffsetDateTime arrival) {}
}
