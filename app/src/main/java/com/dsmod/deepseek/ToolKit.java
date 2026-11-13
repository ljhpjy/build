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
    private static final String TAG = "DSMOD";

    /** One parsed tool call. */
    public static final class Call {
        public String name = "";
        public String arguments = "{}";
        public String raw = "";
        public int index;
        /**
         * Which syntax produced this call: channel / tooltoken / dsml / xml /
         * barejson. Anything other than dsml means the model ignored the
         * documented protocol, which is worth surfacing in the logs.
         */
        public String format = "";
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

    /** Upper bound for a single DSML open tag; only guards against stray '<'. */
    private static final int MAX_TAG_LEN = 8192;

    /** Drop a trailing </...parameter> or </...invoke> left before the next tag. */
    private static String stripTrailingCloseTag(String value) {
        if (value == null || value.isEmpty()) return value;
        String s = value;
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        int close = s.lastIndexOf("</", end - 1);
        if (close >= 0) {
            String tail = s.substring(close, end).replace("\uFF5C", "|").toLowerCase();
            if (tail.endsWith(">") && (tail.contains("parameter") || tail.contains("invoke"))) {
                s = s.substring(0, close);
            }
        }
        return s;
    }

    private static List<Tag> scanTags(String text) {
        List<Tag> tags = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int lt = text.indexOf('<', i);
            if (lt < 0) break;
            int gt = text.indexOf('>', lt);
            // fuckds uses [^>]* with no length cap: a long attribute list must
            // not make the tag unparseable. Only bail out when there is no '>'
            // within a sane window (a bare '<' in prose).
            if (gt < 0 || gt - lt > MAX_TAG_LEN) {
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
            String rest = t.substring(sp).trim();
            // Lower-case variant: <dsml name="tool">{json}</dsml>. There is no
            // sub-tag after the DSML prefix, the attributes start immediately.
            if (name.isEmpty() || rest.startsWith("=")) {
                name = "dsml";
                sp = 0;
            } else if (name.startsWith("_")) {
                // <dsml_tool_call name="tool"> / <dsml_invoke ...> / <dsml_calls>
                name = "dsml";
            }
            if (!(name.equals("calls") || name.equals("tool_calls")
                    || name.equals("invoke") || name.equals("parameter")
                    || name.equals("dsml"))) {
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
            // Trim trailing closing tags / whitespace before the next opening
            // tag, otherwise </parameter> leaks into the value (fuckds matches
            // non-greedily up to the first </...parameter> instead).
            int valueStart = t.end;
            int valueEnd = end;
            String raw = body.substring(valueStart, valueEnd);
            raw = stripTrailingCloseTag(raw);
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
        return parseCalls(text, null);
    }

    /**
     * @param tools declared tools. Used ONLY to validate marker-less bare JSON:
     *              a {"name":..,"args":..} line is indistinguishable from an
     *              ordinary JSON example in the answer, so its name must match a
     *              declared tool. Marked formats (DSML / channel / tool_call)
     *              carry explicit protocol syntax and are trusted as-is, exactly
     *              like fuckds does.
     */
    public static List<Call> parseCalls(String text, JSONArray tools) {
        java.util.Set<String> names = collectToolNameSet(tools);
        // Run EVERY parser and merge. Chaining them with `if (isEmpty())` lost
        // calls whenever one reply mixed syntaxes (e.g. 3 DSML calls + 2 bare
        // JSON ones): the first parser returned non-empty and the rest never ran.
        List<Call> calls = new ArrayList<>();
        calls.addAll(parseNativeChannelCalls(text));
        calls.addAll(parseNativeToolTokenCalls(text));
        calls.addAll(parseDsmlCalls(text));
        calls.addAll(parseJsonToolCalls(text, names));
        calls = dedupeCalls(calls);
        // Deliberately NOT deduped: in an agent loop the same call repeated
        // verbatim is normal (re-observe after every step). Dropping duplicates
        // turns a whole turn into an empty reply (fuckds v2.0.3). Only one
        // parser can contribute here anyway, since each branch is gated on
        // calls.isEmpty().
        return calls;
    }

    /**
     * Native tool tokens like &lt;|tool▁calls▁begin|&gt; followed by a JSON body
     * carrying name / arguments (fuckds parseNativeToolCalls, shape 1).
     */
    public static List<Call> parseNativeToolTokenCalls(String text) {
        List<Call> calls = new ArrayList<>();
        if (text == null || text.isEmpty()) return calls;
        java.util.regex.Matcher m = NATIVE_TOOL_TOKEN.matcher(text);
        while (m.find()) {
            int tokenEnd = m.end();
            int braceAt = text.indexOf('{', tokenEnd);
            if (braceAt < 0 || braceAt - tokenEnd > 600) continue;
            int end = readJsonEnd(text, braceAt);
            if (end < 0) continue;
            Call call = callFromJson(text.substring(braceAt, end));
            if (call == null) continue;
            call.raw = text.substring(m.start(), end);
            call.index = m.start();
            call.format = "tooltoken";
            calls.add(call);
        }
        return calls;
    }

    /**
     * DeepSeek App native tool token:
     *
     *   &lt;|channel|&gt;commentary to=functions.NAME &lt;|constrain|&gt;json
     *   {"arg":"value"}
     *
     * The web build never emits this, which is why fuckds does not need it.
     */
    public static List<Call> parseNativeChannelCalls(String text) {
        List<Call> calls = new ArrayList<>();
        if (text == null || text.isEmpty()) return calls;
        String token = "<|channel|>";
        int from = 0;
        while (true) {
            int start = text.indexOf(token, from);
            if (start < 0) break;
            int nl = text.indexOf('\n', start);
            int headerEnd = nl < 0 ? text.length() : nl;
            String header = text.substring(start + token.length(), headerEnd);
            from = headerEnd;

            int toIdx = header.indexOf("to=functions.");
            if (toIdx < 0) continue;
            String rest = header.substring(toIdx + "to=functions.".length()).trim();
            int sp = 0;
            while (sp < rest.length() && isTagChar(rest.charAt(sp))) sp++;
            if (sp == 0) continue;
            String name = rest.substring(0, sp);

            // The JSON may sit on the same line as the header, so scan from the
            // header start rather than from the following line.
            int bracePos = text.indexOf('{', start + token.length());
            if (bracePos < 0) continue;
            int end = readJsonEnd(text, bracePos);
            if (end < 0) continue;
            JSONObject args;
            try {
                args = new JSONObject(text.substring(bracePos, end));
            } catch (Throwable t) {
                continue;
            }
            Call call = new Call();
            call.name = name;
            call.arguments = args.toString();
            call.raw = text.substring(start, end);
            call.index = start;
            call.format = "channel";
            calls.add(call);
            from = end;
        }
        return calls;
    }

    /** Brace matching that skips braces inside JSON strings; returns end (exclusive) or -1. */
    private static int readJsonEnd(String s, int open) {
        if (s == null || open < 0 || open >= s.length() || s.charAt(open) != '{') return -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    /** &lt;|tool▁calls▁begin|&gt; / &lt;|tool_calls|&gt; style tokens (App build variants). */
    private static final java.util.regex.Pattern NATIVE_TOOL_TOKEN =
            java.util.regex.Pattern.compile(
                    "<[ \\t]*\\|[^|<>\\r\\n]{0,64}tool[^|<>\\r\\n]{0,64}\\|[ \\t]*>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Remove a native tool token together with the JSON body that follows it. */
    private static String removeNativeToolTokenBlocks(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder();
        int from = 0;
        java.util.regex.Matcher m = NATIVE_TOOL_TOKEN.matcher(text);
        while (m.find()) {
            if (m.start() < from) continue;
            out.append(text, from, m.start());
            int tokenEnd = m.end();
            int end = tokenEnd;
            int braceAt = text.indexOf('{', tokenEnd);
            if (braceAt >= 0 && braceAt - tokenEnd <= 600) {
                int jsonEnd = readJsonEnd(text, braceAt);
                if (jsonEnd > 0 && text.substring(braceAt, jsonEnd).contains("\"name\"")) {
                    end = jsonEnd;
                }
            }
            from = end;
        }
        out.append(text.substring(from));
        return out.toString();
    }

    /** Remove native channel tool blocks and any leftover &lt;|...|&gt; special token. */
    private static String removeNativeChannelBlocks(String text) {
        if (text == null || text.isEmpty()) return text;
        String token = "<|channel|>";
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int start = text.indexOf(token, from);
            if (start < 0) break;
            out.append(text, from, start);
            // Keep a separator so removing the block does not glue the words
            // on either side together in the visible thinking text.
            if (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
                out.append(' ');
            }
            int nl = text.indexOf('\n', start);
            int headerEnd = nl < 0 ? text.length() : nl;
            String header = text.substring(start + token.length(), headerEnd);
            int end = headerEnd;
            // Only a real tool header is followed by a JSON body. A plain
            // <|channel|>final marker must not swallow the answer text.
            if (header.contains("to=functions.")) {
                int bracePos = text.indexOf('{', start + token.length());
                if (bracePos >= 0) {
                    int jsonEnd = readJsonEnd(text, bracePos);
                    if (jsonEnd > 0) end = jsonEnd;
                }
            }
            from = end;
        }
        out.append(text.substring(from));
        return out.toString().replaceAll("<\\|[^|<>\\r\\n]{0,40}\\|>", "");
    }

    /**
     * Silent fallback for the older <tool_call>{...}</tool_call> shape, which
     * some model revisions still emit instead of DSML. Same intent as fuckds:
     * a missing parse here turns a valid tool call into an empty reply.
     */
    private static List<Call> parseJsonToolCalls(String text) {
        return parseJsonToolCalls(text, null);
    }

    /**
     * null = no declaration supplied (internal call), accept everything.
     * An empty set means the client declared no tools at all, so a bare JSON
     * object can never be a call — keep it as ordinary content.
     */
    private static boolean allowedName(String name, java.util.Set<String> names) {
        if (names == null) return true;
        return names.contains(name);
    }

    /**
     * Remove exact duplicates produced by overlapping parsers. This is a
     * same-reply dedupe only: the same call repeated in a later turn is a
     * legitimate agent action and must never be dropped.
     */
    private static List<Call> dedupeCalls(List<Call> calls) {
        if (calls == null || calls.size() < 2) return calls;
        List<Call> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Call c : calls) {
            String key = c.name + "\u0000" + c.arguments;
            if (!seen.add(key)) continue;
            out.add(c);
        }
        return out;
    }

    /** Declared tool names, used to validate marker-less bare JSON calls. */
    public static java.util.Set<String> collectToolNameSet(JSONArray tools) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (tools == null) return out;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            JSONObject fn = tool.optJSONObject("function");
            if (fn == null) fn = tool;
            String name = fn.optString("name", "").trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    private static List<Call> parseJsonToolCalls(String text, java.util.Set<String> names) {
        List<Call> calls = new ArrayList<>();
        if (text == null || text.isEmpty()) return calls;
        // <toolcall> and <tool_call> are both in use; normalise for matching
        // only, the returned raw slice still comes from the original text.
        String work = text.replaceAll("(?i)<\\s*toolcall\\s*>", "<tool_call>")
                .replaceAll("(?i)<\\s*/\\s*toolcall\\s*>", "</tool_call>");
        String lower = work.toLowerCase();
        int from = 0;
        while (true) {
            int open = lower.indexOf("<tool_call", from);
            if (open < 0) break;
            int bodyStart = work.indexOf('>', open);
            if (bodyStart < 0) break;
            int close = lower.indexOf("</tool_call", bodyStart);
            if (close < 0) break;
            String body = work.substring(bodyStart + 1, close).trim();
            from = close + 1;
            Call call = callFromJson(body);
            if (call != null) calls.add(call);
        }
        if (calls.isEmpty()) {
            // Bare JSON objects: the App build prints each call as a plain
            // {"name":..,"args":..} line, often several per reply. Scan line by
            // line — the old whole-text check could only ever find one.
            List<int[]> fences = codeFenceRanges(work);
            int lineStart = 0;
            while (lineStart <= work.length()) {
                int nl = work.indexOf('\n', lineStart);
                int lineEnd = nl < 0 ? work.length() : nl;
                String t = work.substring(lineStart, lineEnd).trim();
                // Fenced JSON is an example the model is showing, not a call.
                if (!covered(fences, lineStart) && t.startsWith("{") && t.endsWith("}")) {
                    Call call = callFromJson(t);
                    if (call != null && allowedName(call.name, names)) {
                        call.format = "barejson";
                        calls.add(call);
                    }
                }
                lineStart = nl < 0 ? work.length() + 1 : nl + 1;
            }
            if (calls.isEmpty()) {
                String trimmed = work.trim();
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    Call call = callFromJson(trimmed);
                    if (call != null && allowedName(call.name, names)) {
                        call.format = "barejson";
                        calls.add(call);
                    }
                }
            }
        }
        return calls;
    }

    private static Call callFromJson(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(body);
            String name = obj.optString("name", "").trim();
            if (name.isEmpty()) return null;
            // A self-declared result is a fabrication, not a call: accepting it
            // would make the client execute the tool a second time with empty
            // arguments.
            if (obj.has("result") || obj.has("output") || obj.has("tool_result")) return null;
            JSONObject args = obj.optJSONObject("arguments");
            if (args == null) args = obj.optJSONObject("args");
            if (args == null) args = obj.optJSONObject("parameters");
            Call call = new Call();
            call.name = name;
            call.arguments = args == null ? "{}" : args.toString();
            call.raw = body;
            return call;
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** JSON from an attribute or body, tolerating HTML entities and noise. */
    private static JSONObject parseJsonLoose(String raw) {
        JSONObject out = new JSONObject();
        if (raw == null || raw.isEmpty()) return out;
        String s = raw.replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return out;
        try {
            return new JSONObject(s.substring(a, b + 1));
        } catch (Throwable ignore) {
            return out;
        }
    }

    /** <path>v</path><recursive>false</recursive> -> {"path":"v","recursive":false} */
    private static JSONObject xmlArgsToJson(String body) {
        JSONObject out = new JSONObject();
        if (body == null || body.isEmpty()) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<([A-Za-z0-9_.\\-]+)>([\\s\\S]*?)</\\1>")
                .matcher(body);
        while (m.find()) {
            String k = m.group(1);
            String v = m.group(2).trim();
            try {
                if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
                    out.put(k, Boolean.parseBoolean(v));
                } else if (v.matches("-?\\d{1,18}")) {
                    out.put(k, Long.parseLong(v));
                } else if (v.matches("-?\\d+\\.\\d+")) {
                    out.put(k, Double.parseDouble(v));
                } else {
                    out.put(k, v);
                }
            } catch (Throwable ignore) { }
        }
        return out;
    }

    private static List<Call> parseDsmlCalls(String text) {
        List<Call> calls = new ArrayList<>();
        if (text == null || text.isEmpty()) return calls;
        List<Tag> tags = scanTags(text);
        int i = 0;
        while (i < tags.size()) {
            Tag t = tags.get(i);
            boolean isInvoke = t.name.equals("invoke");
            boolean isDsml = t.name.equals("dsml");
            if (t.close || (!isInvoke && !isDsml)) {
                i++;
                continue;
            }
            int bodyEnd = text.length();
            int next = i + 1;
            while (next < tags.size()) {
                Tag n = tags.get(next);
                if (n.name.equals(t.name)) {
                    bodyEnd = n.start;
                    break;
                }
                next++;
            }
            String name = t.attrs.get("name");
            name = name == null ? "" : name.trim();
            if (isDsml) {
                // Arguments arrive three different ways; the model uses all of
                // them within a single conversation:
                //   <dsml name="t" args='{"a":1}'>   attribute (JSON)
                //   <dsml name="t">{"a":1}</dsml>     JSON body
                //   <dsml name="t"><a>1</a></dsml>     XML body
                String dsmlBody = text.substring(t.end, bodyEnd).trim();
                JSONObject dsmlArgs = new JSONObject();
                String attrArgs = t.attrs.get("args");
                if (attrArgs == null || attrArgs.trim().isEmpty()) {
                    attrArgs = t.attrs.get("arguments");
                }
                if (attrArgs != null && !attrArgs.trim().isEmpty()) {
                    dsmlArgs = parseJsonLoose(attrArgs.trim());
                } else if (dsmlBody.startsWith("{")) {
                    dsmlArgs = parseJsonLoose(dsmlBody);
                } else if (dsmlBody.startsWith("<")) {
                    dsmlArgs = xmlArgsToJson(dsmlBody);
                }
                if (!name.isEmpty()) {
                    Call call = new Call();
                    call.name = name;
                    call.arguments = dsmlArgs.toString();
                    call.raw = text.substring(t.start, Math.min(bodyEnd, text.length()));
                    call.index = t.start;
                    call.format = "dsml";
                    calls.add(call);
                }
                i = next;
                continue;
            }
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
                call.format = "dsml";
                calls.add(call);
            }
            i = next;
        }

        // Truncated output / missing closing tag: same fallback as fuckds.
        // Accept an <invoke ...> whose parameters are complete even when the
        // enclosing </invoke> or </calls> never arrived.
        if (calls.isEmpty()) {
            for (int k = 0; k < tags.size(); k++) {
                Tag t = tags.get(k);
                if (t.close || !t.name.equals("invoke")) continue;
                String name = t.attrs.get("name");
                name = name == null ? "" : name.trim();
                if (name.isEmpty()) continue;
                String body = text.substring(t.end);
                JSONObject args = parseParams(body);
                if (args.length() == 0) continue;
                Call call = new Call();
                call.name = name;
                call.arguments = args.toString();
                call.raw = text.substring(t.start);
                call.index = t.start;
                call.format = "dsml";
                calls.add(call);
                Logs.d(Logs.CHAT, TAG, "tool call recovered from truncated output: " + name);
                break;
            }
        }
        return calls;
    }

    /* ------------------------------------------------------------------ */
    /* Clean visible text                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Remove protocol markup only (no line filtering), safe to run over a whole
     * prompt: history must never hand the model a call-syntax template, and
     * upstream risk control flags exactly that pattern.
     */
    public static String scrubProtocol(String text) {
        return scrubProtocol(text, null);
    }

    public static String scrubProtocol(String text, java.util.Set<String> names) {
        if (text == null || text.isEmpty()) return text;
        String s = removeNativeChannelBlocks(text);
        s = removeNativeToolTokenBlocks(s);
        s = removeDsmlBlocks(s);
        s = removeToolCallBlocks(s);
        // Bare JSON is only stripped when the declared tool names are known.
        // For the assembled prompt (names == null) the ## FTC history lines
        // are legitimate content and must stay.
        if (names != null && !names.isEmpty()) s = removeBareCallLines(s, names);
        s = removeEndTokens(s);
        return s;
    }

    /**
     * &lt;toolcall&gt;...&lt;/toolcall&gt; / &lt;tool_call&gt;...&lt;/tool_call&gt;, including
     * the unclosed form that runs to the end of the text.
     */
    private static String removeToolCallBlocks(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = text.replaceAll("(?is)<\\s*tool_?call\\s*>[\\s\\S]*?<\\s*/\\s*tool_?call\\s*>", " ");
        s = s.replaceAll("(?is)<\\s*tool_?call\\s*>[\\s\\S]*$", " ");
        return s;
    }

    /**
     * Markdown fenced-code ranges (``` ... ```). JSON inside a fence is an
     * example the model is showing to the user, never a live tool call.
     */
    private static List<int[]> codeFenceRanges(String text) {
        List<int[]> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("```", i);
            if (open < 0) break;
            int close = text.indexOf("```", open + 3);
            int end = close < 0 ? text.length() : close + 3;
            out.add(new int[]{open, end});
            i = end;
        }
        return out;
    }

    /** A bare JSON object that declares its own tool result (fabricated). */
    private static boolean looksLikeFabricatedResult(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!o.has("name")) return false;
            return o.has("result") || o.has("output") || o.has("tool_result");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whole lines that are a bare {"name":..,"arguments":..} tool call.
     * The App build sometimes prints the call as plain JSON instead of using a
     * wrapper tag, and that text must never reach the client as content.
     */
    private static String removeBareCallLines(String text) {
        return removeBareCallLines(text, null);
    }

    /**
     * Drop bare tool-call JSON lines. With a name set, only declared tool names
     * are treated as calls — otherwise ordinary JSON in an answer (a config
     * sample, an API example) would be deleted.
     */
    private static String removeBareCallLines(String text, java.util.Set<String> names) {
        if (text == null || text.isEmpty()) return text;
        List<int[]> fences = codeFenceRanges(text);
        StringBuilder out = new StringBuilder();
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl;
            String line = text.substring(lineStart, lineEnd);
            String t = line.trim();
            // JSON inside a code fence is an example, not a call.
            boolean inFence = covered(fences, lineStart);
            if (!inFence) {
                if (t.startsWith("{") && t.endsWith("}")) {
                    // Drop a fabricated result line entirely, and drop real calls.
                    if (looksLikeFabricatedResult(t)) {
                        lineStart = nl < 0 ? text.length() + 1 : nl + 1;
                        continue;
                    }
                    Call c = callFromJson(t);
                    if (c != null && allowedName(c.name, names)) {
                        lineStart = nl < 0 ? text.length() + 1 : nl + 1;
                        continue;
                    }
                }
                // Also handle a call printed after a prefix ("结果：{\"name\":...").
                int jsonAt = t.indexOf("{\"name\"");
                if (jsonAt >= 0) {
                    int end = readJsonEnd(t, jsonAt);
                    if (end > 0) {
                        Call c = callFromJson(t.substring(jsonAt, end));
                        if (c != null && allowedName(c.name, names)) {
                            String prefix = t.substring(0, jsonAt).trim();
                            if (!prefix.isEmpty()) out.append(prefix).append('\n');
                            lineStart = nl < 0 ? text.length() + 1 : nl + 1;
                            continue;
                        }
                    }
                }
            }
            out.append(line);
            if (nl >= 0) out.append('\n');
            lineStart = nl < 0 ? text.length() + 1 : nl + 1;
        }
        return out.toString();
    }

    public static String stripMarkup(String text) {
        return stripMarkup(text, null);
    }

    public static String stripMarkup(String text, java.util.Set<String> names) {
        if (text == null || text.isEmpty()) return "";
        String s = removeNativeChannelBlocks(text);
        s = removeNativeToolTokenBlocks(s);
        s = removeDsmlBlocks(s);
        s = removeToolCallBlocks(s);
        s = removeBareCallLines(s, names);
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
            } else if (t.name.equals("invoke") || t.name.equals("parameter")
                    || t.name.equals("dsml")) {
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
        /** Declared tool names; bare JSON is only dropped when the name matches. */
        private final java.util.Set<String> toolNames;
        private boolean droppingDsml = false;
        /** JSON nesting inside a dropped DSML block; reaching 0 ends the drop. */
        private int dsmlDepth = 0;

        public StreamFilter() {
            this(null);
        }

        public StreamFilter(java.util.Set<String> toolNames) {
            this.toolNames = toolNames;
        }
        /** Skipping the JSON body that follows a native <|channel|> tool header. */
        private boolean droppingNative = false;
        /** Inside a <toolcall> / <tool_call> block. */
        private boolean droppingToolCall = false;
        /** JSON nesting depth while droppingNative is active (multi-line args). */
        private int nativeDepth = 0;
        /** Inside a ``` fence: example content, pass through untouched. */
        private boolean inFence = false;
        private int droppedLines = 0;
        private int releasedChars = 0;

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
                else droppedLines++;
            }
            // No line break yet: release a safe prefix so single-line answers
            // still stream. Keep a short tail that may start a protocol tag.
            // Text before the first '<' is always safe, even on one long line,
            // otherwise a whole single-line answer would be held back.
            // While a protocol block is being swallowed nothing may be released:
            // a long JSON argument line used to leak its first N chars here,
            // before the newline let filterLine recognise and drop it.
            if (!droppingDsml && !droppingNative && !droppingToolCall && !inFence
                    && pending.length() > 24) {
                int lt = pending.indexOf("<");
                if (lt < 0) {
                    // Hold anything that may be a bare tool-call JSON until the
                    // newline lets filterLine decide: at line start, or after a
                    // prefix like "结果：{\"name\":...". Otherwise the first N
                    // chars leak to the client before the newline arrives.
                    int jsonAt = pending.indexOf("{\"name\"");
                    if (jsonAt == 0) {
                        // whole pending is the start of a bare call: hold it
                    } else if (jsonAt > 0) {
                        out.append(pending, 0, jsonAt);
                        pending.delete(0, jsonAt);
                    } else {
                        char first = pending.charAt(0);
                        boolean maybeJson = first == '{' || first == '[';
                        if (!maybeJson && pending.length() > 64) {
                            int cut = pending.length() - 16;
                            out.append(pending, 0, cut);
                            pending.delete(0, cut);
                        }
                    }
                } else if (lt > 0) {
                    out.append(pending, 0, lt);
                    pending.delete(0, lt);
                }
            }
            releasedChars += out.length();
            return out.toString();
        }

        public String flush() {
            if (pending.length() == 0) return "";
            String line = pending.toString();
            pending.setLength(0);
            String safe = filterLine(line);
            if (safe == null) {
                droppedLines++;
                return "";
            }
            releasedChars += safe.length();
            return safe;
        }

        /** Protocol lines swallowed by this filter (visible-text leak check). */
        public int droppedLines() {
            return droppedLines;
        }

        /** Characters actually forwarded to the client. */
        public int releasedChars() {
            return releasedChars;
        }

        private String filterLine(String line) {
            String trimmed = line.trim();
            // Markdown fence: everything inside is example content, never a
            // live call, so pass it through untouched.
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                return line;
            }
            if (inFence) return line;
            // Native channel tool tokens (App build only): drop the header line
            // and the JSON argument line that follows it.
            if (trimmed.contains("<|channel|>") || trimmed.contains("<|constrain|>")) {
                // Header line is always protocol. Only a tool header is followed
                // by a JSON body; <|channel|>final is a plain marker.
                droppingNative = trimmed.contains("to=functions.");
                nativeDepth = 0;
                return null;
            }
            if (droppingNative) {
                if (trimmed.isEmpty()) return null;
                // Count braces so multi-line arguments are swallowed whole, not
                // just the first line.
                for (int i = 0; i < trimmed.length(); i++) {
                    char c = trimmed.charAt(i);
                    if (c == '{' || c == '[') nativeDepth++;
                    else if (c == '}' || c == ']') nativeDepth--;
                }
                if (nativeDepth <= 0) {
                    droppingNative = false;
                    nativeDepth = 0;
                }
                return null;
            }
            if (trimmed.startsWith("<|")) {
                // <|tool▁calls▁begin|> style token: its JSON body follows on the
                // next line, so keep dropping until that line is consumed.
                if (trimmed.toLowerCase().contains("tool")) droppingNative = true;
                return null;
            }
            // <toolcall> / <tool_call> wrapper: drop the whole block.
            String lowerTrim = trimmed.toLowerCase();
            if (lowerTrim.startsWith("<toolcall") || lowerTrim.startsWith("<tool_call")) {
                if (!trimmed.contains("</")) droppingToolCall = true;
                return null;
            }
            if (droppingToolCall) {
                if (trimmed.contains("</")) droppingToolCall = false;
                return null;
            }
            // A bare tool-call JSON object, either alone or after a prefix
            // such as "结果：{\"name\":...". Drop the JSON, keep the prefix.
            int jsonAt = trimmed.indexOf("{\"name\"");
            if (jsonAt >= 0) {
                int end = readJsonEnd(trimmed, jsonAt);
                if (end > 0) {
                    String json = trimmed.substring(jsonAt, end);
                    // Fabricated result lines never reach the client either.
                    if (looksLikeFabricatedResult(json)) {
                        String prefix = trimmed.substring(0, jsonAt).trim();
                        return prefix.isEmpty() ? null : prefix + "\n";
                    }
                    Call c = callFromJson(json);
                    if (c != null && allowedName(c.name, toolNames)) {
                        String prefix = trimmed.substring(0, jsonAt).trim();
                        return prefix.isEmpty() ? null : prefix + "\n";
                    }
                }
            }
            if (droppingDsml) {
                String lowerDsml = trimmed.toLowerCase();
                if (trimmed.contains("</") && (trimmed.contains("DSML")
                        || lowerDsml.contains("</dsml")
                        || trimmed.contains("invoke") || trimmed.contains("parameter"))) {
                    droppingDsml = false;
                    dsmlDepth = 0;
                    return null;
                }
                // End the drop as soon as the JSON body closes, even when the
                // closing tag never arrives. Without this, an unclosed
                // <dsml ...> in the thinking block swallowed every following
                // line and left only the sentence before it.
                int before = dsmlDepth;
                for (int i = 0; i < trimmed.length(); i++) {
                    char c = trimmed.charAt(i);
                    if (c == '{' || c == '[') dsmlDepth++;
                    else if (c == '}' || c == ']') dsmlDepth--;
                }
                if (dsmlDepth > 0) return null;   // still inside the JSON body
                droppingDsml = false;
                dsmlDepth = 0;
                if (before > 0) return null;      // this line closed the body
                // Nothing to drop here: fall through and treat it as ordinary
                // text. Returning null used to swallow the first line after the
                // header, which cut the tail off thinking sentences.
            }
            // Lower-case variant: <dsml name="tool">{json}</dsml>
            if (lowerTrim.contains("<dsml")) {
                if (!lowerTrim.contains("</dsml")) {
                    // Count braces on this line too: a single-line
                    // <dsml name="x">{...} without the closing tag must not put
                    // the filter into drop mode for the following text.
                    int depth = 0;
                    for (int i = 0; i < trimmed.length(); i++) {
                        char c = trimmed.charAt(i);
                        if (c == '{' || c == '[') depth++;
                        else if (c == '}' || c == ']') depth--;
                    }
                    if (depth > 0) {
                        droppingDsml = true;
                        dsmlDepth = depth;
                    }
                }
                return null;
            }
            if (trimmed.startsWith("<") && (trimmed.contains("DSML") || trimmed.startsWith("<|tool"))) {
                boolean complete = trimmed.contains("</") && trimmed.contains(">");
                if (!complete) {
                    droppingDsml = true;
                    dsmlDepth = 0;
                }
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
            if ("args".equals(only) || "arguments".equals(only) || "parameters".equals(only)) {
                Object raw = args.opt(only);
                JSONObject inner = args.optJSONObject(only);
                // Same as fuckds: the wrapper may carry a JSON string, not an object.
                if (inner == null && raw instanceof String) {
                    try {
                        inner = new JSONObject(((String) raw).trim());
                    } catch (Throwable ignore) { }
                }
                if (inner != null && hasAnyProp(inner, props)) args = inner;
            }
        }

        // Case-insensitive rename: only when the declared name is missing and
        // exactly one insensitive match exists (same rule as fuckds).
        List<String> propNames = new ArrayList<>();
        JSONArray propKeys = props.names();
        if (propKeys != null) {
            for (int i = 0; i < propKeys.length(); i++) propNames.add(propKeys.optString(i));
        }
        List<String> renames = new ArrayList<>();
        JSONArray renameKeys = args.names();
        if (renameKeys != null) {
            for (int i = 0; i < renameKeys.length(); i++) {
                String key = renameKeys.optString(i);
                if (props.has(key)) continue;
                String match = null;
                int hits = 0;
                for (String candidate : propNames) {
                    if (candidate.equalsIgnoreCase(key)) {
                        match = candidate;
                        hits++;
                    }
                }
                if (match != null && hits == 1) renames.add(key + "\u0000" + match);
            }
        }
        for (String pair : renames) {
            int sep = pair.indexOf('\u0000');
            String from = pair.substring(0, sep);
            String to = pair.substring(sep + 1);
            try {
                Object moved = args.opt(from);
                args.remove(from);
                args.put(to, moved);
                Logs.d(Logs.CHAT, TAG, "coerce rename " + from + " -> " + to);
            } catch (Throwable ignore) { }
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
            // A bare JSON object carrying its own result field is a fabricated
            // tool result: results are written by the system, never by the model.
            int jsonAt = t.indexOf("{\"name\"");
            if (jsonAt >= 0) {
                int end = readJsonEnd(t, jsonAt);
                if (end > 0) {
                    try {
                        JSONObject o = new JSONObject(t.substring(jsonAt, end));
                        if (o.has("result") || o.has("output") || o.has("tool_result")) return true;
                    } catch (Throwable ignore) { }
                }
            }
        }
        return false;
    }
}
