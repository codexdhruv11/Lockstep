package com.lockstep.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

final class VersionSmokeTest {
    @Test
    void versionCommandPrintsNameAndVersionToOneLine() {
        StringWriter buffer = new StringWriter();
        PrintWriter out = new PrintWriter(buffer);
        CommandLine cmd = new CommandLine(new Cli());
        cmd.setOut(out);

        int exit = cmd.execute("version");
        out.flush();

        assertThat(exit).isZero();

        assertThat(buffer.toString()).matches("(?s)lockstep \\S+\\n");
    }
}
