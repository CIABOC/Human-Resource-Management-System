package lk.gov.ciaboc.hrms.common;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces {{placeholder}} tokens. Used for notifications, letters and certificates. */
public final class TemplateRenderer {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, ?> vars) {
        if (template == null) return "";
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars == null ? null : vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
