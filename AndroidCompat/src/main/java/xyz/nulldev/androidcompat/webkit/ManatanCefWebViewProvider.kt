package xyz.nulldev.androidcompat.webkit

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.print.PrintDocumentAdapter
import android.util.Log
import android.util.SparseArray
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.autofill.AutofillValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.textclassifier.TextClassifier
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebView.PictureListener
import android.webkit.WebView.VisualStateCallback
import android.webkit.WebViewClient
import android.webkit.WebViewProvider
import android.webkit.WebViewProvider.ScrollDelegate
import android.webkit.WebViewProvider.ViewDelegate
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

class ManatanCefWebViewProvider(
    private val view: WebView,
) : WebViewProvider {
    private val settings = CompatWebSettings()
    private var viewClient = WebViewClient()
    private var chromeClient = WebChromeClient()
    private val handler = Handler(view.webViewLooper)
    private val json = Json { ignoreUnknownKeys = true }
    private val bridgeUrl =
        System.getProperty("manatan.nativeWebViewBridgeUrl")
            ?: throw IllegalStateException("manatan.nativeWebViewBridgeUrl is required for manatan-cef WebView")
    private val listener = BridgeListener()
    private val webSocket: WebSocket by lazy { connect() }
    private val pendingSend = AtomicReference<CompletableFuture<*>>(CompletableFuture.completedFuture(null))
    private var currentUrl: String = "about:blank"
    private var progress: Int = 100

    private fun connect(): WebSocket =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create(bridgeUrl), listener)
            .whenComplete { _, error ->
                if (error != null) {
                    Log.w(TAG, "Runtime webview bridge connect failed url=$bridgeUrl", error)
                } else {
                    Log.w(TAG, "Runtime webview bridge connecting url=$bridgeUrl")
                }
            }.join()

    private fun send(
        message: OutgoingMessage,
        socketOverride: WebSocket? = null,
    ) {
        val payload = json.encodeToString(message)
        val socket = socketOverride ?: webSocket
        Log.w(TAG, "Sending runtime webview message payload=$payload")
        pendingSend.updateAndGet { previous ->
            previous
                .exceptionally { error ->
                    Log.w(TAG, "Previous runtime webview send failed", error)
                    null
                }.thenCompose {
                    socket.sendText(payload, true)
                }.whenComplete { _, error ->
                    if (error != null) {
                        Log.w(TAG, "Failed to send runtime webview message payload=$payload", error)
                    }
                }
        }
    }

    private fun pushSettings(socketOverride: WebSocket? = null) {
        send(
            OutgoingMessage.SetSettings(
                userAgent = settings.userAgentString,
                blockNetworkLoads = settings.blockNetworkLoads,
            ),
            socketOverride = socketOverride,
        )
    }

    private fun normalizeResponse(response: WebResourceResponse): RuntimeResponsePayload {
        val statusCode = response.statusCode.takeIf { it in 100..299 || it in 400..599 } ?: 200
        val reasonPhrase = response.reasonPhrase?.takeIf { it.isNotBlank() } ?: "OK"
        val mimeType = response.mimeType ?: "application/octet-stream"
        val headers =
            response.responseHeaders
                ?.entries
                ?.map { RuntimeHeader(it.key, it.value) }
                .orEmpty()
        val bodyBytes = response.data?.readAllBytes() ?: ByteArray(0)
        return RuntimeResponsePayload(
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            mimeType = mimeType,
            headers = headers,
            bodyBase64 = Base64.getEncoder().encodeToString(bodyBytes),
        )
    }

    private fun handleInterceptRequest(message: IncomingMessage.InterceptRequest) {
        val request =
            object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(message.url)

                override fun isForMainFrame(): Boolean = message.isMainFrame

                override fun isRedirect(): Boolean = message.isRedirect

                override fun hasGesture(): Boolean = false

                override fun getMethod(): String = message.method

                override fun getRequestHeaders(): Map<String, String> = message.headers.associate { it.name to it.value }
            }

        if (viewClient.shouldOverrideUrlLoading(view, request)) {
            Log.w(TAG, "Intercept override blocked url=${message.url} mainFrame=${message.isMainFrame}")
            send(OutgoingMessage.InterceptResponse(message.requestId, "block", null))
            return
        }

        viewClient.onLoadResource(view, message.url)
        val response = viewClient.shouldInterceptRequest(view, request)
        if (response == null) {
            Log.w(TAG, "Intercept allow url=${message.url} mainFrame=${message.isMainFrame}")
            send(OutgoingMessage.InterceptResponse(message.requestId, "allow", null))
            return
        }

        Log.w(TAG, "Intercept respond url=${message.url} mainFrame=${message.isMainFrame}")
        send(
            OutgoingMessage.InterceptResponse(
                requestId = message.requestId,
                action = "respond",
                response = normalizeResponse(response),
            ),
        )
    }

    private inner class BridgeListener : WebSocket.Listener {
        private val textBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
            Log.w(TAG, "Runtime webview bridge opened")
            pushSettings(webSocket)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*> {
            textBuffer.append(data)
            if (!last) {
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            val payload = textBuffer.toString()
            textBuffer.setLength(0)
            val message =
                try {
                    json.decodeFromString<IncomingMessage>(payload)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode runtime webview message", e)
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(null)
                }

            when (message) {
                is IncomingMessage.PageStarted -> {
                    Log.w(TAG, "Page started url=${message.url}")
                    currentUrl = message.url
                    progress = 0
                    handler.post {
                        chromeClient.onProgressChanged(view, 0)
                        viewClient.onPageStarted(view, message.url, null)
                    }
                }

                is IncomingMessage.PageFinished -> {
                    Log.w(TAG, "Page finished url=${message.url} status=${message.httpStatusCode}")
                    currentUrl = message.url
                    progress = 100
                    handler.post {
                        viewClient.onPageFinished(view, message.url)
                        chromeClient.onProgressChanged(view, 100)
                    }
                }

                is IncomingMessage.LoadError -> {
                    Log.w(TAG, "Page load error url=${message.url} error=${message.errorText}")
                    handler.post {
                        viewClient.onReceivedError(
                            view,
                            WebViewClient.ERROR_UNKNOWN,
                            message.errorText,
                            message.url,
                        )
                    }
                }

                is IncomingMessage.InterceptRequest -> {
                    handleInterceptRequest(message)
                }
            }

            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onBinary(
            webSocket: WebSocket,
            data: ByteBuffer,
            last: Boolean,
        ): CompletionStage<*> {
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(
            webSocket: WebSocket?,
            error: Throwable,
        ) {
            Log.w(TAG, "Runtime webview bridge error", error)
        }
    }

    override fun init(
        javaScriptInterfaces: Map<String, Any>?,
        privateBrowsing: Boolean,
    ) {
        Log.w(
            TAG,
            "init privateBrowsing=$privateBrowsing jsInterfaces=${javaScriptInterfaces?.keys.orEmpty().joinToString(",")}",
        )
        webSocket
    }

    override fun setHorizontalScrollbarOverlay(overlay: Boolean): Unit = throw RuntimeException("Stub!")

    override fun setVerticalScrollbarOverlay(overlay: Boolean): Unit = throw RuntimeException("Stub!")

    override fun overlayHorizontalScrollbar(): Boolean = throw RuntimeException("Stub!")

    override fun overlayVerticalScrollbar(): Boolean = throw RuntimeException("Stub!")

    override fun getVisibleTitleHeight(): Int = throw RuntimeException("Stub!")

    override fun getCertificate(): SslCertificate = throw RuntimeException("Stub!")

    override fun setCertificate(certificate: SslCertificate): Unit = throw RuntimeException("Stub!")

    override fun savePassword(
        host: String,
        username: String,
        password: String,
    ): Unit = throw RuntimeException("Stub!")

    override fun setHttpAuthUsernamePassword(
        host: String,
        realm: String,
        username: String,
        password: String,
    ): Unit = throw RuntimeException("Stub!")

    override fun getHttpAuthUsernamePassword(
        host: String,
        realm: String,
    ): Array<String> = throw RuntimeException("Stub!")

    override fun destroy() {
        Log.w(TAG, "destroy currentUrl=$currentUrl")
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "destroy")
        } catch (_: Exception) {
        }
    }

    override fun setNetworkAvailable(networkUp: Boolean): Unit = throw RuntimeException("Stub!")

    override fun saveState(outState: Bundle): WebBackForwardList = throw RuntimeException("Stub!")

    override fun savePicture(
        b: Bundle,
        dest: File,
    ): Boolean = throw RuntimeException("Stub!")

    override fun restorePicture(
        b: Bundle,
        src: File,
    ): Boolean = throw RuntimeException("Stub!")

    override fun restoreState(inState: Bundle): WebBackForwardList = throw RuntimeException("Stub!")

    override fun loadUrl(
        loadUrl: String,
        additionalHttpHeaders: Map<String, String>,
    ) {
        Log.w(
            TAG,
            "loadUrl url=$loadUrl headerCount=${additionalHttpHeaders.size} blockNetworkLoads=${settings.blockNetworkLoads}",
        )
        currentUrl = loadUrl
        progress = 0
        chromeClient.onProgressChanged(view, 0)
        pushSettings()
        send(
            OutgoingMessage.LoadRequest(
                url = loadUrl,
                method = "GET",
                headers = additionalHttpHeaders.map { RuntimeHeader(it.key, it.value) },
                bodyBase64 = null,
            ),
        )
    }

    override fun loadUrl(url: String) {
        loadUrl(url, mapOf())
    }

    override fun postUrl(
        url: String,
        postData: ByteArray,
    ) {
        Log.w(TAG, "postUrl url=$url bodyBytes=${postData.size}")
        currentUrl = url
        progress = 0
        chromeClient.onProgressChanged(view, 0)
        pushSettings()
        send(
            OutgoingMessage.LoadRequest(
                url = url,
                method = "POST",
                headers = emptyList(),
                bodyBase64 = Base64.getEncoder().encodeToString(postData),
            ),
        )
    }

    override fun loadData(
        data: String,
        mimeType: String,
        encoding: String,
    ) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null)
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String,
        encoding: String,
        historyUrl: String?,
    ) {
        Log.w(
            TAG,
            "loadDataWithBaseURL baseUrl=$baseUrl mimeType=$mimeType encoding=$encoding historyUrl=$historyUrl dataLength=${data.length}",
        )
        val bodyBase64 = Base64.getEncoder().encodeToString(data.toByteArray())
        val url = "data:$mimeType;charset=$encoding;base64,$bodyBase64"
        loadUrl(url)
    }

    override fun evaluateJavaScript(
        script: String,
        resultCallback: ValueCallback<String>,
    ) {
        Log.w(TAG, "evaluateJavaScript length=${script.length}")
        send(OutgoingMessage.EvaluateScript(script.removePrefix("javascript:")))
        handler.post(Runnable { resultCallback.onReceiveValue("null") })
    }

    override fun saveWebArchive(filename: String): Unit = throw RuntimeException("Stub!")

    override fun saveWebArchive(
        basename: String,
        autoname: Boolean,
        callback: ValueCallback<String>,
    ): Unit = throw RuntimeException("Stub!")

    override fun stopLoading() {
        Log.w(TAG, "stopLoading currentUrl=$currentUrl")
    }

    override fun reload() {
        Log.w(TAG, "reload currentUrl=$currentUrl")
        loadUrl(currentUrl)
    }

    override fun canGoBack(): Boolean = false

    override fun goBack(): Unit = throw RuntimeException("Stub!")

    override fun canGoForward(): Boolean = false

    override fun goForward(): Unit = throw RuntimeException("Stub!")

    override fun canGoBackOrForward(steps: Int): Boolean = throw RuntimeException("Stub!")

    override fun goBackOrForward(steps: Int): Unit = throw RuntimeException("Stub!")

    override fun isPrivateBrowsingEnabled(): Boolean = false

    override fun pageUp(top: Boolean): Boolean = throw RuntimeException("Stub!")

    override fun pageDown(bottom: Boolean): Boolean = throw RuntimeException("Stub!")

    override fun insertVisualStateCallback(
        requestId: Long,
        callback: VisualStateCallback,
    ): Unit = throw RuntimeException("Stub!")

    override fun clearView(): Unit = throw RuntimeException("Stub!")

    override fun capturePicture(): Picture = throw RuntimeException("Stub!")

    override fun createPrintDocumentAdapter(documentName: String): PrintDocumentAdapter = throw RuntimeException("Stub!")

    override fun getScale(): Float = 1f

    override fun setInitialScale(scaleInPercent: Int): Unit = throw RuntimeException("Stub!")

    override fun invokeZoomPicker(): Unit = throw RuntimeException("Stub!")

    override fun getHitTestResult(): HitTestResult = throw RuntimeException("Stub!")

    override fun requestFocusNodeHref(hrefMsg: Message): Unit = throw RuntimeException("Stub!")

    override fun requestImageRef(msg: Message): Unit = throw RuntimeException("Stub!")

    override fun getUrl(): String = currentUrl

    override fun getOriginalUrl(): String = currentUrl

    override fun getTitle(): String = ""

    override fun getFavicon(): Bitmap = throw RuntimeException("Stub!")

    override fun getTouchIconUrl(): String = throw RuntimeException("Stub!")

    override fun getProgress(): Int = progress

    override fun getContentHeight(): Int = 0

    override fun getContentWidth(): Int = 0

    override fun pauseTimers(): Unit = throw RuntimeException("Stub!")

    override fun resumeTimers(): Unit = throw RuntimeException("Stub!")

    override fun onPause(): Unit = throw RuntimeException("Stub!")

    override fun onResume(): Unit = throw RuntimeException("Stub!")

    override fun isPaused(): Boolean = false

    override fun freeMemory(): Unit = throw RuntimeException("Stub!")

    override fun clearCache(includeDiskFiles: Boolean): Unit = throw RuntimeException("Stub!")

    override fun clearFormData(): Unit = throw RuntimeException("Stub!")

    override fun clearHistory(): Unit = throw RuntimeException("Stub!")

    override fun clearSslPreferences(): Unit = throw RuntimeException("Stub!")

    override fun copyBackForwardList(): WebBackForwardList = throw RuntimeException("Stub!")

    override fun setFindListener(listener: WebView.FindListener): Unit = throw RuntimeException("Stub!")

    override fun findNext(forward: Boolean): Unit = throw RuntimeException("Stub!")

    override fun findAll(find: String): Int = throw RuntimeException("Stub!")

    override fun findAllAsync(find: String): Unit = throw RuntimeException("Stub!")

    override fun showFindDialog(
        text: String,
        showIme: Boolean,
    ): Boolean = throw RuntimeException("Stub!")

    override fun clearMatches(): Unit = throw RuntimeException("Stub!")

    override fun documentHasImages(response: Message): Unit = throw RuntimeException("Stub!")

    override fun setWebViewClient(client: WebViewClient) {
        Log.w(TAG, "setWebViewClient client=${client::class.java.name}")
        viewClient = client
    }

    override fun getWebViewClient(): WebViewClient = viewClient

    override fun getWebViewRenderProcess(): WebViewRenderProcess? = throw RuntimeException("Stub!")

    override fun setWebViewRenderProcessClient(
        executor: Executor?,
        client: WebViewRenderProcessClient?,
    ): Unit = throw RuntimeException("Stub!")

    override fun getWebViewRenderProcessClient(): WebViewRenderProcessClient? = throw RuntimeException("Stub!")

    override fun setDownloadListener(listener: DownloadListener): Unit = throw RuntimeException("Stub!")

    override fun setWebChromeClient(client: WebChromeClient) {
        Log.w(TAG, "setWebChromeClient client=${client::class.java.name}")
        chromeClient = client
    }

    override fun getWebChromeClient(): WebChromeClient = chromeClient

    override fun setPictureListener(listener: PictureListener): Unit = throw RuntimeException("Stub!")

    override fun addJavascriptInterface(
        obj: Any,
        interfaceName: String,
    ): Unit = throw RuntimeException("Stub!")

    override fun removeJavascriptInterface(interfaceName: String): Unit = throw RuntimeException("Stub!")

    override fun createWebMessageChannel(): Array<WebMessagePort> = throw RuntimeException("Stub!")

    override fun postMessageToMainFrame(
        message: WebMessage,
        targetOrigin: Uri,
    ): Unit = throw RuntimeException("Stub!")

    override fun getSettings(): WebSettings {
        Log.w(TAG, "getSettings")
        return settings
    }

    override fun setMapTrackballToArrowKeys(setMap: Boolean): Unit = throw RuntimeException("Stub!")

    override fun flingScroll(
        vx: Int,
        vy: Int,
    ): Unit = throw RuntimeException("Stub!")

    override fun getZoomControls(): View = throw RuntimeException("Stub!")

    override fun canZoomIn(): Boolean = false

    override fun canZoomOut(): Boolean = false

    override fun zoomBy(zoomFactor: Float): Boolean = throw RuntimeException("Stub!")

    override fun zoomIn(): Boolean = throw RuntimeException("Stub!")

    override fun zoomOut(): Boolean = throw RuntimeException("Stub!")

    override fun dumpViewHierarchyWithProperties(
        out: BufferedWriter,
        level: Int,
    ): Unit = throw RuntimeException("Stub!")

    override fun findHierarchyView(
        className: String,
        hashCode: Int,
    ): View = throw RuntimeException("Stub!")

    override fun setRendererPriorityPolicy(
        rendererRequestedPriority: Int,
        waivedWhenNotVisible: Boolean,
    ): Unit = throw RuntimeException("Stub!")

    override fun getRendererRequestedPriority(): Int = throw RuntimeException("Stub!")

    override fun getRendererPriorityWaivedWhenNotVisible(): Boolean = throw RuntimeException("Stub!")

    @SuppressWarnings("unused")
    override fun setTextClassifier(textClassifier: TextClassifier?) {}

    override fun getTextClassifier(): TextClassifier = TextClassifier.NO_OP

    override fun getViewDelegate(): ViewDelegate = BridgeViewDelegate()

    override fun getScrollDelegate(): ScrollDelegate = BridgeScrollDelegate()

    override fun notifyFindDialogDismissed(): Unit = throw RuntimeException("Stub!")

    class BridgeViewDelegate : ViewDelegate {
        override fun shouldDelayChildPressedState(): Boolean = throw RuntimeException("Stub!")

        override fun onProvideVirtualStructure(structure: android.view.ViewStructure): Unit = throw RuntimeException("Stub!")

        override fun onProvideAutofillVirtualStructure(
            structure: android.view.ViewStructure,
            flags: Int,
        ) {}

        override fun autofill(values: SparseArray<AutofillValue>) {}

        override fun isVisibleToUserForAutofill(virtualId: Int): Boolean = true

        override fun onProvideContentCaptureStructure(
            structure: android.view.ViewStructure,
            flags: Int,
        ) {}

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = throw RuntimeException("Stub!")

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo): Unit = throw RuntimeException("Stub!")

        override fun onInitializeAccessibilityEvent(event: AccessibilityEvent): Unit = throw RuntimeException("Stub!")

        override fun performAccessibilityAction(
            action: Int,
            arguments: Bundle,
        ): Boolean = throw RuntimeException("Stub!")

        override fun setOverScrollMode(mode: Int): Unit = throw RuntimeException("Stub!")

        override fun setScrollBarStyle(style: Int): Unit = throw RuntimeException("Stub!")

        override fun onDrawVerticalScrollBar(
            canvas: Canvas,
            scrollBar: Drawable,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun onOverScrolled(
            scrollX: Int,
            scrollY: Int,
            clampedX: Boolean,
            clampedY: Boolean,
        ): Unit = throw RuntimeException("Stub!")

        override fun onWindowVisibilityChanged(visibility: Int): Unit = throw RuntimeException("Stub!")

        override fun onDraw(canvas: Canvas): Unit = throw RuntimeException("Stub!")

        override fun setLayoutParams(layoutParams: LayoutParams): Unit = throw RuntimeException("Stub!")

        override fun performLongClick(): Boolean = throw RuntimeException("Stub!")

        override fun onConfigurationChanged(newConfig: Configuration): Unit = throw RuntimeException("Stub!")

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection = throw RuntimeException("Stub!")

        override fun onDragEvent(event: DragEvent): Boolean = throw RuntimeException("Stub!")

        override fun onKeyMultiple(
            keyCode: Int,
            repeatCount: Int,
            event: KeyEvent,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onKeyUp(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onAttachedToWindow(): Unit = throw RuntimeException("Stub!")

        override fun onDetachedFromWindow(): Unit = throw RuntimeException("Stub!")

        override fun onMovedToDisplay(
            displayId: Int,
            config: Configuration,
        ) {}

        override fun onVisibilityChanged(
            changedView: View,
            visibility: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun onWindowFocusChanged(hasWindowFocus: Boolean): Unit = throw RuntimeException("Stub!")

        override fun onFocusChanged(
            focused: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect,
        ): Unit = throw RuntimeException("Stub!")

        override fun setFrame(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onSizeChanged(
            w: Int,
            h: Int,
            ow: Int,
            oh: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun onScrollChanged(
            l: Int,
            t: Int,
            oldl: Int,
            oldt: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = throw RuntimeException("Stub!")

        override fun onTouchEvent(ev: MotionEvent): Boolean = throw RuntimeException("Stub!")

        override fun onHoverEvent(event: MotionEvent): Boolean = throw RuntimeException("Stub!")

        override fun onGenericMotionEvent(event: MotionEvent): Boolean = throw RuntimeException("Stub!")

        override fun onTrackballEvent(ev: MotionEvent): Boolean = throw RuntimeException("Stub!")

        override fun requestFocus(
            direction: Int,
            previouslyFocusedRect: Rect,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun requestChildRectangleOnScreen(
            child: View,
            rect: Rect,
            immediate: Boolean,
        ): Boolean = throw RuntimeException("Stub!")

        override fun setBackgroundColor(color: Int): Unit = throw RuntimeException("Stub!")

        override fun setLayerType(
            layerType: Int,
            paint: Paint,
        ) {}

        override fun preDispatchDraw(canvas: Canvas): Unit = throw RuntimeException("Stub!")

        override fun onStartTemporaryDetach(): Unit = throw RuntimeException("Stub!")

        override fun onFinishTemporaryDetach(): Unit = throw RuntimeException("Stub!")

        override fun onActivityResult(
            requestCode: Int,
            resultCode: Int,
            data: Intent,
        ): Unit = throw RuntimeException("Stub!")

        override fun getHandler(originalHandler: Handler): Handler = throw RuntimeException("Stub!")

        override fun findFocus(originalFocusedView: View): View = throw RuntimeException("Stub!")

        @SuppressWarnings("unused")
        override fun onCheckIsTextEditor(): Boolean = false

        @SuppressWarnings("unused")
        override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets? = null

        @SuppressWarnings("unused")
        override fun onResolvePointerIcon(
            event: MotionEvent,
            pointerIndex: Int,
        ): PointerIcon? = null
    }

    class BridgeScrollDelegate : ScrollDelegate {
        override fun computeHorizontalScrollRange(): Int = throw RuntimeException("Stub!")

        override fun computeHorizontalScrollOffset(): Int = throw RuntimeException("Stub!")

        override fun computeVerticalScrollRange(): Int = throw RuntimeException("Stub!")

        override fun computeVerticalScrollOffset(): Int = throw RuntimeException("Stub!")

        override fun computeVerticalScrollExtent(): Int = throw RuntimeException("Stub!")

        override fun computeScroll(): Unit = throw RuntimeException("Stub!")
    }

    @Serializable
    private sealed class OutgoingMessage {
        @Serializable
        @SerialName("loadRequest")
        data class LoadRequest(
            val url: String,
            val method: String,
            val headers: List<RuntimeHeader> = emptyList(),
            @SerialName("bodyBase64")
            val bodyBase64: String? = null,
        ) : OutgoingMessage()

        @Serializable
        @SerialName("evaluateScript")
        data class EvaluateScript(
            val script: String,
        ) : OutgoingMessage()

        @Serializable
        @SerialName("setSettings")
        data class SetSettings(
            @SerialName("userAgent")
            val userAgent: String? = null,
            @SerialName("blockNetworkLoads")
            val blockNetworkLoads: Boolean? = null,
        ) : OutgoingMessage()

        @Serializable
        @SerialName("interceptResponse")
        data class InterceptResponse(
            @SerialName("requestId")
            val requestId: String,
            val action: String,
            val response: RuntimeResponsePayload? = null,
        ) : OutgoingMessage()
    }

    @Serializable
    private sealed class IncomingMessage {
        @Serializable
        @SerialName("pageStarted")
        data class PageStarted(
            val url: String,
        ) : IncomingMessage()

        @Serializable
        @SerialName("pageFinished")
        data class PageFinished(
            val url: String,
            @SerialName("httpStatusCode")
            val httpStatusCode: Int,
        ) : IncomingMessage()

        @Serializable
        @SerialName("loadError")
        data class LoadError(
            val url: String,
            @SerialName("errorText")
            val errorText: String,
        ) : IncomingMessage()

        @Serializable
        @SerialName("interceptRequest")
        data class InterceptRequest(
            @SerialName("requestId")
            val requestId: String,
            val url: String,
            val method: String,
            val headers: List<RuntimeHeader> = emptyList(),
            @SerialName("isMainFrame")
            val isMainFrame: Boolean,
            @SerialName("isRedirect")
            val isRedirect: Boolean,
            @SerialName("frameUrl")
            val frameUrl: String,
        ) : IncomingMessage()
    }

    @Serializable
    private data class RuntimeHeader(
        val name: String,
        val value: String,
    )

    @Serializable
    private data class RuntimeResponsePayload(
        @SerialName("statusCode")
        val statusCode: Int,
        @SerialName("reasonPhrase")
        val reasonPhrase: String,
        @SerialName("mimeType")
        val mimeType: String,
        val headers: List<RuntimeHeader> = emptyList(),
        @SerialName("bodyBase64")
        val bodyBase64: String,
    )

    companion object {
        private const val TAG = "ManatanCefWebView"
    }
}
