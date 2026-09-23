package com.lockstep.config;

public class ConfigValidationException extends RuntimeException {
    private final String fieldName;

    public ConfigValidationException(String message) {
        this(null, message);
    }

    public ConfigValidationException(String fieldName, String message) {
        super(message);
        this.fieldName = fieldName;
    }

    public String fieldName() {
        return fieldName;
    }
}
