package com.navan.billing.transaction.metadata;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** metadata for type=hotel: property name and address are mandatory. */
public record HotelMetadata(
        @NotBlank String name,
        @NotNull @Valid Address address,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotNull @Positive Integer nights,
        @NotNull BigDecimal roomRatePerNight,
        List<@Valid @NotNull Extra> extras) {

    public record Address(@NotBlank String street, @NotBlank String city, @NotBlank String country, String postalCode) {}

    public record Extra(@NotBlank String name, @NotNull BigDecimal amount) {}
}
