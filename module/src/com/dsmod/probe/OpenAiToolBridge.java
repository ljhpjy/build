package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges OpenAI/Anthropic tool-call syntax onto Deekseep's text protocol.
 *
 * <p>Rebuilt from the 1.7.5 APK dex (R8-obfuscated); the original had 1 field and 27 methods.
 * Only the entry points consumed by {@link LocalApiGateway} are reproduced here.</p>
 */
final class OpenAiToolBridge {
    /** Marker the host uses to delimit the local-tool schema block. */
    static final String TOOL_BLOCK = "[[DEEKSEEP_LOCAL_TOOLS_V1]]";

    private static final Pattern BLOCK = Pattern.compile(
            "\\[\\[DEEKSEEP_TOOL_CALL\\]\\]([\\s\\S]*?)\\[\\[/DEEKSEEP_TOOL_CALL\\]\\]",
            Pattern.MULTILINE);

    private OpenAiToolBridge() {}

    // ------------------------------------------------------- OpenAI messages

    /** Flattens OpenAI {@code messages[]} into {@link LocalApiGateway.ChatTurn} records. */
    static List<LocalApiGateway.ChatTurn> parseOpenAiMessages(JSONArray messages) {
        List<LocalApiGateway.ChatTurn> turns = new ArrayList<>();
        if (messages == null) return turns;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "user");
            String content = LocalApiGateway.contentToText(message.opt("content"));
            if (content.length() == 0) continue;
            turns.add(new LocalApiGateway.ChatTurn(role, content));
        }
        return turns;
    }

    // ------------------------------------------------------ Responses input

    /** Flattens the OpenAI Responses {@code input} field into a single prompt. */
    static String parseResponsesInput(JSONObject request) {
        if (request == null) return "";
        Object input = request.opt("input");
        if (input instanceof String) return ((String) input).trim();
        StringBuilder sb = new StringBuilder();
        if (input instanceof JSONArray) {
            JSONArray array = (JSONArray) input;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String role = item.optString("role", "user");
                String text = LocalApiGateway.contentToText(item.opt("content"));
                if (text.length() == 0) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(role).append(": ").append(text);
            }
        }
        String instructions = request.optString("instructions", "").trim();
        if (instructions.length() > 0) {
            sb.insert(0, "system: " + instructions + "\n");
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------- tool-call I/O

    /** Extracts every local tool block from a model reply. */
    static List<LocalApiGateway.ToolCall> extractToolCalls(String reply) {
        List<LocalApiGateway.ToolCall> calls = new ArrayList<>();
        if (reply == null || reply.length() == 0) return calls;
        Matcher matcher = BLOCK.matcher(reply);
        int seq = 0;
        while (matcher.find()) {
            String payload = matcher.group(1);
            if (payload == null) continue;
            try {
                JSONObject call = new JSONObject(payload.trim());
                String name = call.optString("name", "");
                if (name.length() == 0) continue;
                String id = call.optString("id", "");
                if (id.length() == 0) {
                    id = "call_" + Integer.toHexString(++seq) + "_"
                            + Integer.toHexString((int) (System.nanoTime() & 0xFFFF));
                }
                JSONObject arguments = call.optJSONObject("arguments");
                if (arguments == null) arguments = new JSONObject();
                calls.add(new LocalApiGateway.ToolCall(id, name, arguments));
            } catch (Throwable ignored) {}
        }
        return LocalApiGateway.dedupeToolCalls(calls);
    }

    /** Removes tool blocks so the visible assistant text stays clean. */
    static String stripToolCalls(String reply) {
        if (reply == null) return "";
        String stripped = BLOCK.matcher(reply).replaceAll("").trim();
        if (LocalApiGateway.isDisclaimer(stripped)) return "";
        return stripped;
    }

    /** Encodes tool calls in the OpenAI {@code tool_calls[]} shape. */
    static JSONArray encodeOpenAiToolCalls(List<LocalApiGateway.ToolCall> calls) {
        JSONArray array = new JSONArray();
        if (calls == null) return array;
        for (LocalApiGateway.ToolCall call : calls) {
            if (call == null) continue;
            try {
                JSONObject function = new JSONObject();
                function.put("name", call.name);
                function.put("arguments", call.arguments.toString());
                JSONObject entry = new JSONObject();
                entry.put("id", call.id);
                entry.put("type", "function");
                entry.put("function", function);
                array.put(entry);
            } catch (Throwable ignored) {}
        }
        return array;
    }

    /** Encodes tool calls in the Anthropic {@code tool_use} content-block shape. */
    static JSONArray encodeAnthropicToolUse(List<LocalApiGateway.ToolCall> calls) {
        JSONArray array = new JSONArray();
        if (calls == null) return array;
        for (LocalApiGateway.ToolCall call : calls) {
            if (call == null) continue;
            try {
                JSONObject block = new JSONObject();
                block.put("type", "tool_use");
                block.put("id", call.id.startsWith("toolu_") ? call.id : "toolu_" + call.id);
                block.put("name", call.name);
                block.put("input", call.arguments);
                array.put(block);
            } catch (Throwable ignored) {}
        }
        return array;
    }

    // ------------------------------------------------ capability helpers

    /** True when the reply requests a terminal-style tool. */
    static boolean isTerminalTool(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.contains("bash") || n.contains("shell") || n.contains("terminal")
                || n.contains("exec") || n.contains("run");
    }

    /** True when the reply requests a file-editing tool. */
    static boolean isEditTool(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return "apply_patch".equals(n) || "str_replace_editor".equals(n)
                || "write".equals(n) || "edit".equals(n) || "multiedit".equals(n);
    }
}
