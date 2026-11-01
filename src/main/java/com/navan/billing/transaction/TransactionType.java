package com.navan.billing.transaction;

import java.util.Arrays;
import java.util.Optional;

public enum TransactionType {
    FLIGHT("flight"),
    HOTEL("hotel"),
    RAIL("rail"),
    NAVAN_FEE("navan_fee");

    private final String wireName;

    TransactionType(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static Optional<TransactionType> fromWire(String value) {
        return Arrays.stream(values()).filter(t -> t.wireName.equals(value)).findFirst();
    }
}
