package com.lockstep.cli;

import java.util.Optional;

public final class Version {
    private static final String UNKNOWN = "unknown";
    private static final String VALUE = resolve();

    private Version() {}

    public static String value() {
        return VALUE;
    }

    private static String resolve() {
        return Optional.ofNullable(Version.class.getPackage())
            .map(Package::getImplementationVersion)
            .orElse(UNKNOWN);
    }
}
