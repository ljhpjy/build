package com.dsmod.deepseek;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native DSML tool-call parser and protocol text cleaner.
 *
 * The model emits calls in the DSML tag syntax, e.g.
 *
 *   <|DSML|calls>
 *       <|DSML|invoke name="exec_shell">
 *           <|DSML|parameter name="command" string="true">uname -a</|DSML|parameter>
 *       </|DSML|invoke>
 *   </|DSML|calls>
 *
 * Full-width pipes, single/double pipes, optional inner spaces, calls vs
 * tool_calls, quoted / unquoted attribute values and truncated (unclosed)
 * invocations are all tolerated.
 */
public final class ToolKit {
    /** One parsed tool call. */
    public static final class Call {
        public String name = "";
        public String arguments = "{}";
        public String raw = "";
        public int index;
    }

    private static final class Tag {
        boolean close;
        String name;
        int start;
        int end;
        Map<String, String> attrs = new HashMap<>();
    }

    private ToolKit() {}

    /* ------------------------------------------------------------------ */
    /* Tag scanner                                                         */
    /* ------------------------------------------------------------------ */

    private static boolean isTagChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static List<Tag> scanTags(String text) {
        List<Tag> tags = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int lt = text.indexOf('<', i);
            if (lt < 0) break;
            int gt = text.indexOf('>', lt);
            if (gt < 0 || gt - lt > 256) {
                i = lt + 1;
                continue;
            }
            String inner = text.substring(lt + 1, gt).replace('\uFF5C', '|');
            String t = inner.trim();
            boolean close = false;
            if (t.startsWith("/")) {
                close = true;
                t = t.substring(1).trim();
            }
            // strip one or two leading pipes
            while (t.startsWith("|")) t = t.substring(1).trim();
            if (!t.regionMatches(true, 0, "DSML", 0, 4)) {
                i = gt + 1;
                continue;
            }
            t = t.substring(4).trim();
            while (t.startsWith("|")) t = t.substring(1).trim();
            int sp = 0;
            while (sp < t.length() && isTagChar(t.charAt(sp))) sp++;
            String name = t.substring(0, sp).toLowerCase();
            if (!(name.equals("calls") || name.equals("tool_calls")
                    || name.equals("invoke") || name.equals("parameter"))) {
                i = gt + 1;
                continue;
            }
            Tag tag = new Tag();
            tag.close = close;
            tag.name = name;
            tag.start = lt;
            tag.end = gt + 1;
            tag.attrs = parseAttrs(t.substring(sp));
            tags.add(tag);
            i = gt + 1;
        }
        return tags;
    }

    private static Map<String, String> parseAttrs(String s) {
        Map<String, String> map = new HashMap<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t'
                    || s.charAt(i) == '/' || s.charAt(i) == '>')) i++;
            if (i >= s.length()) break;
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i))
                    || s.charAt(i) == '_' || s.charAt(i) == '-')) i++;
            if (i == start) {
                i++;
                continue;
            }
            String key = s.substring(start, i).toLowerCase();
            while (i < s.length() && s.charAt(i) == ' ') i++;
            if (i >= s.length() || s.charAt(i) != '=') continue;
            i++;
            while (i < s.length() && s.charAt(i) == ' ') i++;
            String val = "";
            if (i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                char q = s.charAt(i++);
                int vs = i;
                while (i < s.length() && s.charAt(i) != q) i++;
                val = s.substring(vs, i);
                if (i < s.length()) i++;
            } else {
                int vs = i;
                while (i < s.length() && s.charAt(i) != ' ' && s.charAt(i) != '\t') i++;
                val = s.substring(vs, i);
            }
            map.put(key, val);
        }
        return map;
    }

    private static JSONObject parseParams(String body) {
        JSONObject args = new JSONObject();
        List<Tag> tags = scanTags(body);
        for (int i = 0; i < tags.size(); i++) {
            Tag t = tags.get(i);
            if (t.close || !t.name.equals("parameter")) continue;
            int end = body.length();
            for (int j = i + 1; j < tags.size(); j++) {
                Tag n = tags.get(j);
                if (n.name.equals("parameter")) {
                    end = n.start;
                    break;
                }
            }
            String name = t.attrs.get("name");
            if (name == null) continue;
            name = name.trim();
            if (name.isEmpty()) continue;
            String raw = body.substring(t.end, end);
            String stringAttr = t.attrs.get("string");
            boolean asString = true;
            if (stringAttr != null) {
                String v = stringAttr.trim().toLowerCase();
                asString = !("false".equals(v) || "0".equals(v) || "no".equals(v));
            }
            try {
                if (asString) {
                    args.put(name, raw.trim());
                } else {
                    String trimmed = raw.trim();
                    if (trimmed.isEmpty()) {
                        args.put(name, "");
                    } else {
                        try {
                            args.put(name, new JSONTokener(trimmed).nextValue());
                        } catch (Throwable t2) {
                            args.put(name, trimmed);
                        }
                    }
                }
            } catch (Throwable ignore) { }
        }
        return args;
    }

    /* ------------------------------------------------------------------ */
    /* Parse calls                                                         */
    /* ------------------------------------------------------------------ */

    public static List<Call> parseCalls(String text) {
        List<Call> calls = new ArrayList<>();
        if (text == null || text.isEmpty()) return calls;
        List<Tag> tags = scanTags(text);
        int i = 0;
        while (i < tags.size()) {
            Tag t = tags.get(i);
            if (t.close || !t.name.equals("invoke")) {
                i++;
                continue;
            }
            int bodyEnd = text.length();
            int next = i + 1;
            while (next < tags.size()) {
                Tag n = tags.get(next);
                if (n.name.equals("invoke")) {
                    bodyEnd = n.start;
                    break;
                }
                next++;
            }
            String name = t.attrs.get("name");
            name = name == null ? "" : name.trim();
            String body = text.substring(t.end, bodyEnd);
            JSONObject args = parseParams(body);
            if (args.length() == 0) {
                String trimmed = body.trim();
                if (trimmed.startsWith("{")) {
                    try {
                        args = new JSONObject(trimmed);
                    } catch (Throwable ignore) { }
                }
            }
            if (!name.isEmpty()) {
                Call call = new Call();
                call.name = name;
                call.arguments = args.toString();
                call.raw = text.substring(t.start, Math.min(bodyEnd, text.length()));
                call.index = t.start;
                calls.add(call);
            }
            i = next;
        }
        return calls;
    }

    /* ------------------------------------------------------------------ */
    /* Clean visible text                                                  */
    /* ------------------------------------------------------------------ */

    public static String stripMarkup(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = removeDsmlBlocks(text);
        s = removeEndTokens(s);
        s = removeProtocolLines(s);
        s = s.replaceAll("```[a-zA-Z0-9_+-]*[ \\t]*\\r?\\n[ \\t]*\\r?\\n?[ \\t]*```(\\r?\\n)?", "");
        s = s.replaceAll("\\n{3,}", "\\n\\n");
        return s.trim();
    }

    private static String removeDsmlBlocks(String text) {
        List<Tag> tags = scanTags(text);
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            Tag t = tags.get(i);
            if (t.close) continue;
            boolean isCalls = t.name.equals("calls") || t.name.equals("tool_calls");
            if (isCalls) {
                int end = text.length();
                for (int j = i + 1; j < tags.size(); j++) {
                    Tag n = tags.get(j);
                    if (n.close && (n.name.equals("calls") || n.name.equals("tool_calls"))) {
                        end = n.end;
                        break;
                    }
                }
                ranges.add(new int[]{t.start, end});
            } else if (t.name.equals("invoke") || t.name.equals("parameter")) {
                if (covered(ranges, t.start)) continue;
                int end = text.length();
                for (int j = i + 1; j < tags.size(); j++) {
                    Tag n = tags.get(j);
                    if (n.close && n.name.equals(t.name)) {
                        end = n.end;
                        break;
                    }
                }
                ranges.add(new int[]{t.start, end});
            }
        }
        return cutRanges(text, ranges);
    }

    private static boolean covered(List<int[]> ranges, int index) {
        for (int[] r : ranges) {
            if (r[0] <= index && index < r[1]) return true;
        }
        return false;
    }

    private static String cutRanges(String text, List<int[]> ranges) {
        if (ranges.isEmpty()) return text;
        // merge overlaps
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : ranges) {
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], r[1]);
            } else {
                merged.add(new int[]{r[0], r[1]});
            }
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (int[] r : merged) {
            if (r[0] > cursor) sb.append(text, cursor, r[0]);
            cursor = Math.max(cursor, r[1]);
        }
        if (cursor < text.length()) sb.append(text, cursor, text.length());
        return sb.toString();
    }

    private static String removeEndTokens(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int lt = s.indexOf('<', i);
            if (lt < 0) {
                sb.append(s, i, s.length());
                break;
            }
            sb.append(s, i, lt);
            int gt = s.indexOf('>', lt);
            if (gt < 0) {
                sb.append(s, lt, s.length());
                break;
            }
            String inner = s.substring(lt + 1, gt)
                    .replace('\uFF5C', '|').replace("|", "").replace(" ", "").replace("\t", "");
            if (inner.equalsIgnoreCase("END")) {
                i = gt + 1;
                continue;
            }
            sb.append(s, lt, gt + 1);
            i = gt + 1;
        }
        return sb.toString();
    }

    private static String removeProtocolLines(String s) {
        StringBuilder sb = new StringBuilder();
        String[] lines = s.split("\\n", -1);
        for (String line : lines) {
            String t = line.trim();
            String core = t.replaceAll("^[>#*`~\\-\\s\\d.]+", "");
            if (core.startsWith("RESULT:") || core.startsWith("RESULT\uFF1A")
                    || core.startsWith("DO:") || core.startsWith("DO\uFF1A")) {
                continue;
            }
            if (t.matches("^#{1,4}\\s*(System|User|Assistant|Tool_calls|Tool_call_result|Tool_functions|Messages|CONTEXT_ATTACHMENT)\\s*[:\uFF1A]?\\s*$")) {
                continue;
            }
            if (t.matches("^#{1,4}\\s*(SYSTEM|USER|ASSISTANT)_ROLE_MESSAGES\\s*[:\uFF1A]?\\s*$")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Streaming protocol filter                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Line-buffered stream filter. Drops DSML blocks, RESULT/DO protocol
     * lines, END tokens and fake section headers before they reach SSE.
     * Non-protocol lines pass through unchanged (one line of latency).
     */
    public static final class StreamFilter {
        private final StringBuilder pending = new StringBuilder();
        private boolean droppingDsml = false;

        public String push(String delta) {
            if (delta == null || delta.isEmpty()) return "";
            pending.append(delta);
            StringBuilder out = new StringBuilder();
            int idx;
            while ((idx = pending.indexOf("\n")) >= 0) {
                String line = pending.substring(0, idx + 1);
                pending.delete(0, idx + 1);
                String safe = filterLine(line);
                if (safe != null) out.append(safe);
            }
            // No line break yet: release a safe prefix so single-line answers
            // still stream. Keep a short tail that may start a protocol tag;
            // a large pending buffer (pathological single line) is released too.
            if (!droppingDsml && pending.length() > 64) {
                boolean safe = pending.indexOf('<') < 0 || pending.length() > 4096;
                if (safe) {
                    int cut = pending.length() - 16;
                    out.append(pending, 0, cut);
                    pending.delete(0, cut);
                }
            }
            return out.toString();
        }

        public String flush() {
            if (pending.length() == 0) return "";
            String line = pending.toString();
            pending.setLength(0);
            String safe = filterLine(line);
            return safe == null ? "" : safe;
        }

        private String filterLine(String line) {
            String trimmed = line.trim();
            if (droppingDsml) {
                if (trimmed.contains("</") && (trimmed.contains("DSML")
                        || trimmed.contains("invoke") || trimmed.contains("parameter"))) {
                    droppingDsml = false;
                }
                return null;
            }
            if (trimmed.startsWith("<") && (trimmed.contains("DSML") || trimmed.startsWith("<|tool"))) {
                boolean complete = trimmed.contains("</") && trimmed.contains(">");
                if (!complete) droppingDsml = true;
                return null;
            }
            if (trimmed.startsWith("<")) {
                String inner = trimmed.replace('\uFF5C', '|').replace("|", "")
                        .replace(" ", "").replace("\t", "").toUpperCase();
                if (inner.startsWith("<") && inner.contains("END")) return null;
            }
            String core = trimmed.replaceAll("^[>#*`~\\-\\s\\d.]+", "");
            if (core.startsWith("RESULT:") || core.startsWith("RESULT\uFF1A")
                    || core.startsWith("DO:") || core.startsWith("DO\uFF1A")) {
                return null;
            }
            if (trimmed.matches("^#{1,4}\\s*(System|User|Assistant|Tool_calls|Tool_call_result|Tool_functions|Messages|CONTEXT_ATTACHMENT)\\s*[:\uFF1A]?\\s*$")) {
                return null;
            }
            if (trimmed.matches("^#{1,4}\\s*(SYSTEM|USER|ASSISTANT)_ROLE_MESSAGES\\s*[:\uFF1A]?\\s*$")) {
                return null;
            }
            return line;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Argument coercion                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Coerce stringified scalars/arrays/objects back to their declared
     * types, and unwrap a single extra {args:{...}} layer. Missing values
     * are never invented; unknown tool names are passed through unchanged.
     */
    public static String coerceArguments(String name, String argsJson, JSONArray tools) {
        if (tools == null || argsJson == null) return argsJson;
        JSONObject schema = findSchema(name, tools);
        if (schema == null) return argsJson;
        JSONObject args;
        try {
            args = new JSONObject(argsJson);
        } catch (Throwable t) {
            return argsJson;
        }
        JSONObject props = schema.optJSONObject("properties");
        if (props == null) return argsJson;

        JSONArray keys = args.names();
        if (keys != null && keys.length() == 1) {
            String only = keys.optString(0);
            JSONObject inner = args.optJSONObject(only);
            if (inner != null && ("args".equals(only) || "arguments".equals(only) || "parameters".equals(only))
                    && hasAnyProp(inner, props)) {
                args = inner;
            }
        }

        JSONArray outKeys = args.names();
        if (outKeys == null) return argsJson;
        for (int i = 0; i < outKeys.length(); i++) {
            String key = outKeys.optString(i);
            JSONObject prop = props.optJSONObject(key);
            if (prop == null) continue;
            Object value = args.opt(key);
            Object coerced = coerceValue(value, prop);
            if (coerced != null) {
                try {
                    args.put(key, coerced);
                } catch (Throwable ignore) { }
            }
        }
        return args.toString();
    }

    private static JSONObject findSchema(String name, JSONArray tools) {
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            JSONObject fn = tool.optJSONObject("function");
            if (fn == null) fn = tool;
            if (name.equals(fn.optString("name", ""))) {
                return fn.optJSONObject("parameters");
            }
        }
        return null;
    }

    private static boolean hasAnyProp(JSONObject obj, JSONObject props) {
        JSONArray keys = obj.names();
        if (keys == null) return false;
        for (int i = 0; i < keys.length(); i++) {
            if (props.has(keys.optString(i))) return true;
        }
        return false;
    }

    private static Object coerceValue(Object value, JSONObject schema) {
        if (value == null) return null;
        String type = schema.optString("type", "");
        if (type.isEmpty()) {
            JSONArray types = schema.optJSONArray("type");
            if (types != null && types.length() > 0) type = types.optString(0);
        }
        try {
            if ("integer".equals(type)) {
                if (value instanceof Number) return ((Number) value).longValue();
                if (value instanceof String) {
                    String s = ((String) value).trim();
                    if (s.matches("-?\\d+")) return Long.parseLong(s);
                }
                return null;
            }
            if ("number".equals(type)) {
                if (value instanceof Number) return ((Number) value).doubleValue();
                if (value instanceof String) {
                    String s = ((String) value).trim();
                    if (!s.isEmpty()
                            && s.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
                        return Double.parseDouble(s);
                    }
                }
                return null;
            }
            if ("boolean".equals(type)) {
                if (value instanceof Boolean) return value;
                if (value instanceof String) {
                    String s = ((String) value).trim().toLowerCase();
                    if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return Boolean.TRUE;
                    if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return Boolean.FALSE;
                }
                return null;
            }
            if ("string".equals(type)) {
                if (value instanceof String) return value;
                if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
                return null;
            }
            if ("array".equals(type)) {
                if (value instanceof JSONArray) return value;
                if (value instanceof String) {
                    String s = ((String) value).trim();
                    if (s.startsWith("[")) {
                        try {
                            return new JSONArray(s);
                        } catch (Throwable ignore) { }
                    }
                }
                return null;
            }
            if ("object".equals(type)) {
                if (value instanceof JSONObject) return value;
                if (value instanceof String) {
                    String s = ((String) value).trim();
                    if (s.startsWith("{")) {
                        try {
                            return new JSONObject(s);
                        } catch (Throwable ignore) { }
                    }
                }
                return null;
            }
        } catch (Throwable ignore) { }
        return null;
    }

    public static boolean hasFabricatedProtocol(String text) {
        if (text == null || text.isEmpty()) return false;
        String[] lines = text.split("\\n", -1);
        for (String line : lines) {
            String t = line.trim();
            String core = t.replaceAll("^[>#*`~\\-\\s\\d.]+", "");
            if ((core.startsWith("RESULT:") || core.startsWith("RESULT\uFF1A")) && core.contains("{")) {
                return true;
            }
            if (t.matches("^#{1,4}\\s*(System|User|Assistant|Tool_calls|Tool_call_result|Tool_functions|Messages)\\s*[:\uFF1A]?\\s*$")) {
                return true;
            }
        }
        return false;
    }
}
