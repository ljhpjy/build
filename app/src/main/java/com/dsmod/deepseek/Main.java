package com.dsmod.deepseek;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry: runs inside the DeepSeek host process.
 *
 * We hook Application.attach(Context): the host context is ready there,
 * the MMKV store (login state) can be opened reflectively and the local
 * HTTP server can start. The foreground service is requested as a
 * best-effort keepalive; failure never blocks the server.
 */
public class Main implements IXposedHookLoadPackage {
    private static final String TAG = "DSMOD";
    private static final String HOST_PACKAGE = "com.deepseek.chat";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !HOST_PACKAGE.equals(lpparam.packageName)) return;
        Log.i(TAG, "module loaded into " + lpparam.packageName);
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            if (context != null) onHostReady(context.getApplicationContext());
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hook Application.attach failed", t);
        }
    }

    private static void onHostReady(final Context context) {
        if (context == null) return;
        // Never do reflective MMKV / socket IO on the host main thread.
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Accounts.init(context);
                    LocalApiServer.ensureStarted(context);
                    Notifier.show(context);
                } catch (Throwable t) {
                    Log.e(TAG, "host ready init failed", t);
                }
            }
        }, "dsmod-bootstrap");
        thread.setDaemon(true);
        thread.start();
    }
}
