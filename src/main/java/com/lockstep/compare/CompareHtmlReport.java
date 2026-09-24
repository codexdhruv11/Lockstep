package com.lockstep.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CompareHtmlReport {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private CompareHtmlReport() {}

    public static String render(RunComparator.Comparison comparison, String baselineLabel, String currentLabel) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("baselineLabel", baselineLabel);
        data.put("currentLabel", currentLabel);
        data.put("budgetNanos", comparison.budgetNanos());
        data.put("runners", comparison.runners());
        data.put("warnings", comparison.warnings());
        data.put("spikePairs", comparison.spikePairs());

        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (IOException e) {
            throw new UncheckedIOException("could not serialise the comparison", e);
        }
        return template()
                .replace("__LATENCY_FORMATTER__", com.lockstep.report.HtmlReport.LATENCY_FORMATTER_JS)
                .replace("__DATA__", escapeForScriptTag(json));
    }

    public static void write(RunComparator.Comparison comparison, String baselineLabel,
            String currentLabel, Path path) {
        String html = render(comparison, baselineLabel, currentLabel);
        Path directory = path.toAbsolutePath().getParent();
        try {
            Path temporary = Files.createTempFile(directory, ".lockstep-", ".html");
            Files.writeString(temporary, html, StandardCharsets.UTF_8);
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

    static String escapeForScriptTag(String json) {
        return json.replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private static String template() {
        try (InputStream in = CompareHtmlReport.class.getResourceAsStream("/compare-template.html")) {
            if (in == null) {
                throw new IllegalStateException("compare template is missing from the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the compare template", e);
        }
    }
}
