package com.lockstep.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
    name = "version",
    description = "Print the version.")
public final class VersionCommand implements Callable<Integer> {
    static final int EXIT_SUCCESS = 0;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getOut().printf("lockstep %s%n", Version.value());
        return EXIT_SUCCESS;
    }
}
