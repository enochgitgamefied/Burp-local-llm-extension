package localagent;

/** Small display-only Markdown subset. All source HTML is escaped; links/images stay inert. */
final class Markdown {
    private Markdown() {}
    static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    static String inline(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length();) {
            String marker = value.startsWith("**", i) ? "**" : value.charAt(i) == '`' ? "`" : value.charAt(i) == '*' ? "*" : null;
            if (marker != null) {
                int end = value.indexOf(marker, i + marker.length());
                if (end > i + marker.length()) {
                    String tag = marker.equals("**") ? "b" : marker.equals("`") ? "code" : "i";
                    out.append('<').append(tag).append('>').append(escape(value.substring(i + marker.length(), end)))
                        .append("</").append(tag).append('>'); i = end + marker.length(); continue;
                }
            }
            out.append(escape(value.substring(i, i + 1))); i++;
        }
        return out.toString();
    }
    static String render(String input) {
        String[] lines = input.replace("\r\n", "\n").split("\n", -1);
        StringBuilder html = new StringBuilder(); boolean code = false; String list = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i], trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (!list.isEmpty()) { html.append("</").append(list).append('>'); list = ""; }
                html.append(code ? "</p>" : "<p class='codeblock'>"); code = !code; continue;
            }
            if (code) { html.append(escape(line).replace(" ", "&nbsp;")).append("<br>"); continue; }
            String kind = trimmed.matches("[-*+] .*" ) ? "ul" : trimmed.matches("\\d+\\. .*" ) ? "ol" : "";
            if (!list.equals(kind)) { if (!list.isEmpty()) html.append("</").append(list).append('>'); if (!kind.isEmpty()) html.append('<').append(kind).append('>'); list = kind; }
            if (!kind.isEmpty()) { html.append("<li>").append(inline(trimmed.replaceFirst("^(?:[-*+]|\\d+\\.)\\s+", ""))).append("</li>"); continue; }
            if (trimmed.isEmpty()) continue;
            if (i + 1 < lines.length && trimmed.contains("|") && lines[i + 1].trim().matches("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?")) {
                html.append("<table width='100%'>"); row(html, trimmed, "th"); i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].isBlank()) { row(html, lines[i], "td"); i++; }
                i--; html.append("</table>"); continue;
            }
            if (trimmed.matches("#{1,6} .*")) { int n = Math.min(3, trimmed.indexOf(' ')); html.append("<h").append(n).append('>').append(inline(trimmed.substring(trimmed.indexOf(' ') + 1))).append("</h").append(n).append('>'); }
            else if (trimmed.matches("(?:-{3,}|\\*{3,}|_{3,})")) html.append("<hr>");
            else if (trimmed.startsWith("> ")) html.append("<blockquote>").append(inline(trimmed.substring(2))).append("</blockquote>");
            else html.append("<p>").append(inline(line)).append("</p>");
        }
        if (code) html.append("</p>"); if (!list.isEmpty()) html.append("</").append(list).append('>');
        return html.toString();
    }
    private static void row(StringBuilder html, String line, String tag) {
        String clean = line.trim().replaceAll("^\\||\\|$", ""); html.append("<tr>");
        for (String cell : clean.split("\\|", -1)) html.append('<').append(tag).append('>').append(inline(cell.trim())).append("</").append(tag).append('>');
        html.append("</tr>");
    }
}
