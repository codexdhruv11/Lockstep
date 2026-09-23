package com.lockstep.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class JsonExport {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonExport() {}

    public static String toJson(RunReport report) {
        try {
            return MAPPER.writeValueAsString(report);
        } catch (IOException e) {
            throw new UncheckedIOException("could not serialise the run report", e);
        }
    }

    public static void write(RunReport report, Path path) {
        String json = toJson(report);
        Path directory = path.toAbsolutePath().getParent();
        try {
            Path temporary = Files.createTempFile(directory, ".lockstep-", ".json");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + path + ": " + e.getMessage(), e);
        }
    }

    public static RunReport read(Path path) {
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8), path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path + ": " + e.getMessage(), e);
        }
    }

    public static RunReport parse(String json, String source) {
        RunReport report;
        try {
            report = MAPPER.readValue(json, RunReport.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(source + " is not a lockstep report: " + e.getMessage(), e);
        }
        if (report.schemaVersion() > RunReport.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "%s was written by a newer lockstep (schema %d, this build reads %d) — upgrade to read it"
                            .formatted(source, report.schemaVersion(), RunReport.SCHEMA_VERSION));
        }
        return report;
    }
}
