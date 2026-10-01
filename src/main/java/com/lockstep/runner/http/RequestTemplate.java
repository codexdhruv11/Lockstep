package com.lockstep.runner.http;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes a per-request value into a target's URL or body.
 *
 * <p>Without this, load-testing a write is close to impossible wherever the target enforces
 * uniqueness - which is most places worth testing. Every request carries a byte-identical body,
 * so the first one succeeds and the rest collide on a unique index. Measured on a real target:
 * creating a signal needs {@code (influencer_id, influencer_posted_at, trading_pair)} to be
 * unique, so the only way to drive it was to generate twenty thousand distinct bodies into one
 * config file - which then exceeded the parser's size limit. One placeholder replaces all of that.
 *
 * <p>Three placeholders, each guaranteed to differ between requests in a run:
 *
 * <pre>
 *   {{seq}}   a counter, 1 upward, unique within the run and stable across repeats of it
 *   {{uuid}}  a fresh random UUID
 *   {{now}}   the current instant, ISO-8601 UTC, at whatever precision the clock offers
 * </pre>
 *
 * <p>Deliberately not a template language. There is no arithmetic, no formatting and no nesting:
 * those invite a config that has to be debugged, and the whole value here is that the config stays
 * something a reader can take at face value.
 *
 * <p>{{now}} is the one to be careful with. {@link Instant#now()} is microsecond-precision on a
 * modern JDK, so collisions are vanishingly unlikely at ordinary rates - but it is a clock, not a
 * counter, and a clock can repeat. Where uniqueness has to hold rather than merely be likely, use
 * {{seq}} or {{uuid}}.
 */
public final class RequestTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(seq|uuid|now)}}");

    private RequestTemplate() {}

    /** Whether this text needs rendering per request. Null and plain text do not. */
    public static boolean isTemplated(String text) {
        return text != null && PLACEHOLDER.matcher(text).find();
    }

    /**
     * Replaces every placeholder in {@code text}. Each {{uuid}} and {{now}} in one call is
     * evaluated separately, so two placeholders in the same body do not share a value - if two
     * fields must match, use {{seq}}, which is one value for the whole request.
     */
    public static String render(String text, long sequence) {
        if (text == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 32);
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "seq" -> Long.toString(sequence);
                case "uuid" -> UUID.randomUUID().toString();
                case "now" -> Instant.now().toString();
                default -> matcher.group();
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The placeholders this understands, for an error message to list. */
    public static String supported() {
        return "{{seq}}, {{uuid}}, {{now}}";
    }
}
