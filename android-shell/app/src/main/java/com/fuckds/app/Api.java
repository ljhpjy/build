package com.fuckds.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 HTTP/JSON 客户端：调用本机 fuckds 服务的 /admin/api/* 管理接口。
 */
public final class Api {

    private Api() {
    }

    public static String http(String method, String url, String jsonBody) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (jsonBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = jsonBody.getBytes(StandardCharsets.UTF_8);
                c.getOutputStream().write(data);
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = in == null ? "" : readAll(in);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " " + body);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    public static String get(String url) throws IOException {
        return http("GET", url, null);
    }

    public static String post(String url, String jsonBody) throws IOException {
        return http("POST", url, jsonBody);
    }

    public static String put(String url, String jsonBody) throws IOException {
        return http("PUT", url, jsonBody);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 14];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    /** JSON 字符串转义（不引入第三方 JSON 库） */
    public static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    public static String tokensJson(String[] tokens) {
        StringBuilder sb = new StringBuilder("{\"tokens\":[");
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(tokens[i])).append('"');
        }
        return sb.append("]}").toString();
    }
}
