package com.navan.billing.transaction.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

/** metadata for type=rail: a journey with a change of train is two legs. */
public record RailMetadata(@NotEmpty List<@Valid @NotNull Leg> legs) {

    public record Leg(
            @NotBlank String origin,
            @NotBlank String destination,
            @NotNull OffsetDateTime departure,
            @NotNull OffsetDateTime arrival,
            String trainNumber,
            @JsonProperty("class") String travelClass) {}
}
