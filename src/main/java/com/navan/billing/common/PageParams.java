package com.navan.billing.common;

/**
 * Keyset (cursor) pagination: "give me up to `limit` rows with id greater than the cursor".
 * Default limit 50, maximum 100; anything above the maximum is a 400.
 */
public record PageParams(int limit, long afterId) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public static PageParams of(Integer limit, String cursor, String idPrefix) {
        int effective = limit == null ? DEFAULT_LIMIT : limit;
        if (effective < 1 || effective > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        long after = 0;
        if (cursor != null && !cursor.isBlank()) {
            Long parsed = Ids.parse(cursor, idPrefix);
            if (parsed == null) {
                throw ApiException.badRequest("cursor is not valid");
            }
            after = parsed;
        }
        return new PageParams(effective, after);
    }
}
