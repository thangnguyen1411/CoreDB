package com.coredb.api;

public record Column(String name, ColumnType type, boolean nullable) {

    public static Column intCol(String name)    { return new Column(name, ColumnType.INT,    true); }
    public static Column longCol(String name)   { return new Column(name, ColumnType.LONG,   true); }
    public static Column stringCol(String name) { return new Column(name, ColumnType.STRING, true); }
    public static Column boolCol(String name)   { return new Column(name, ColumnType.BOOL,   true); }

    /**
     * Returns a new Column with the specified nullability.
     *
     * @param nullable true if this column allows null values
     * @return a new Column instance with the same name and type but different nullability
     */
    public Column withNullable(boolean nullable) {
        return new Column(this.name, this.type, nullable);
    }
}
