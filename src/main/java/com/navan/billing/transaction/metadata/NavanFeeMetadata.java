package com.navan.billing.transaction.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/** metadata for type=navan_fee: a Navan-owned charge such as a trip fee or an agent call fee. */
public record NavanFeeMetadata(@NotNull FeeKind feeKind, String description, String relatedExternalId) {

    public enum FeeKind {
        @JsonProperty("trip_fee") TRIP_FEE,
        @JsonProperty("agent_call_fee") AGENT_CALL_FEE
    }
}
