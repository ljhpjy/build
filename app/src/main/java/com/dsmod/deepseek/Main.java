package com.dsmod.deepseek;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry: runs inside the DeepSeek host process.
 *
 * Hook strategy (most reliable first):
 *   1. Application.attach(Context)  - earliest context
 *   2. Application.onCreate()       - fallback
 *   3. ActivityThread.handleBindApplication - last resort (classic modules)
 *
 * Every step writes to BOTH logcat and the LSPosed module log, so a silent
 * failure in the field can actually be diagnosed from LSPosed -> Logs.
 * Only the main process is bootstrapped; sub-processes would bind the same
 * port and fail.
 */
public class Main implements IXposedHookLoadPackage {
    private static final String TAG = "DSMOD";
    private static final String HOST_PACKAGE = "com.deepseek.chat";

    private static volatile boolean bootstrapped = false;

    private static void xlog(String message) {
        Log.i(TAG, message);
        try {
            XposedBridge.log("[DSMOD] " + message);
        } catch (Throwable ignore) { }
    }

    private static void xerr(String message, Throwable t) {
        Log.e(TAG, message, t);
        try {
            XposedBridge.log("[DSMOD] " + message + ": " + t);
        } catch (Throwable ignore) { }
    }

    static {
        // Earliest proof that LSPosed actually loaded this module class into
        // the scoped process; appears even if every hook below fails.
        xlog("Main class loaded by " + Main.class.getClassLoader());
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !HOST_PACKAGE.equals(lpparam.packageName)) return;
        String process = lpparam.processName == null ? HOST_PACKAGE : lpparam.processName;
        xlog("module loaded: pkg=" + lpparam.packageName + " process=" + process
                + " version=" + Main.class.getName());
        if (!process.equals(HOST_PACKAGE)) {
            xlog("skip sub-process: " + process);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            if (context != null) bootstrap("Application.attach", context);
                        }
                    });
            xlog("hooked Application.attach");
        } catch (Throwable t) {
            xerr("hook Application.attach failed", t);
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object app = param.thisObject;
                            if (app instanceof Context) bootstrap("Application.onCreate", (Context) app);
                        }
                    });
            xlog("hooked Application.onCreate");
        } catch (Throwable t) {
            xerr("hook Application.onCreate failed", t);
        }

        try {
            Class<?> activityThread = XposedHelpers.findClass(
                    "android.app.ActivityThread", lpparam.classLoader);
            Class<?> appBindData = XposedHelpers.findClass(
                    "android.app.ActivityThread$AppBindData", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(activityThread, "handleBindApplication", appBindData,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object app = XposedHelpers.callStaticMethod(activityThread,
                                        "currentApplication");
                                if (app instanceof Context) {
                                    bootstrap("ActivityThread.handleBindApplication", (Context) app);
                                }
                            } catch (Throwable ignore) { }
                        }
                    });
            xlog("hooked ActivityThread.handleBindApplication");
        } catch (Throwable t) {
            xerr("hook ActivityThread.handleBindApplication failed (ignored)", t);
        }
    }

    /** In-process TCP self-check: proves the socket really listens on 127.0.0.1. */
    private static boolean probePort(int port) {
        java.net.Socket socket = null;
        try {
            socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1200);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Throwable ignore) { }
            }
        }
    }

    private static synchronized void bootstrap(String source, Context context) {
        if (bootstrapped) return;
        bootstrapped = true;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        final Context host = app;
        xlog("bootstrap via " + source + ": starting local api");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Accounts.init(host);
                    LocalApiServer.ensureStarted(host);
                    Notifier.show(host);
                    boolean portOpen = probePort(Config.port);
                    xlog("bootstrap done: running=" + LocalApiServer.isRunning()
                            + " portOpen=" + portOpen + " port=" + Config.port);
                } catch (Throwable t) {
                    xerr("bootstrap failed", t);
                }
            }
        }, "dsmod-bootstrap");
        thread.setDaemon(true);
        thread.start();
    }
}
