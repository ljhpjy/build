package com.dsmod.deepseek;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Protocol-independent execution layer.
 *
 * OpenAI and Anthropic request bodies are normalized into one internal
 * shape (OpenAI-style messages + tools), then a single direct-engine round
 * runs: build prompt -> upload images -> completion -> parse DSML calls.
 * Multi-turn tool loops stay on the client side, exactly like fuckds.
 */
public final class ChatRunner {
    private static final String TAG = "DSMOD";
    private static final int MAX_IMAGES = 8;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** Normalized internal request. */
    public static final class Request {
        public String model = Config.defaultModel;
        public JSONArray messages = new JSONArray();
        public JSONArray tools = new JSONArray();
        public boolean stream;
        public boolean streamIncludeUsage;
        public boolean thinking = Config.thinking;
        public boolean search = Config.search;
        public List<String> imageUrls = new ArrayList<>();
    }

    /** Execution result. */
    public static final class Result {
        public String content = "";
        public String reasoning = "";
        public List<ToolKit.Call> toolCalls = new ArrayList<>();
        public String prompt = "";
        public long upstreamUsage = -1;
        public boolean fabricated;
        public long elapsedMs;
    }

    /** Streaming callbacks used by the local server. */
    public interface StreamSink {
        void onReasoning(String delta);
        void onContent(String delta);
    }

    private ChatRunner() {}

    /* ------------------------------------------------------------------ */
    /* OpenAI normalization                                                */
    /* ------------------------------------------------------------------ */

    public static Request normalizeOpenAI(JSONObject body) throws DeepSeekClient.ApiException {
        if (body == null) throw new DeepSeekClient.ApiException(400, "body must be a JSON object", "invalid_body");
        JSONArray messages = body.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            throw new DeepSeekClient.ApiException(400, "messages is required", "invalid_messages");
        }
        Request req = new Request();
        req.messages = messages;
        req.model = body.optString("model", Config.defaultModel);
        if (req.model.trim().isEmpty()) req.model = Config.defaultModel;
        req.tools = body.optJSONArray("tools");
        if (req.tools == null) req.tools = new JSONArray();
        req.stream = body.optBoolean("stream", false);
        JSONObject streamOptions = body.optJSONObject("stream_options");
        req.streamIncludeUsage = streamOptions != null && streamOptions.optBoolean("include_usage", false);
        if (body.has("thinking")) req.thinking = body.optBoolean("thinking", Config.thinking);
        else req.thinking = resolveThinking(req.model);
        if (body.has("search")) req.search = body.optBoolean("search", Config.search);
        else req.search = Config.search;
        req.imageUrls = collectImageUrls(messages);
        return req;
    }

    private static List<String> collectImageUrls(JSONArray messages) throws DeepSeekClient.ApiException {
        List<String> urls = new ArrayList<>();
        int lastUser = -1;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null && "user".equals(m.optString("role"))) {
                lastUser = i;
                break;
            }
        }
        if (lastUser < 0) return urls;
        JSONObject msg = messages.optJSONObject(lastUser);
        JSONArray content = msg == null ? null : msg.optJSONArray("content");
        if (content == null) return urls;
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) continue;
            String type = part.optString("type", "");
            if (!("image_url".equals(type) || "input_image".equals(type) || "image".equals(type))) continue;
            Object imageUrl = part.opt("image_url");
            String url = "";
            if (imageUrl instanceof JSONObject) url = ((JSONObject) imageUrl).optString("url", "");
            else if (imageUrl instanceof String) url = (String) imageUrl;
            if (url.isEmpty()) url = part.optString("url", part.optString("data", ""));
            if (!url.trim().isEmpty()) urls.add(url.trim());
        }
        if (urls.size() > MAX_IMAGES) {
            throw new DeepSeekClient.ApiException(400,
                    "too many images (max " + MAX_IMAGES + ")", "too_many_images");
        }
        return urls;
    }

    /* ------------------------------------------------------------------ */
    /* Anthropic normalization                                             */
    /* ------------------------------------------------------------------ */

    public static Request normalizeAnthropic(JSONObject body) throws DeepSeekClient.ApiException {
        if (body == null) throw new DeepSeekClient.ApiException(400, "body must be a JSON object", "invalid_body");
        JSONArray messages = body.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            throw new DeepSeekClient.ApiException(400, "messages is required", "invalid_messages");
        }
        Request req = new Request();
        req.model = body.optString("model", Config.defaultModel);
        if (req.model.trim().isEmpty()) req.model = Config.defaultModel;
        req.stream = body.optBoolean("stream", false);

        JSONObject thinking = body.optJSONObject("thinking");
        if (thinking != null) {
            String type = thinking.optString("type", "");
            if ("enabled".equals(type)) req.thinking = true;
            else if ("disabled".equals(type)) req.thinking = false;
        } else if (body.has("thinking")) {
            req.thinking = body.optBoolean("thinking", Config.thinking);
        } else {
            req.thinking = resolveThinking(req.model);
        }
        req.search = Config.search;

        JSONArray converted = new JSONArray();
        String system = systemToText(body.opt("system"));
        if (!system.isEmpty()) {
            JSONObject sys = new JSONObject();
            try {
                sys.put("role", "system");
                sys.put("content", system);
            } catch (Throwable ignore) { }
            safePut(converted, sys);
        }

        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if ("user".equals(role)) {
                appendAnthropicUser(converted, m.opt("content"), req.imageUrls);
            } else if ("assistant".equals(role)) {
                appendAnthropicAssistant(converted, m.opt("content"));
            } else if ("system".equals(role) || "developer".equals(role)) {
                String text = systemToText(m.opt("content"));
                if (!text.isEmpty()) {
                    JSONObject sys = new JSONObject();
                    try {
                        sys.put("role", "system");
                        sys.put("content", text);
                    } catch (Throwable ignore) { }
                    safePut(converted, sys);
                }
            }
        }
        req.messages = converted;

        JSONArray tools = body.optJSONArray("tools");
        JSONArray normalizedTools = new JSONArray();
        if (tools != null) {
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.optJSONObject(i);
                if (tool == null) continue;
                String name = tool.optString("name", "").trim();
                if (name.isEmpty()) continue;
                JSONObject fn = new JSONObject();
                JSONObject wrapper = new JSONObject();
                try {
                    fn.put("name", name);
                    String desc = tool.optString("description", "");
                    if (!desc.isEmpty()) fn.put("description", desc);
                    JSONObject schema = tool.optJSONObject("input_schema");
                    if (schema == null) schema = tool.optJSONObject("parameters");
                    if (schema != null) fn.put("parameters", schema);
                    wrapper.put("type", "function");
                    wrapper.put("function", fn);
                } catch (Throwable ignore) { }
                safePut(normalizedTools, wrapper);
            }
        }
        req.tools = normalizedTools;

        if (req.imageUrls.size() > MAX_IMAGES) {
            throw new DeepSeekClient.ApiException(400,
                    "too many images (max " + MAX_IMAGES + ")", "too_many_images");
        }
        return req;
    }

    /** Resolve thinking from Config.thinkingMode (always / auto / never). */
    private static boolean resolveThinking(String model) {
        if ("never".equals(Config.thinkingMode)) return false;
        if ("auto".equals(Config.thinkingMode)) {
            String m = model == null ? "" : model.toLowerCase();
            return m.contains("reason") || m.contains("r1") || m.contains("think");
        }
        return Config.thinking;
    }

    private static String systemToText(Object system) {
        if (system == null) return "";
        if (system instanceof String) return ((String) system).trim();
        if (system instanceof JSONArray) {
            JSONArray arr = (JSONArray) system;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof String) {
                    sb.append((String) item).append('\n');
                } else if (item instanceof JSONObject) {
                    JSONObject block = (JSONObject) item;
                    if ("text".equals(block.optString("type", ""))) {
                        sb.append(block.optString("text", "")).append('\n');
                    }
                }
            }
            return sb.toString().trim();
        }
        return "";
    }

    private static void appendAnthropicUser(JSONArray out, Object content, List<String> imageUrls) {
        if (content instanceof String) {
            JSONObject m = new JSONObject();
            try {
                m.put("role", "user");
                m.put("content", content);
            } catch (Throwable ignore) { }
            safePut(out, m);
            return;
        }
        if (!(content instanceof JSONArray)) return;
        JSONArray blocks = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type", "");
            if ("text".equals(type)) {
                String t = block.optString("text", "");
                if (!t.isEmpty()) text.append(t).append('\n');
            } else if ("image".equals(type)) {
                JSONObject source = block.optJSONObject("source");
                if (source != null) {
                    if ("base64".equals(source.optString("type", ""))) {
                        String media = source.optString("media_type", "image/png");
                        String data = source.optString("data", "");
                        if (!data.isEmpty()) imageUrls.add("data:" + media + ";base64," + data);
                    } else if ("url".equals(source.optString("type", ""))) {
                        String url = source.optString("url", "");
                        if (!url.isEmpty()) imageUrls.add(url);
                    }
                }
            } else if ("tool_result".equals(type)) {
                JSONObject tool = new JSONObject();
                try {
                    tool.put("role", "tool");
                    tool.put("tool_call_id", block.optString("tool_use_id", ""));
                    tool.put("content", toolResultText(block.opt("content")));
                } catch (Throwable ignore) { }
                safePut(out, tool);
            }
        }
        if (text.length() > 0) {
            JSONObject m = new JSONObject();
            try {
                m.put("role", "user");
                m.put("content", text.toString().trim());
            } catch (Throwable ignore) { }
            safePut(out, m);
        }
    }

    private static void appendAnthropicAssistant(JSONArray out, Object content) {
        if (content instanceof String) {
            JSONObject m = new JSONObject();
            try {
                m.put("role", "assistant");
                m.put("content", content);
            } catch (Throwable ignore) { }
            safePut(out, m);
            return;
        }
        if (!(content instanceof JSONArray)) return;
        JSONArray blocks = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        JSONArray calls = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type", "");
            if ("text".equals(type)) {
                String t = block.optString("text", "");
                if (!t.isEmpty()) text.append(t);
            } else if ("tool_use".equals(type)) {
                String name = block.optString("name", "").trim();
                if (name.isEmpty()) continue;
                JSONObject fn = new JSONObject();
                JSONObject call = new JSONObject();
                try {
                    fn.put("name", name);
                    JSONObject input = block.optJSONObject("input");
                    fn.put("arguments", input == null ? "{}" : input.toString());
                    call.put("type", "function");
                    call.put("id", block.optString("id", ""));
                    call.put("function", fn);
                } catch (Throwable ignore) { }
                safePut(calls, call);
            }
        }
        JSONObject m = new JSONObject();
        try {
            m.put("role", "assistant");
            m.put("content", text.toString());
            if (calls.length() > 0) m.put("tool_calls", calls);
        } catch (Throwable ignore) { }
        safePut(out, m);
    }

    private static String toolResultText(Object content) {
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof String) sb.append((String) item).append('\n');
                else if (item instanceof JSONObject) {
                    JSONObject o = (JSONObject) item;
                    if ("text".equals(o.optString("type", ""))) {
                        sb.append(o.optString("text", "")).append('\n');
                    }
                }
            }
            return sb.toString().trim();
        }
        if (content != null) return content.toString();
        return "";
    }

    /* ------------------------------------------------------------------ */
    /* Execution                                                           */
    /* ------------------------------------------------------------------ */

    public static Result run(Request req, DeepSeekClient client, StreamSink sink)
            throws DeepSeekClient.ApiException {
        Result result = new Result();
        long start = System.currentTimeMillis();

        Prompt.Built built = Prompt.build(req.messages, req.tools);
        String prompt = built.prompt;
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new DeepSeekClient.ApiException(400, "empty prompt", "invalid_messages");
        }
        Overflow.Plan overflow = null;
        // Before pushing history into an attachment, try the compact tool index:
        // declarations are the largest fixed cost, and once they move into an
        // attachment the model can no longer see them at all (it then invents
        // call syntax instead of calling a real tool).
        if (prompt.length() > Config.maxPromptChars
                && req.tools != null && req.tools.length() > 0) {
            Prompt.Built compactBuilt = Prompt.build(req.messages, req.tools, true);
            if (compactBuilt.prompt.length() < prompt.length()) {
                Logs.i(Logs.CHAT, TAG, "prompt over limit, retry compact tool decls chars="
                        + compactBuilt.prompt.length() + " was=" + prompt.length());
                prompt = compactBuilt.prompt;
            }
        }
        if (prompt.length() > Config.maxPromptChars) {
            if (!Config.overflowToAttachment) {
                throw new DeepSeekClient.ApiException(413,
                        "prompt too long: " + prompt.length() + " chars", "prompt_too_long");
            }
            overflow = Overflow.plan(prompt, Config.maxPromptChars, Config.maxAttachmentChars);
            prompt = overflow.prompt;
        }
        result.prompt = prompt;
        Logs.i(Logs.CHAT, TAG, "prompt built chars=" + prompt.length()
                + " messages=" + (req.messages == null ? 0 : req.messages.length())
                + " tools=" + (req.tools == null ? 0 : req.tools.length())
                + " overflow=" + (overflow != null));

        List<String> fileIds = new ArrayList<>();
        for (String url : req.imageUrls) {
            try {
                byte[] data = loadImage(url);
                if (data == null || data.length == 0) continue;
                if (data.length > MAX_IMAGE_BYTES) {
                    throw new DeepSeekClient.ApiException(400, "image too large", "image_too_large");
                }
                String mime = mimeOf(url);
                String id = client.uploadFile(data, mime,
                        "image-" + System.currentTimeMillis() + ".png");
                fileIds.add(id);
            } catch (DeepSeekClient.ApiException e) {
                throw e;
            } catch (Throwable t) {
                Logs.w(Logs.CHAT, TAG, "image upload failed (ignored): " + t);
            }
        }

        if (overflow != null && overflow.attachment != null && !overflow.attachment.isEmpty()) {
            String id = client.uploadFile(overflow.attachment.getBytes(StandardCharsets.UTF_8),
                    "text/plain", overflow.name);
            fileIds.add(id);
        }

        // Bare-JSON tool calls are only recognised when the name matches a
        // declared tool, so both the parsers and the stream filters need the
        // declaration list. Ordinary JSON in an answer (a config sample, an API
        // example) must never be deleted as if it were a call.
        final java.util.Set<String> toolNames = ToolKit.collectToolNameSet(req.tools);
        final ToolKit.StreamFilter contentFilter =
                sink == null ? null : new ToolKit.StreamFilter(toolNames);
        // Separate filter instance: the reasoning stream must not share the
        // dropping state of the content stream.
        final ToolKit.StreamFilter reasoningFilter =
                sink == null ? null : new ToolKit.StreamFilter(toolNames);
        DeepSeekClient.Sink inner = new DeepSeekClient.Sink() {
            @Override
            public void onReasoning(String delta) {
                if (sink == null || reasoningFilter == null) return;
                String safe = reasoningFilter.push(delta);
                if (!safe.isEmpty()) sink.onReasoning(safe);
            }

            @Override
            public void onContent(String delta) {
                if (sink == null || contentFilter == null) return;
                String safe = contentFilter.push(delta);
                if (!safe.isEmpty()) sink.onContent(safe);
            }
        };

        DeepSeekClient.Reply reply = client.completion(
                prompt, req.thinking, req.search, fileIds, inner);

        if (contentFilter != null) {
            String rest = contentFilter.flush();
            if (!rest.isEmpty()) sink.onContent(rest);
        }
        if (reasoningFilter != null) {
            String rest = reasoningFilter.flush();
            if (!rest.isEmpty()) sink.onReasoning(rest);
        }

        result.reasoning = reply.reasoning == null ? "" : reply.reasoning;
        result.upstreamUsage = reply.usage;

        // Tool calls may live in the content stream, in the thinking block, or
        // split across both; search them independently so nothing is missed.
        List<ToolKit.Call> calls = ToolKit.parseCalls(reply.content, req.tools);
        String callSource = calls.isEmpty() ? "none" : "content";
        if (calls.isEmpty() && !result.reasoning.isEmpty()) {
            calls = ToolKit.parseCalls(result.reasoning, req.tools);
            if (!calls.isEmpty()) callSource = "reasoning";
        }
        List<ToolKit.Call> coerced = new ArrayList<>();
        for (ToolKit.Call call : calls) {
            ToolKit.Call fixed = new ToolKit.Call();
            fixed.name = call.name;
            fixed.arguments = ToolKit.coerceArguments(call.name, call.arguments, req.tools);
            fixed.raw = call.raw;
            fixed.index = call.index;
            coerced.add(fixed);
        }
        result.toolCalls = coerced;
        String rawContent = reply.content == null ? "" : reply.content;
        String rawThinking = result.reasoning == null ? "" : result.reasoning;
        String visible = ToolKit.stripMarkup(rawContent, toolNames);
        // Reasoning only loses protocol tokens, never whole lines: stripMarkup
        // also drops RESULT:/DO:/section-header lines, which are ordinary prose
        // inside a thinking block. That silently ate ~2k chars per turn
        // (log: completion reasoning_chars=7590 -> extract reasoning_chars=5620).
        String thinking = ToolKit.scrubProtocol(rawThinking, toolNames);
        if (thinking.length() > 0) {
            thinking = thinking.replaceAll("\\n{3,}", "\\n\\n").trim();
        }
        if (visible.trim().isEmpty() && thinking.isEmpty()) {
            // Nothing visible at all: keep the raw text so the client is not
            // handed an empty assistant message.
            visible = rawContent.trim();
        }
        result.content = visible;
        result.reasoning = thinking;
        result.fabricated = ToolKit.hasFabricatedProtocol(reply.content)
                || ToolKit.hasFabricatedProtocol(reply.reasoning);

        // Strip accounting: content dropping to zero used to be invisible, which
        // made "the answer vanished" impossible to diagnose from logs alone.
        int contentRemoved = rawContent.length() - visible.length();
        int thinkingRemoved = rawThinking.length() - thinking.length();
        if (contentRemoved > 0 || thinkingRemoved > 0) {
            Logs.d(Logs.CHAT, TAG, "strip content=" + rawContent.length() + "->" + visible.length()
                    + " reasoning=" + rawThinking.length() + "->" + thinking.length()
                    + " removed=" + (contentRemoved + thinkingRemoved)
                    + " tool_source=" + callSource);
        }

        StringBuilder names = new StringBuilder();
        int downgraded = 0;
        for (int i = 0; i < result.toolCalls.size(); i++) {
            if (i > 0) names.append(',');
            ToolKit.Call c = result.toolCalls.get(i);
            names.append(c.name).append('(').append(c.arguments.length()).append('B');
            if (!c.format.isEmpty()) {
                names.append(',').append(c.format);
                if (!"dsml".equals(c.format)) downgraded++;
            }
            names.append(')');
        }
        result.elapsedMs = System.currentTimeMillis() - start;
        // One closing line per request: what came back, where it came from, and
        // how the time was spent. "extract" + "chat done" used to say the same
        // thing twice and neither carried the stage breakdown.
        Logs.i(Logs.CHAT, TAG, "chat done content=" + result.content.length()
                + " reasoning=" + result.reasoning.length()
                + " tools=" + result.toolCalls.size()
                + (names.length() > 0 ? " [" + names + "]" : "")
                + " src=" + callSource
                + " fabricated=" + result.fabricated
                + " elapsed=" + result.elapsedMs + "ms"
                + " cold=" + reply.coldStartMs + " session=" + reply.sessionMs
                + " pow=" + reply.powMs);
        for (int i = 0; i < result.toolCalls.size(); i++) {
            ToolKit.Call c = result.toolCalls.get(i);
            Logs.d(Logs.CHAT, TAG, "tool args " + c.name + ": " + preview(c.arguments, 120));
        }
        // Two failure modes that used to be invisible: the model ignored the
        // documented call syntax, or it invented a tool result on its own.
        Stats.TOOL_CALLS.addAndGet(result.toolCalls.size());
        if (downgraded > 0) {
            Stats.TOOL_DOWNGRADED.addAndGet(downgraded);
            Logs.w(Logs.CHAT, TAG, "format downgrade: " + downgraded + "/" + result.toolCalls.size()
                    + " calls used non-DSML syntax (model ignored the documented protocol)");
        }
        if (result.fabricated) {
            Stats.FABRICATED.incrementAndGet();
            Logs.w(Logs.CHAT, TAG, "fabricated tool result detected; result text was stripped");
        }
        return result;
    }

    /** Single-line, length-capped preview for debug logs. */
    private static String preview(String s, int max) {
        if (s == null || s.isEmpty()) return "{}";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "\u2026";
    }

    private static byte[] loadImage(String url) throws Exception {
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma < 0) return null;
            String meta = url.substring(5, comma);
            String payload = url.substring(comma + 1);
            if (meta.contains(";base64")) {
                return android.util.Base64.decode(payload, android.util.Base64.DEFAULT);
            }
            return payload.getBytes(StandardCharsets.UTF_8);
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String mimeOf(String url) {
        if (url.startsWith("data:")) {
            int semi = url.indexOf(';');
            int comma = url.indexOf(',');
            if (semi > 5 && comma > semi) return url.substring(5, semi);
        }
        return "image/png";
    }

    private static void safePut(JSONArray arr, Object value) {
        try {
            arr.put(value);
        } catch (Throwable ignore) { }
    }

    /* ------------------------------------------------------------------ */
    /* Token estimate                                                      */
    /* ------------------------------------------------------------------ */

    /** Rough estimate: CJK chars = 1 token, others = 1/4 token. */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp > 0x2e80) cjk++;
            else other++;
        }
        return Math.max(1, (int) Math.ceil(cjk + other / 4.0));
    }
}
