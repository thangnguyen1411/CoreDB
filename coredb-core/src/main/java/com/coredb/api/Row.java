package com.coredb.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Row {

    private final List<Object> values;

    private Row(List<Object> values) {
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static Row of(Object... values) {
        return new Row(Arrays.asList(values));
    }

    public static Row of(List<Object> values) {
        return new Row(values);
    }

    public int size() {
        return values.size();
    }

    public Object get(int index) {
        return values.get(index);
    }

    public Integer getInt(int index)     { return (Integer) values.get(index); }
    public Long getLong(int index)       { return (Long) values.get(index); }
    public String getString(int index)   { return (String) values.get(index); }
    public Boolean getBoolean(int index) { return (Boolean) values.get(index); }

    public List<Object> values() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Row other)) return false;
        return Objects.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
