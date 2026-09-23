package com.lockstep.scenario;

import java.util.Map;

public final class Template {
    private Template() {}

    public static String render(String template, Map<String, Object> vars) {
        if (template == null || template.isEmpty() || template.indexOf("{{") < 0 || vars.isEmpty()) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length());
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf("{{", cursor);
            if (open < 0) {
                out.append(template, cursor, template.length());
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                out.append(template, cursor, template.length());
                break;
            }
            String name = template.substring(open + 2, close).trim();
            Object value = vars.get(name);
            out.append(template, cursor, open);
            if (value == null) {
                out.append(template, open, close + 2);
            } else {
                out.append(value);
            }
            cursor = close + 2;
        }
        return out.toString();
    }

    public static boolean hasUnresolved(String rendered) {
        return rendered != null && rendered.contains("{{");
    }
}
