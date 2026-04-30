package com.coredb.query;

import com.coredb.api.Row;
import com.coredb.api.Schema;

public final class Predicate {

    public enum Op { EQ, NEQ, LT, LE, GT, GE }

    private final String column;
    private final Op op;
    private final Object literal;
    private final Schema schema;
    private final int columnIndex;

    public Predicate(String column, Op op, Object literal, Schema schema) {
        int idx = schema.indexOf(column);
        if (idx < 0) {
            throw new IllegalArgumentException("Column not found in schema: " + column);
        }
        this.column = column;
        this.op = op;
        this.literal = literal;
        this.schema = schema;
        this.columnIndex = idx;
    }

    public String column() { return column; }
    public Op op() { return op; }
    public Object literal() { return literal; }
    public Schema schema() { return schema; }

    public boolean test(Row row) {
        return compare(row.get(columnIndex), op, literal);
    }

    // All Row values are Comparable by construction: Long, Integer, String, Boolean
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
