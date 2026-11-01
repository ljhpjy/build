package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps OmniRoute-style tool invocations onto Deekseep's local tools.
 *
 * <p>Rebuilt from the 1.7.5 APK dex (R8-obfuscated); the original had 4 fields and 11 methods.
 * Only the entry points needed by {@link LocalApiGateway} and {@link OpenAiToolBridge} are
 * reproduced here.</p>
 */
final class OmniRouteToolBridge {
    /** OmniRoute names its tool schema block differently from the OpenAI-compatible one. */
    static final String OMNI_BLOCK_OPEN = "[[OMNIROUTE_TOOL_CALL]]";
    static final String OMNI_BLOCK_CLOSE = "[[/OMNIROUTE_TOOL_CALL]]";
    static final String OMNI_SCHEMA_MARKER = "[[OMNIROUTE_LOCAL_TOOLS_V1]]";
    static final String DEFAULT_NAMESPACE = "local";

    private OmniRouteToolBridge() {}

    // ------------------------------------------------------------ detection

    /** True when the payload or reply uses OmniRoute's tool-block syntax. */
    static boolean isOmniRoute(String text) {
        if (text == null || text.length() == 0) return false;
        return text.contains(OMNI_BLOCK_OPEN) || text.contains(OMNI_SCHEMA_MARKER);
    }

    /** True when the request body carries an OmniRoute-specific field. */
    static boolean isOmniRouteRequest(JSONObject request) {
        if (request == null) return false;
        if (request.has("omni_route") || request.has("omniroute")) return true;
        JSONObject metadata = request.optJSONObject("metadata");
        return metadata != null && metadata.optBoolean("omni_route", false);
    }

    // ------------------------------------------------------------- parsing

    /** Extracts OmniRoute tool blocks and converts them to the shared {@code ToolCall} type. */
    static List<LocalApiGateway.ToolCall> extractToolCalls(String reply) {
        List<LocalApiGateway.ToolCall> calls = new ArrayList<>();
        if (reply == null || reply.length() == 0) return calls;
        int from = 0;
        int seq = 0;
        while (true) {
            int start = reply.indexOf(OMNI_BLOCK_OPEN, from);
            if (start < 0) break;
            int bodyStart = start + OMNI_BLOCK_OPEN.length();
            int end = reply.indexOf(OMNI_BLOCK_CLOSE, bodyStart);
            if (end < 0) break;
            String payload = reply.substring(bodyStart, end);
            from = end + OMNI_BLOCK_CLOSE.length();
            try {
                JSONObject call = new JSONObject(payload.trim());
                String name = call.optString("name", call.optString("tool", ""));
                if (name.length() == 0) continue;
                String id = call.optString("id", "");
                if (id.length() == 0) {
                    id = "call_omni_" + Integer.toHexString(++seq)
                            + "_" + Integer.toHexString((int) (System.nanoTime() & 0xFFFF));
                }
                JSONObject arguments = call.optJSONObject("arguments");
                if (arguments == null) arguments = call.optJSONObject("input");
                if (arguments == null) arguments = new JSONObject();
                String namespace = call.optString("namespace", DEFAULT_NAMESPACE);
                String qualified = DEFAULT_NAMESPACE.equals(namespace) || namespace.length() == 0
                        ? name : namespace + "." + name;
                calls.add(new LocalApiGateway.ToolCall(id, qualified, arguments));
            } catch (Throwable ignored) {}
        }
        return LocalApiGateway.dedupeToolCalls(calls);
    }

    /** Strips OmniRoute tool blocks from visible assistant text. */
    static String stripToolCalls(String reply) {
        if (reply == null) return "";
        StringBuilder sb = new StringBuilder();
        int from = 0;
        while (true) {
            int start = reply.indexOf(OMNI_BLOCK_OPEN, from);
            if (start < 0) {
                sb.append(reply, from, reply.length());
                break;
            }
            sb.append(reply, from, start);
            int end = reply.indexOf(OMNI_BLOCK_CLOSE, start + OMNI_BLOCK_OPEN.length());
            if (end < 0) break;
            from = end + OMNI_BLOCK_CLOSE.length();
        }
        String stripped = sb.toString().trim();
        if (LocalApiGateway.isDisclaimer(stripped)) return "";
        return stripped;
    }

    // ------------------------------------------------------------- encoding

    /** Encodes shared tool calls back into OmniRoute's block syntax. */
    static String encodeToolCalls(List<LocalApiGateway.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (LocalApiGateway.ToolCall call : calls) {
            if (call == null) continue;
            try {
                JSONObject body = new JSONObject();
                body.put("id", call.id);
                body.put("name", call.name);
                body.put("arguments", call.arguments);
                if (sb.length() > 0) sb.append('\n');
                sb.append(OMNI_BLOCK_OPEN).append('\n')
                        .append(body.toString()).append('\n')
                        .append(OMNI_BLOCK_CLOSE);
            } catch (Throwable ignored) {}
        }
        return sb.toString();
    }

    /** Normalizes an OmniRoute tool name so the local dispatcher can match it. */
    static String normalizeToolName(String name) {
        if (name == null) return "";
        String n = name.trim().toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < n.length()) n = n.substring(dot + 1);
        return n.replace('-', '_');
    }

    // ------------------------------------------------ request transformation

    /** Converts OmniRoute {@code tools[]} into the OpenAI function-tool shape. */
    static JSONArray toOpenAiTools(JSONArray omniTools) {
        JSONArray out = new JSONArray();
        if (omniTools == null) return out;
        for (int i = 0; i < omniTools.length(); i++) {
            JSONObject tool = omniTools.optJSONObject(i);
            if (tool == null) continue;
            try {
                JSONObject function = new JSONObject();
                function.put("name", normalizeToolName(tool.optString("name", "")));
                String description = tool.optString("description", "");
                if (description.length() > 0) function.put("description", description);
                JSONObject schema = tool.optJSONObject("parameters");
                if (schema == null) schema = tool.optJSONObject("input_schema");
                if (schema == null) schema = new JSONObject().put("type", "object");
                function.put("parameters", schema);
                JSONObject entry = new JSONObject();
                entry.put("type", "function");
                entry.put("function", function);
                out.put(entry);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Converts already-dispatched results back into OmniRoute's result blocks. */
    static String encodeResults(List<String> toolNames, List<String> payloads) {
        if (toolNames == null || payloads == null) return "";
        int count = Math.min(toolNames.size(), payloads.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("[[OMNIROUTE_TOOL_RESULT name=\"")
                    .append(normalizeToolName(toolNames.get(i)))
                    .append("\"]]\n")
                    .append(payloads.get(i) == null ? "" : payloads.get(i))
                    .append("\n[[/OMNIROUTE_TOOL_RESULT]]");
        }
        return sb.toString();
    }
}
