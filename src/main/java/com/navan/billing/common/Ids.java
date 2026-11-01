package com.navan.billing.common;

/**
 * Public ids are "txn_42" / "inv_7"; the database uses plain BIGINT keys.
 * An id that does not parse cannot exist, so it is reported as 404 by callers.
 */
public final class Ids {
    public static final String TRANSACTION_PREFIX = "txn_";
    public static final String INVOICE_PREFIX = "inv_";

    private Ids() {}

    public static String transaction(long id) { return TRANSACTION_PREFIX + id; }
    public static String invoice(long id) { return INVOICE_PREFIX + id; }

    /** Returns the numeric key, or null if the string is not a well-formed id with this prefix. */
    public static Long parse(String publicId, String prefix) {
        if (publicId == null || !publicId.startsWith(prefix)) {
            return null;
        }
        try {
            long value = Long.parseLong(publicId.substring(prefix.length()));
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
