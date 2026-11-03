package com.fuckds.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fuckds.android.databinding.ActivityConsoleBinding

/** 用 WebView 装现有的 /admin 控制台：账号、设置、测试、日志、重启都在里面。 */
class ConsoleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConsoleBinding
    private var port = 8787

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        port = RuntimeInstaller.currentPort(this)
        configureWebView()

        binding.swipeRefresh.setOnRefreshListener { loadConsole() }
        binding.btnRetry.setOnClickListener { loadConsole() }
        binding.btnStart.setOnClickListener {
            try {
                ServerService.start(this)
            } catch (t: Throwable) {
                toast(t.message ?: "启动服务失败")
            }
            binding.errorBox.visibility = View.GONE
            binding.progress.visibility = View.VISIBLE
            binding.webView.postDelayed({ loadConsole() }, 1500)
        }

        loadConsole()
    }

    override fun onResume() {
        super.onResume()
        val latest = RuntimeInstaller.currentPort(this)
        if (latest != port) {
            port = latest
            loadConsole()
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_console, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> {
                loadConsole()
                true
            }
            R.id.action_browser -> {
                openInBrowser()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /* ------------------------------------------------------------------ */

    private fun configureWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.loadsImagesAutomatically = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.textZoom = 100

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val host = url.host ?: return false
                if (host == "127.0.0.1" || host == "localhost") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: Throwable) {
                    toast("无法打开外部链接")
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) showError()
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
    }

    private fun loadConsole() {
        port = RuntimeInstaller.currentPort(this)
        binding.errorBox.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.swipeRefresh.isRefreshing = false
        binding.webView.loadUrl("http://127.0.0.1:$port/admin/")
    }

    private fun showError() {
        binding.progress.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.errorBox.visibility = View.VISIBLE
        binding.errorText.text = getString(R.string.console_error, port)
    }

    private fun openInBrowser() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port/admin/")))
        } catch (_: Throwable) {
            toast("没有可用的浏览器")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
