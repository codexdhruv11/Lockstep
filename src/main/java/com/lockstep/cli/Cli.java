package com.lockstep.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "lockstep",
    mixinStandardHelpOptions = true,
    versionProvider = ManifestVersionProvider.class,
    description = "Multi-target load test tool: HTTP, database and Redis load on one shared clock.",
    subcommands = {RunCommand.class, VersionCommand.class})
public final class Cli {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Cli()).execute(args);

        System.exit(exitCode);
    }
}
