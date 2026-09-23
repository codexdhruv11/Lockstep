package com.lockstep.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

final class VersionSmokeTest {
    private static String runAndCapture(Runnable action) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            action.run();
        } finally {
            System.setOut(originalOut);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void versionCommandPrintsNameAndVersionToOneLine() {
        String out = runAndCapture(() ->
            assertThat(new CommandLine(new Cli()).execute("version")).isZero());

        assertThat(out).matches("(?s)lockstep [^\\s]+\\n");
        assertThat(out.split(" ")[1]).isNotEqualTo("unknown");
    }
}
