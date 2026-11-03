package com.fuckds.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        if (!Prefs.autoStart(context)) return
        try {
            ServerService.start(context)
            Log.i("fuckds", "开机自启：已请求启动服务")
        } catch (t: Throwable) {
            Log.w("fuckds", "开机自启失败", t)
        }
    }
}
