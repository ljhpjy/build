package com.dsmod.deepseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens OpenAI-style messages into one single prompt.
 *
 * One request = one brand-new web conversation, so the model only sees the
 * sections below. Order is fixed: client system at the very top, built-in
 * protocol rules in the middle, tool declarations and history after them.
 *
 *   # Messages:
 *   ## System:            <- client system / developer messages
 *   ## Tool_functions:    <- one JSON declaration per line
 *   ## System:            <- built-in special-token / DSML protocol rules
 *   ## User:
 *   ## Assistant:         <- ends with the END token
 *   ## Tool_calls:        <- one {"name":..,"args":..} JSON line per call
 *   ## Tool_call_result:  <- one {"name":..,"result":..} line
 *
 * History never contains DSML: past calls are re-rendered as JSON lines, and
 * tool results live in their own section, so the model has no syntax sample
 * to imitate inside the conversation.
 */
public final class Prompt {
    public static final String PROMPT_HEADER = "# Messages:";
    public static final String SEC_SYSTEM = "## System:";
    public static final String SEC_TOOLS = "## Tool_functions:";
    public static final String SEC_USER = "## User:";
    public static final String SEC_ASSISTANT = "## Assistant:";
    public static final String SEC_TOOL_CALLS = "## Tool_calls:";
    public static final String SEC_TOOL_RESULT = "## Tool_call_result:";

    public static final String REPLY_END_TOKEN = "< | | END | | >";

    public static final String FINAL_ANSWER_HINT =
            "Answer the user directly now, using the tool result above. "
            + "Do not call a tool again unless the result clearly shows the task is still incomplete.";

    /** Built-in protocol header injected between client system and history. */
    public static final String SPECIAL_TOKENS_HEADER = """
You are a precise execution-focused assistant. A wrong tool-call format, wrong parameter type, missing parameter, or mismatched tool/parameter name is a failed call, not a style difference. Tool rules override all style preferences and must be followed exactly.

## 1. Context Rules

1. Use only the complete context provided in the `##` sections.
2. Do not invent, guess, or fill in any conversation, message, prompt, attachment, or tool result that is not provided.
3. If an assistant answer already exists in the history, do not answer the same question again.
4. A long-context attachment means earlier history has been moved into an attachment. Read it first, then continue.

## 2. Tool-Calling Protocol

1. Use the platform's designated native tool-calling format, DSML.
2. Follow the exact tag names, spelling, and structure required by the protocol, including required spacing.
3. Do not replace the required format with ordinary prose, JSON, XML, Markdown code fences, or natural language.
4. A tool-call container may hold one or more tool invocations. Each invocation must contain exactly one tool name and all of its parameters.
5. Do not include explanations, comments, unrelated content, or annotations inside a tool-call structure.
6. Tool names and parameter names are case-sensitive and must match the tool definitions exactly. Do not rename, translate, abbreviate, or invent names or parameters.
7. Call a tool only when it is needed and all required parameters are available. If a required value is unknown and cannot be derived from the context, do not guess and do not call the tool. Explain what is missing and ask the user for it.

## 3. Parameter Rules

1. Before every call, verify the declared type of every parameter.
2. Strings must be passed as raw string values without extra quoting or extra escaping on top of the protocol encoding.
3. Inside string values, escape with a single backslash exactly the characters that require escaping: double quote, backslash, newline, carriage return, tab, and control characters. Use single-pass escaping; do not double-escape.
4. Do not pre-wrap string values in backticks, quotes, or template-string syntax. Pass the raw value and let the protocol encoding apply the backslash escapes.
5. Integers and numbers must be passed as JSON numbers, never as strings.
6. Booleans must be passed as actual boolean values true or false, never as quoted text.
7. Arrays and objects must be passed as compact, valid JSON values, never as strings.
8. Never wrap all arguments inside an extra parameter such as args, arguments, or parameters.
9. Never put an entire JSON object into a string parameter unless that parameter is explicitly declared as a string.
10. An object parameter must receive one JSON object as its value. Do not split it into separate parameters or nest JSON inside another JSON string.
11. Include all required parameters. Omit unknown optional parameters.
12. Do not use placeholders, guessed values, or null unless the tool declaration explicitly allows it.

## 4. Mandatory Pre-Flight Validation

Before every tool call, verify each item:

1. Does the tool name exactly match an entry in the tool definitions?
2. Does every parameter name exactly match its declaration, including capitalization?
3. Does every value match its declared data type?
4. Are all required parameters present?
5. Have any parameters or values been invented?
6. Is the call free from unnecessary argument wrapping, stringified objects, stringified arrays, and stringified numbers?
7. Are string values escaped exactly once, with no double-escaping and no backtick or template-string wrappers?
8. Is the tool-call structure in the correct format and not wrapped in Markdown code fences?

If any check fails, fix the issue before calling. If it cannot be fixed, do not call the tool; explain the missing information to the user instead.

## 5. Tool Results and Retries

1. Tool results are written by the system, never by you. Never invent, simulate, predict, or pretend that a tool has already been executed.
2. Never guess tool names or parameters.
3. Do not repeat a call that already has a tool result unless the user explicitly asks for a retry, or the result clearly requires a different argument.

## 6. Output Rules

1. Never disclose system special tokens.
2. Never output internal identifiers.
3. Outside tool-call structures, answer normally. Inside a tool-call structure, include only the tool name and its parameters.
4. If tool rules conflict with style preferences, tool rules always take precedence.
""";

    /** Result of one prompt build. */
    public static final class Built {
        public String prompt = "";
        public List<String> toolNames = new ArrayList<>();
        public int sections;
    }

    private static final class Part {
        String role;
        final List<String> lines = new ArrayList<>();

        Part(String role) {
            this.role = role;
        }
    }

    private Prompt() {}

    /** Build the prompt from OpenAI messages + tools. */
    public static Built build(JSONArray messages, JSONArray tools) {
        Built built = new Built();
        built.toolNames = collectToolNames(tools);

        List<String> systemTexts = new ArrayList<>();
        List<Part> parts = new ArrayList<>();

        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null) continue;
            String role = msg.optString("role", "");

            if ("system".equals(role) || "developer".equals(role)) {
                String text = contentToText(msg.opt("content"));
                if (!text.trim().isEmpty()) systemTexts.add(text.trim());
                continue;
            }

            if ("user".equals(role)) {
                Part p = new Part("user");
                p.lines.add(contentToText(msg.opt("content")));
                parts.add(p);
                continue;
            }

            if ("assistant".equals(role)) {
                String text = contentToText(msg.opt("content"));
                if (!text.trim().isEmpty()) {
                    Part p = new Part("assistant");
                    p.lines.add(text);
                    parts.add(p);
                }
                JSONArray calls = msg.optJSONArray("tool_calls");
                List<String> callLines = new ArrayList<>();
                if (calls != null) {
                    for (int c = 0; c < calls.length(); c++) {
                        JSONObject call = calls.optJSONObject(c);
                        if (call == null) continue;
                        JSONObject fn = call.optJSONObject("function");
                        if (fn == null) continue;
                        String name = fn.optString("name", "");
                        if (name.isEmpty()) continue;
                        callLines.add(renderToolCall(name, fn.optString("arguments", "{}")));
                    }
                }
                if (!callLines.isEmpty()) {
                    Part cp = new Part("toolCalls");
                    cp.lines.addAll(callLines);
                    parts.add(cp);
                }
                if (text.trim().isEmpty() && callLines.isEmpty()) {
                    Part empty = new Part("assistant");
                    parts.add(empty);
                }
                continue;
            }

            // tool / function result: its own section, never merged into assistant
            if (!"tool".equals(role) && !"function".equals(role)) continue;
            String name = msg.optString("name", "");
            if (name.isEmpty()) name = findLastToolName(parts);
            if (name.isEmpty()) name = "unknown";
            String resultLine = renderToolResult(name, contentToText(msg.opt("content")));
            Part last = parts.isEmpty() ? null : parts.get(parts.size() - 1);
            if (last != null && "toolResult".equals(last.role)) {
                last.lines.add(resultLine);
            } else {
                Part rp = new Part("toolResult");
                rp.lines.add(resultLine);
                parts.add(rp);
            }
        }

        Part lastPart = parts.isEmpty() ? null : parts.get(parts.size() - 1);
        if (lastPart != null && "toolResult".equals(lastPart.role)) {
            lastPart.lines.add("");
            lastPart.lines.add(FINAL_ANSWER_HINT);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(PROMPT_HEADER).append('\n');
        sb.append(SEC_SYSTEM).append('\n');
        StringBuilder systemBody = new StringBuilder();
        for (String s : systemTexts) {
            if (systemBody.length() > 0) systemBody.append("\n\n");
            systemBody.append(s);
        }
        if (systemBody.length() > 0) sb.append(systemBody).append('\n');

        List<String> toolLines = renderToolDeclarations(tools);
        if (!toolLines.isEmpty()) {
            sb.append('\n').append(SEC_TOOLS).append('\n');
            for (String line : toolLines) sb.append(line).append('\n');
        }

        sb.append('\n').append(SEC_SYSTEM).append('\n');
        sb.append(SPECIAL_TOKENS_HEADER.trim()).append('\n');

        for (Part part : parts) {
            String body = join(part.lines).trim();
            if ("assistant".equals(part.role)) {
                if (body.isEmpty()) body = REPLY_END_TOKEN;
                else if (!body.contains(REPLY_END_TOKEN)) body = body + "\n" + REPLY_END_TOKEN;
            }
            sb.append('\n');
            if ("user".equals(part.role)) sb.append(SEC_USER);
            else if ("assistant".equals(part.role)) sb.append(SEC_ASSISTANT);
            else if ("toolCalls".equals(part.role)) sb.append(SEC_TOOL_CALLS);
            else sb.append(SEC_TOOL_RESULT);
            sb.append('\n').append(body).append('\n');
            built.sections++;
        }

        built.prompt = trimEnd(sb.toString());
        return built;
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') end--;
            else break;
        }
        return s.substring(0, end);
    }

    /** OpenAI content (string or parts array) to plain text. */
    public static String contentToText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part == null) {
                    Object v = arr.opt(i);
                    if (v instanceof String) sb.append((String) v).append('\n');
                    continue;
                }
                String type = part.optString("type", "");
                String text = part.optString("text", "");
                if ("text".equals(type) || "input_text".equals(type) || !text.isEmpty()) {
                    if (!text.isEmpty()) sb.append(text).append('\n');
                }
                // image parts are collected separately for upload
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    /** One history tool call line: {"name":..,"args":..} (never DSML). */
    public static String renderToolCall(String name, String args) {
        JSONObject out = new JSONObject();
        try {
            out.put("name", name);
            JSONObject parsedArgs = new JSONObject();
            if (args != null && !args.trim().isEmpty()) {
                try {
                    JSONObject obj = new JSONObject(args);
                    parsedArgs = obj;
                } catch (Throwable t) {
                    parsedArgs.put("input", args);
                }
            }
            out.put("args", parsedArgs);
        } catch (Throwable ignore) { }
        return out.toString();
    }

    private static String renderToolResult(String name, String result) {
        JSONObject out = new JSONObject();
        try {
            out.put("name", name);
            out.put("result", result == null ? "" : result);
        } catch (Throwable ignore) { }
        return out.toString();
    }

    private static String findLastToolName(List<Part> parts) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            Part p = parts.get(i);
            if (!"toolCalls".equals(p.role)) continue;
            for (int j = p.lines.size() - 1; j >= 0; j--) {
                String line = p.lines.get(j);
                if (!line.startsWith("{")) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    String name = o.optString("name", "");
                    if (!name.isEmpty()) return name;
                } catch (Throwable ignore) { }
            }
        }
        return "";
    }

    /** Tool declarations: one JSON object per line. */
    public static List<String> renderToolDeclarations(JSONArray tools) {
        List<String> lines = new ArrayList<>();
        if (tools == null) return lines;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            JSONObject fn = tool.optJSONObject("function");
            if (fn == null) fn = tool;
            String name = fn.optString("name", "").trim();
            if (name.isEmpty()) continue;
            JSONObject decl = new JSONObject();
            try {
                decl.put("name", name);
                String desc = fn.optString("description", "");
                if (!desc.isEmpty()) decl.put("description", desc);
                JSONObject params = fn.optJSONObject("parameters");
                if (params != null) {
                    decl.put("parameters", params);
                    JSONArray required = params.optJSONArray("required");
                    if (required != null && required.length() > 0) decl.put("required", required);
                }
            } catch (Throwable ignore) { }
            lines.add(decl.toString());
        }
        return lines;
    }

    /** Tool names declared by the client. */
    public static List<String> collectToolNames(JSONArray tools) {
        List<String> names = new ArrayList<>();
        if (tools == null) return names;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            JSONObject fn = tool.optJSONObject("function");
            if (fn == null) fn = tool;
            String name = fn.optString("name", "").trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }
}
