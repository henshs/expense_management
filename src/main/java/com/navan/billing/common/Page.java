package com.navan.billing.common;

import java.util.List;

/** One page of a list endpoint. next_cursor is null on the last page. */
public record Page<T>(List<T> items, int limit, String nextCursor) {}
