package com.coredb.query;

import com.coredb.api.Row;
import com.coredb.api.Schema;

public record Predicate(String column, Op op, Object literal, Schema schema) {

    public enum Op { EQ, NEQ, LT, LE, GT, GE }

    public Predicate {
        if (schema.indexOf(column) < 0) {
            throw new IllegalArgumentException("Column not found in schema: " + column);
        }
    }

    public boolean test(Row row) {
        Object value = row.get(schema.indexOf(column));
        return compare(value, op, literal);
    }

    @SuppressWarnings("unchecked")
    private static boolean compare(Object value, Op op, Object literal) {
        Comparable<Object> cv = (Comparable<Object>) value;
        int cmp = cv.compareTo(literal);
        return switch (op) {
            case EQ  -> cmp == 0;
            case NEQ -> cmp != 0;
            case LT  -> cmp < 0;
            case LE  -> cmp <= 0;
            case GT  -> cmp > 0;
            case GE  -> cmp >= 0;
        };
    }
}
