package com.fuckds.app;

import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * 极简 tar.gz 解包器（流式，不落中间文件）：
 * - ustar 格式（含 prefix 长前缀）
 * - GNU 'L'/'K' 长文件名 / 长链接名
 * - PAX 'x' 扩展头（path / linkpath / size）
 * - 符号链接 / 硬链接（rootfs 里 /bin -> usr/bin 等必需）
 * - 设备节点 / fifo 直接跳过；路径中拒绝 ".." 防目录穿越
 * 不校验 checksum（数据完整性由外层 gzip + APK 安装校验保证）。
 */
public final class TarGz {

    private TarGz() {
    }

    public static void extract(InputStream raw, File destDir) throws IOException {
        destDir.mkdirs();
        byte[] buf = new byte[1 << 16];

        try (InputStream gz = new GZIPInputStream(raw, 1 << 16)) {
            String pendingName = null;
            String pendingLink = null;
            long pendingSize = -1L;

            while (true) {
                byte[] header = readFully(gz, 512);
                if (header == null || isAllZero(header)) {
                    break;
                }

                String name = readString(header, 0, 100);
                String prefix = readString(header, 345, 155);
                long size = parseOctal(header, 124, 12);
                int type = header[156] & 0xFF;
                String linkName = readString(header, 157, 100);
                long dataLen = ((size + 511L) / 512L) * 512L;

                if (type == 'L' || type == 'K') {
                    // GNU long name / long link：正文就是名字，跳过，等下一个真实条目
                    byte[] data = readUpTo(gz, size, dataLen, 1 << 20);
                    if (data == null) {
                        break;
                    }
                    String value = new String(data, 0, (int) Math.min(size, data.length),
                            StandardCharsets.UTF_8).replace("\u0000", "");
                    if (type == 'L') {
                        pendingName = value;
                    } else {
                        pendingLink = value;
                    }
                    continue;
                }

                if (type == 'x') {
                    // PAX 扩展头：解析 path / linkpath / size 供下一个条目使用
                    byte[] data = readUpTo(gz, size, dataLen, 16 << 20);
                    if (data == null) {
                        break;
                    }
                    String text = new String(data, 0, (int) Math.min(size, data.length),
                            StandardCharsets.UTF_8);
                    for (String record : text.split("\n")) {
                        if (record.isEmpty()) {
                            continue;
                        }
                        int sp = record.indexOf(' ');
                        if (sp < 0) {
                            continue;
                        }
                        String kv = record.substring(sp + 1);
                        if (kv.startsWith("path=")) {
                            pendingName = kv.substring(5);
                        } else if (kv.startsWith("linkpath=")) {
                            pendingLink = kv.substring(9);
                        } else if (kv.startsWith("size=")) {
                            try {
                                pendingSize = Long.parseLong(kv.substring(5));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    continue;
                }

                if (pendingName != null) {
                    name = pendingName;
                    pendingName = null;
                }
                if (pendingLink != null) {
                    linkName = pendingLink;
                    pendingLink = null;
                }
                if (pendingSize >= 0) {
                    size = pendingSize;
                    pendingSize = -1L;
                    dataLen = ((size + 511L) / 512L) * 512L;
                }

                String full = sanitize((prefix != null && !prefix.isEmpty())
                        ? prefix + "/" + name : name);
                if (full.isEmpty()) {
                    skipFully(gz, dataLen);
                    continue;
                }
                File out = new File(destDir, full);

                switch (type) {
                    case '5': // 目录
                        out.mkdirs();
                        skipFully(gz, dataLen);
                        applyMode(header, out, true);
                        break;

                    case '2': { // 符号链接：容错处理（重复 tar 条目 / ROM 限制 symlink 均不阻断解压）
                        out.getParentFile().mkdirs();
                        boolean isLink = false;
                        if (out.exists()) {
                            try {
                                isLink = !out.getAbsolutePath().equals(out.getCanonicalPath());
                            } catch (IOException ignored) {
                            }
                        }
                        if (!isLink) {
                            try {
                                Os.symlink(linkName, out.getAbsolutePath());
                            } catch (ErrnoException e) {
                                // 常见于重复 tar 条目（EEXIST）：删占位重试一次；
                                // 再失败则忽略——关键目录（bin/sbin/lib/lib64）由
                                // BootService 的 proot -b 绑定兜底。
                                //noinspection ResultOfMethodCallIgnored
                                out.delete();
                                try {
                                    Os.symlink(linkName, out.getAbsolutePath());
                                } catch (ErrnoException ignored) {
                                }
                            }
                        }
                        skipFully(gz, dataLen);
                        break;
                    }

                    case '1': { // 硬链接
                        out.getParentFile().mkdirs();
                        File target = new File(destDir, sanitize(linkName));
                        if (!out.exists() && target.isFile()) {
                            try {
                                Os.link(target.getAbsolutePath(), out.getAbsolutePath());
                            } catch (ErrnoException ignored) {
                            }
                        }
                        if (out.exists()) {
                            skipFully(gz, dataLen);
                        } else {
                            copyExactly(gz, size, out, buf);
                        }
                        applyMode(header, out, false);
                        break;
                    }

                    case '0':
                    case '\0':
                    case '7': { // 普通文件
                        out.getParentFile().mkdirs();
                        copyExactly(gz, size, out, buf);
                        applyMode(header, out, false);
                        break;
                    }

                    default: // 设备节点 / fifo 等，跳过
                        skipFully(gz, dataLen);
                        break;
                }
            }
        }
    }

    /* ------------------------------ 内部 ------------------------------ */

    private static String sanitize(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                return "";
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(seg);
        }
        return sb.toString();
    }

    private static void copyExactly(InputStream in, long size, File out, byte[] buf) throws IOException {
        long remaining = size;
        try (OutputStream os = new FileOutputStream(out)) {
            while (remaining > 0) {
                int want = (int) Math.min(remaining, buf.length);
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    throw new IOException("tar 数据意外结束: " + out.getPath());
                }
                os.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    private static void applyMode(byte[] header, File f, boolean dir) {
        int mode = (int) parseOctal(header, 100, 8);
        if (mode == 0) {
            mode = dir ? 0755 : 0644;
        }
        int finalMode = (mode & 07777) | (dir ? 0700 : 0400);
        try {
            Os.chmod(f.getAbsolutePath(), finalMode);
        } catch (ErrnoException ignored) {
        }
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) {
                return off == 0 ? null : b;
            }
            off += n;
        }
        return b;
    }

    /** 读正文（最多 cap 字节），剩余部分丢弃；保证 dataLen 全部消费 */
    private static byte[] readUpTo(InputStream in, long size, long dataLen, int cap) throws IOException {
        byte[] data = new byte[(int) Math.min(size, cap)];
        long done = 0;
        while (done < data.length) {
            int want = (int) Math.min(data.length - done, 1 << 16);
            int n = in.read(data, (int) done, want);
            if (n < 0) {
                return null;
            }
            done += n;
        }
        long consumed = Math.max(dataLen - done, 0);
        skipFully(in, consumed);
        return data;
    }

    private static void skipFully(InputStream in, long len) throws IOException {
        long remaining = len;
        byte[] skip = new byte[1 << 16];
        while (remaining > 0) {
            int n = in.read(skip, 0, (int) Math.min(remaining, skip.length));
            if (n < 0) {
                return;
            }
            remaining -= n;
        }
    }

    private static String readString(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) {
            end++;
        }
        return new String(buf, off, end - off, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        long v = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            byte b = buf[i];
            if (b == 0) {
                break;
            }
            if (b == ' ') {
                continue;
            }
            if (b < '0' || b > '7') {
                return v;
            }
            v = v * 8 + (b - '0');
            any = true;
        }
        return any ? v : 0;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }
}
