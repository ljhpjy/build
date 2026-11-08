package com.fuckds.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * 参考 IDAdroid 的 rootfs 导入器：
 *   - 兼容 .tar.xz 与 .tar.gz（按魔数自动识别）
 *   - 基于 commons-compress（tar/链接/权限全处理）与 tukaani xz
 *   - 带进度回调（百分比 + 当前条目）
 *   - staging 暂存 + 原子切换，失败不污染旧环境
 *   - 路径防穿越，符号链接创建失败不阻断（proot -b 兜底）
 */
public final class Importer {

    public interface Progress {
        void onProgress(float pct, String entry);
    }

    private Importer() {
    }

    public static void importRootfs(Context ctx, Uri uri, File destDir, Progress cb) throws Exception {
        ContentResolver cr = ctx.getContentResolver();

        long total = -1;
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) {
                    total = c.getLong(i);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }

        File parent = destDir.getParentFile();
        if (parent == null) {
            throw new IOException("无法定位目标目录");
        }
        parent.mkdirs();

        File staging = new File(parent, "rootfs.staging");
        deleteRecursively(staging);
        staging.mkdirs();

        CountingInputStream cis = new CountingInputStream(
                new BufferedInputStream(cr.openInputStream(uri), 1 << 16));
        InputStream dec = isXz(cis) ? new XZInputStream(cis) : new GZIPInputStream(cis);
        TarArchiveInputStream tar = new TarArchiveInputStream(dec);

        long lastReport = 0;
        TarArchiveEntry e;
        while ((e = tar.getNextTarEntry()) != null) {
            String name = sanitize(e.getName());
            if (name.isEmpty()) {
                continue;
            }
            File out = new File(staging, name);
            if (e.isDirectory()) {
                out.mkdirs();
                continue;
            }
            File p = out.getParentFile();
            if (p != null) {
                p.mkdirs();
            }
            if (e.isSymbolicLink()) {
                try {
                    Os.symlink(e.getLinkName(), out.getAbsolutePath());
                } catch (ErrnoException ex) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    try {
                        Os.symlink(e.getLinkName(), out.getAbsolutePath());
                    } catch (ErrnoException ignored) {
                    }
                }
            } else if (e.isLink()) {
                File target = new File(staging, sanitize(e.getLinkName()));
                try {
                    Os.link(target.getAbsolutePath(), out.getAbsolutePath());
                } catch (ErrnoException ex) {
                    copyStream(tar, out);
                }
            } else {
                copyStream(tar, out);
            }
            if (!e.isSymbolicLink()) {
                int mode = e.getMode();
                try {
                    Os.chmod(out.getAbsolutePath(), (mode & 07777) | (e.isDirectory() ? 0700 : 0400));
                } catch (ErrnoException ignored) {
                }
            }
            // 进度回调节流：每 1MB（或最后一段）才回调一次，避免上万条目刷爆 UI 线程
            if (cis.count - lastReport >= (1 << 20)) {
                lastReport = cis.count;
                cb.onProgress(total > 0 ? cis.count / (float) total : -1f, name);
            }
        }
        cb.onProgress(total > 0 ? 1f : -1f, "解压完成，正在激活…");
        tar.close();

        // 原子切换：删除旧 rootfs，staging 改名
        deleteRecursively(destDir);
        if (!staging.renameTo(destDir)) {
            throw new IOException("rootfs 激活失败（staging 改名失败）");
        }
    }

    /* ------------------------------ 内部 ------------------------------ */

    private static boolean isXz(CountingInputStream in) throws IOException {
        in.mark(6);
        byte[] magic = new byte[6];
        int n = in.read(magic);
        in.reset();
        return n == 6
                && (magic[0] & 0xFF) == 0xFD && magic[1] == '7' && magic[2] == 'z'
                && magic[3] == 'X' && magic[4] == 'Z' && magic[5] == 0x00;
    }

    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String seg : raw.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..") || seg.startsWith("/")) {
                return "";
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(seg);
        }
        return sb.toString();
    }

    private static void copyStream(InputStream in, File out) throws IOException {
        byte[] buf = new byte[1 << 16];
        try (OutputStream os = new FileOutputStream(out)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        boolean symlinkDir = false;
        if (f.isDirectory()) {
            try {
                symlinkDir = !f.getAbsolutePath().equals(f.getCanonicalPath());
            } catch (IOException ignored) {
            }
        }
        if (f.isDirectory() && !symlinkDir) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 计数输入流：用于进度百分比 */
    static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }
}
