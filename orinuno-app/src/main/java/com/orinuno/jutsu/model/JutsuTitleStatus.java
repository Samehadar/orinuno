package com.orinuno.jutsu.model;

/** Mirror of the {@code jutsu_title.status} enum (ADR 0016 P1a). */
public enum JutsuTitleStatus {
    ONGOING("ongoing"),
    RELEASED("released");

    private final String dbValue;

    JutsuTitleStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static JutsuTitleStatus fromDbValue(String value) {
        if (value == null) return null;
        for (JutsuTitleStatus s : values()) {
            if (s.dbValue.equalsIgnoreCase(value)) return s;
        }
        throw new IllegalArgumentException("Unknown jutsu_title.status value: " + value);
    }
}
