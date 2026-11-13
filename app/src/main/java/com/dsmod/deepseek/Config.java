package com.dsmod.deepseek;

/**
 * 全局配置。
 *
 * 运行期改配置走 /data/data/com.deepseek.chat/files/dsmod_config.json，
 * 或通过设置页保存；这里只是默认值。
 */
public final class Config {
    private Config() {}

    /** 监听地址：0.0.0.0 允许局域网访问，127.0.0.1 仅本机。 */
    public static String host = "0.0.0.0";
    /** 本地 API 端口，与 fuckds 默认一致。 */
    public static int port = 8765;
    /** 上游站点。 */
    public static String baseUrl = "https://chat.deepseek.com";

    /** 可选：设置后要求 Authorization: Bearer <key> 或 x-api-key: <key>。 */
    public static String apiKey = "";

    /** 默认模型名（只是对外标识，网页版实际模型固定）。 */
    public static String defaultModel = "deepseek-v4.1-flash-1017";

    /** 是否默认开启深度思考。 */
    public static boolean thinking = true;
    /** thinking 策略：always / auto（按模型名含 reason/r1/think）/ never。 */
    public static String thinkingMode = "always";
    /** 是否默认开启智能搜索。 */
    public static boolean search = false;

    /** 一次请求里最多自动续跑的工具轮次（收到 tool_calls 后再发一轮）。 */
    public static int maxRounds = 5;
    /** 上游并发上限（Semaphore）；fuckds 默认并发 1，更保守、更少风控。 */
    public static int concurrency = 1;

    /** 是否尝试宿主原生 PoW（部分版本会在 JNI 内卡死，默认关闭，纯 Java 求解）。 */
    public static boolean powNative = true;

    /** 单次上游请求总超时。 */
    public static long requestTimeoutMs = 240_000L;
    /** 单账号限流冷却。 */
    public static long rateLimitCooldownMs = 60_000L;
    /** 号池全冷却时的最大等待，超时返回 429。 */
    public static long maxCooldownWaitMs = 5_000L;

    /** 单条 prompt 上限；超过即转附件（官方网页 10 万字，但超长单次耗时可达 30s+）。 */
    public static int maxPromptChars = 32_000;
    /** 拆到附件的上下文上限（字符）。 */
    public static int maxAttachmentChars = 2_000_000;
    /** 超长时是否转附件；false 则直接 413。 */
    public static boolean overflowToAttachment = true;

    /** 启动时预解 PoW / 预建会话。 */
    public static boolean warmup = true;
    /** 进程内每个账号跑一次网页冷启动序列（settings / check_device）。 */
    public static boolean coldStart = true;
    /** 发送前加入人类输入停顿（120–480ms）。 */
    public static boolean humanTiming = true;
    /** PoW 预解缓存有效期（秒），取得时剩余不足 5s 视为过期。 */
    public static long powExpirySafetyMs = 5_000L;

    /** 设备 ID 持久化文件。 */
    public static String deviceFile = "dsmod_device_id.txt";
    /** 号池文件。 */
    public static String accountsFile = "dsmod_accounts.json";
    /** 配置覆盖文件。 */
    public static String configFile = "dsmod_config.json";

    // ---- 上游请求头固定值（与 fuckds fingerprint.mjs 对齐） ----
    public static final String UA_CHROME_WIN =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    public static final String SEC_CH_UA =
            "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"";
    public static final String CLIENT_VERSION = "2.7.0";
    public static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7";
    public static final String ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8";
    public static final String ACCEPT_ENCODING = "gzip, deflate";
}
