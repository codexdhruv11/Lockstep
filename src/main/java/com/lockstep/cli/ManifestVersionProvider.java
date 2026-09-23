package com.lockstep.cli;

import picocli.CommandLine.IVersionProvider;

public final class ManifestVersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[] {"lockstep " + Version.value()};
    }
}
