package com.lockstep.core;

public enum Arrivals {

    CONSTANT,

    POISSON;

    public static Arrivals parse(String value) {
        if (value == null || value.isBlank()) {
            return CONSTANT;
        }
        return switch (value.trim().toLowerCase()) {
            case "constant", "uniform", "fixed" -> CONSTANT;
            case "poisson", "exponential", "random" -> POISSON;
            default -> throw new IllegalArgumentException(
                    "unknown arrival model \"" + value + "\" (expected constant or poisson)");
        };
    }

    public String label() {
        return name().toLowerCase();
    }
}
