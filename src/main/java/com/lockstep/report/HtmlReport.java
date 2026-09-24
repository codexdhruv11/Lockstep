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

    public static final String LATENCY_FORMATTER_JS = """
        function ns(v) {
          if (v === null || v === undefined) return "-";
          if (v < 0) return "-" + ns(-v);
          if (v < 1e3) return v + "ns";
          if (v < 1e6) return trim(v / 1e3) + "\\u00b5s";
          if (v < 1e9) return trim(v / 1e6) + "ms";
          return trim(v / 1e9) + "s";
          function trim(x) {
            var s = x >= 100 ? x.toFixed(0) : x.toFixed(1);
            return s.replace(/\\.0$/, "");
          }
        }
        """;

    private HtmlReport() {}

    public static String render(RunReport report) {
        String template = loadTemplate();
        String json = JsonExport.toJson(report);
        return template
                .replace("__TITLE__", "lockstep · " + escapeHtml(report.startedAt()))
                .replace("__LATENCY_FORMATTER__", LATENCY_FORMATTER_JS)
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
