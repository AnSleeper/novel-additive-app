package com.fleshout.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var ipc: Ipc
    private var jsInjected = false

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.ctx = applicationContext
        ipc = Ipc(this)

        webView = WebView(this)
        webView.setBackgroundColor(0xFF191919.toInt())

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        // 桌面式布局靠 meta viewport 自适应，禁用双击/捏合缩放以免误触打乱排版
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.javaScriptCanOpenWindowsAutomatically = true

        webView.addJavascriptInterface(ipc, "FleshOutNative")

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val host = request.url.host ?: return true
                if (host == "appassets.androidplatform.net") return false
                // 外部链接（如 GitHub 更新页）交给系统浏览器
                return try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, request.url).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(
                    "(function(){return JSON.stringify({w:window.innerWidth,h:window.innerHeight," +
                        "dpr:window.devicePixelRatio,s:window.visualViewport?window.visualViewport.scale:0});})()"
                ) { r -> android.util.Log.d("FleshOutWeb", "viewport $r") }

                if (BuildConfig.DEBUG) injectJs(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("FleshOutWeb", m.message() + " @" + m.lineNumber())
                return true
            }
        }

        setContentView(webView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        App.webView = webView
        webView.loadUrl("https://appassets.androidplatform.net/index.html")

        if (intent?.getStringExtra("selftest") != null) runSelfTest()
    }

    /** 调试用：从 intent 的 js / jsfile 参数注入脚本 */
    private fun injectJs(view: WebView) {
        if (jsInjected) return
        val code = intent?.getStringExtra("js") ?: intent?.getStringExtra("jsfile")?.let { p ->
            val f = File(p)
            if (f.exists()) f.readText(Charsets.UTF_8) else null
        } ?: return
        jsInjected = true
        view.postDelayed({
            view.evaluateJavascript(code) { r ->
                android.util.Log.d("FleshOutWeb", "inject result=$r")
            }
        }, 1200)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        jsInjected = false
        if (BuildConfig.DEBUG) injectJs(webView)
    }

    /** 调试用自检：直接走一遍「建项目 → 解析 → 列章节」，结果打到 logcat */
    private fun runSelfTest() {
        Thread {
            try {
                if (intent?.getStringExtra("selftest") == "sample") {
                    val f = File(FileStore.booksDir(this), "sample.txt")
                    if (!f.exists()) {
                        android.util.Log.e("FleshOutSelfTest", "sample.txt 不存在")
                        return@Thread
                    }
                    val t0 = System.currentTimeMillis()
                    val plain = ipc.exec(
                        "preview_txt_chapters",
                        JSONObject().put("filePath", f.absolutePath).put("maxChapters", JSONObject.NULL)
                    )
                    android.util.Log.i(
                        "FleshOutSelfTest",
                        "默认规则 ${System.currentTimeMillis() - t0}ms -> " + plain.toString().take(300)
                    )
                    val rules = JSONObject()
                        .put("mode", "custom")
                        .put("customPattern", "^\\s*\\d{3}\\s*[：:]")
                    val t1 = System.currentTimeMillis()
                    val custom = ipc.exec(
                        "preview_txt_chapters",
                        JSONObject().put("filePath", f.absolutePath).put("rules", rules).put("maxChapters", JSONObject.NULL)
                    )
                    val arr = custom.optJSONObject("data")?.optJSONArray("chapters")
                    android.util.Log.i(
                        "FleshOutSelfTest",
                        "自定义规则 ${System.currentTimeMillis() - t1}ms 章节数=" + (arr?.length() ?: -1) +
                            " -> " + custom.toString().take(300)
                    )

                    val t2 = System.currentTimeMillis()
                    val smart = ipc.exec(
                        "preview_txt_chapters",
                        JSONObject().put("filePath", f.absolutePath)
                            .put("rules", JSONObject().put("mode", "smart"))
                            .put("maxChapters", JSONObject.NULL)
                    )
                    val sarr = smart.optJSONObject("data")?.optJSONArray("chapters")
                    android.util.Log.i(
                        "FleshOutSelfTest",
                        "智能识别 ${System.currentTimeMillis() - t2}ms 章节数=" + (sarr?.length() ?: -1) +
                            " -> " + smart.toString().take(300)
                    )

                    val novel = File(FileStore.booksDir(this), "novel.txt")
                    if (novel.exists()) {
                        val t3 = System.currentTimeMillis()
                        val r3 = ipc.exec(
                            "preview_txt_chapters",
                            JSONObject().put("filePath", novel.absolutePath)
                                .put("rules", JSONObject().put("mode", "smart"))
                                .put("maxChapters", JSONObject.NULL)
                        )
                        android.util.Log.i(
                            "FleshOutSelfTest",
                            "短篇智能识别 ${System.currentTimeMillis() - t3}ms -> " + r3.toString().take(500)
                        )
                    }
                    android.util.Log.i("FleshOutSelfTest", "SAMPLE DONE")
                    return@Thread
                }

                val file = File(FileStore.booksDir(this), "selftest.txt")
                if (!file.exists()) {
                    file.writeText(
                        "第一章 开端\n" +
                            "夜色如墨，雨点砸在青石板上。张三站在巷口，手里攥着一封已经被雨水浸透的信。\n" +
                            "他想起李四白天说过的话，心里越发不安。\n\n" +
                            "第二章 相遇\n" +
                            "李四从转角处走出来，撑着一把黑伞，脸上看不出表情。\n" +
                            "两人对视了很久，谁都没有先开口。\n\n" +
                            "第三章 分歧\n" +
                            "争执从一件小事开始，最后变成了掀翻桌子的吵闹。\n" +
                            "张三摔门而出，留下李四一个人在空荡荡的屋子里。\n",
                        Charsets.UTF_8
                    )
                }
                val created = ipc.exec(
                    "create_manual_project",
                    JSONObject().put(
                        "request",
                        JSONObject().put("name", "自检项目").put("bookPath", file.absolutePath).put("modelId", "")
                    )
                )
                android.util.Log.i("FleshOutSelfTest", "create=" + created)
                val pid = created.optJSONObject("data")?.optString("projectId").orEmpty()
                if (pid.isNotEmpty()) {
                    android.util.Log.i("FleshOutSelfTest", "step: list_manual_chapters begin")
                    val chapters = ipc.exec("list_manual_chapters", JSONObject().put("projectId", pid))
                    android.util.Log.i("FleshOutSelfTest", "chapters=" + chapters.toString().take(1200))
                    android.util.Log.i("FleshOutSelfTest", "step: get_project begin")
                    val detail = ipc.exec("get_project", JSONObject().put("id", pid))
                    android.util.Log.i("FleshOutSelfTest", "project=" + detail.toString().take(800))
                    android.util.Log.i("FleshOutSelfTest", "step: create_conversation begin")
                    val conv = ipc.exec("create_conversation", JSONObject().put("projectId", pid).put("name", "自检会话"))
                    android.util.Log.i("FleshOutSelfTest", "conversation=" + conv)
                    val info = ipc.exec(
                        "get_manual_project_info",
                        JSONObject().put("projectId", pid).put("currentChapterIndex", 1)
                    )
                    android.util.Log.i("FleshOutSelfTest", "info=" + info)
                    val cfg = ipc.exec("get_category_config", JSONObject().put("projectId", pid))
                    android.util.Log.i("FleshOutSelfTest", "category=" + cfg)
                    val exp = ipc.exec("export_manual_project", JSONObject().put("projectId", pid))
                    android.util.Log.i("FleshOutSelfTest", "export=" + exp)
                    android.util.Log.i("FleshOutSelfTest", "ALL DONE")
                }
            } catch (e: Exception) {
                android.util.Log.e("FleshOutSelfTest", "self test failed", e)
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ipc.onActivityResult(requestCode, resultCode, data)
    }

    private var lastBackAt = 0L

    override fun onBackPressed() {
        // 优先回应用内上一页；已在首页才走「再按一次退出」
        val view = webView
        if (view == null) {
            finish()
            return
        }
        view.evaluateJavascript(
            "(function(){return window.__foBack ? window.__foBack() : false;})()"
        ) { result ->
            App.main.post {
                if (result == "true") return@post
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) {
                    finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        App.webView = null
        super.onDestroy()
    }
}
