package com.lockstep.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class HtmlReport {
    private static final String TEMPLATE_RESOURCE = "/report-template.html";

    private HtmlReport() {}

    public static String render(RunReport report) {
        String template = loadTemplate();
        String json = JsonExport.toJson(report);
        return template
                .replace("__TITLE__", "lockstep · " + escapeHtml(report.startedAt()))
                .replace("__DATA__", escapeForScriptTag(json));
    }

    public static void write(RunReport report, Path path) {
        String html = render(report);
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

    private static String loadTemplate() {
        try (InputStream in = HtmlReport.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("report template is missing from the jar: " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the report template", e);
        }
    }

    static String escapeForScriptTag(String json) {
        return json.replace("</", "<\\/")
                .replace(" ", "\\u2028")
                .replace(" ", "\\u2029");
    }

    private static String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
