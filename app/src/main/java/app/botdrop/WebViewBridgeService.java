package app.botdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.termux.R;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebView CDP Bridge Service
 * 
 * Provides a WebSocket server that exposes Chrome DevTools Protocol (CDP)
 * for OpenClaw/Playwright to control an embedded WebView.
 * 
 * Architecture:
 * - HTTP server on 127.0.0.1:9222 (CDP discovery endpoints)
 * - WebSocket server on 127.0.0.1:9223 (CDP protocol)
 * - Hidden WebView with debugging enabled
 */
public class WebViewBridgeService extends Service {
    
    private static final String TAG = "WebViewBridge";
    private static final String CHANNEL_ID = "webview_bridge_channel";
    private static final int NOTIFICATION_ID = 1002;
    
    // CDP server configuration
    private static final String CDP_HOST = "127.0.0.1";
    private static final int HTTP_PORT = 9222;
    private static final int WS_PORT = 9223;
    
    private final IBinder mBinder = new LocalBinder();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    
    // Server state
    private HttpDiscoveryServer mHttpServer;
    private CdpWebSocketServer mWebSocketServer;
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    
    // WebView state
    private WebView mWebView;
    private final Object mWebViewLock = new Object();
    private final AtomicInteger mExecutionId = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> mPendingExecutions = new ConcurrentHashMap<>();
    
    // Target state
    private String mTargetId = "main";
    private volatile String mTargetTitle = "about:blank";
    private volatile String mTargetUrl = "about:blank";
    private volatile boolean mIsLoading = false;
    private String mSessionId = "session-1";
    
    public class LocalBinder extends Binder {
        public WebViewBridgeService getService() {
            return WebViewBridgeService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WebViewBridgeService created");
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "WebViewBridgeService started");
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, buildNotification());
        
        // Initialize WebView on main thread
        mHandler.post(this::initializeWebView);
        
        // Start servers
        startServers();
        
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "WebViewBridgeService destroyed");
        stopServers();
        mHandler.post(this::destroyWebView);
    }
    
    /**
     * Initialize the hidden WebView
     */
    private void initializeWebView() {
        synchronized (mWebViewLock) {
            if (mWebView != null) {
                return;
            }
            
            // Create WebView with null context (hidden)
            mWebView = new WebView(getApplicationContext());
            
            // Configure WebView settings
            WebSettings settings = mWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; BotDrop) AppleWebKit/537.36");
            
            // Enable debugging (critical for CDP)
            WebView.setWebContentsDebuggingEnabled(true);
            
            // Add JavaScript interface for communication
            mWebView.addJavascriptInterface(new JsBridge(), "BotDrop");
            
            // Set WebView client to track loading state
            mWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    Log.d(TAG, "Page started loading: " + url);
                    mIsLoading = true;
                    mTargetUrl = url;
                    notifyLoadingEvent("Page.loadEventFired");
                }
                
                @Override
                public void onPageFinished(WebView view, String url) {
                    Log.d(TAG, "Page finished loading: " + url);
                    mIsLoading = false;
                    mTargetUrl = url;
                    mTargetTitle = view.getTitle();
                    notifyLoadingEvent("Page.loadEventFired");
                }
                
                public void onReceivedTitle(WebView view, String title) {
                    mTargetTitle = title;
                }
            });
            
            mWebView.setWebChromeClient(new WebChromeClient());
            
            // Load initial blank page
            mWebView.loadUrl("about:blank");
            
            Log.i(TAG, "WebView initialized successfully");
        }
    }
    
    /**
     * Destroy the WebView
     */
    private void destroyWebView() {
        synchronized (mWebViewLock) {
            if (mWebView != null) {
                mWebView.stopLoading();
                mWebView.loadUrl("about:blank");
                mWebView.destroy();
                mWebView = null;
                Log.i(TAG, "WebView destroyed");
            }
        }
    }
    
    /**
     * JavaScript bridge for communication
     */
    public class JsBridge {
        @JavascriptInterface
        public void onExecutionResult(int id, String result) {
            CompletableFuture<String> future = mPendingExecutions.remove(id);
            if (future != null) {
                future.complete(result);
            }
        }
    }
    
    /**
     * Execute JavaScript in WebView
     */
    private CompletableFuture<String> executeJavaScript(String script) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        mHandler.post(() -> {
            synchronized (mWebViewLock) {
                if (mWebView == null) {
                    future.completeExceptionally(new IllegalStateException("WebView not initialized"));
                    return;
                }
                
                // Use WebView's evaluateJavascript callback directly
                mWebView.evaluateJavascript(script, result -> {
                    // result comes as a JSON string from WebView
                    if (result == null || result.equals("null")) {
                        future.complete("null");
                    } else {
                        // Remove JSON quotes if it's a string
                        if (result.startsWith("\"") && result.endsWith("\"")) {
                            result = result.substring(1, result.length() - 1)
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                        }
                        future.complete(result);
                    }
                });
            }
        });
        
        // Set timeout
        mHandler.postDelayed(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new java.util.concurrent.TimeoutException("Script execution timeout"));
            }
        }, 30000);
        
        return future;
    }
    
    /**
     * Navigate to URL
     */
    private void navigateTo(String url) {
        mHandler.post(() -> {
            synchronized (mWebViewLock) {
                if (mWebView != null) {
                    mWebView.loadUrl(url);
                }
            }
        });
    }
    
    /**
     * Take screenshot
     */
    private CompletableFuture<String> takeScreenshot(boolean fullPage) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        mHandler.post(() -> {
            synchronized (mWebViewLock) {
                if (mWebView == null) {
                    future.completeExceptionally(new IllegalStateException("WebView not initialized"));
                    return;
                }
                
                try {
                    // Measure WebView
                    mWebView.measure(View.MeasureSpec.makeMeasureSpec(
                        View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(
                        View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED));
                    mWebView.layout(0, 0, mWebView.getMeasuredWidth(), mWebView.getMeasuredHeight());
                    
                    // Create bitmap
                    int width = mWebView.getMeasuredWidth();
                    int height = fullPage ? mWebView.getContentHeight() : mWebView.getMeasuredHeight();
                    if (width == 0) width = 1920;
                    if (height == 0) height = 1080;
                    
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                    mWebView.draw(canvas);
                    
                    // Convert to base64
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                    byte[] bytes = outputStream.toByteArray();
                    String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    
                    bitmap.recycle();
                    future.complete(base64);
                    
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });
        
        return future;
    }
    
    /**
     * Notify loading events to CDP clients
     */
    private void notifyLoadingEvent(String eventMethod) {
        if (mWebSocketServer == null) return;
        
        try {
            JSONObject event = new JSONObject();
            event.put("method", eventMethod);
            JSONObject params = new JSONObject();
            params.put("timestamp", System.currentTimeMillis() / 1000.0);
            event.put("params", params);
            
            mWebSocketServer.broadcast(event.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending event", e);
        }
    }
    
    /**
     * Check if running
     */
    public boolean isRunning() {
        return mIsRunning.get();
    }
    
    /**
     * Start servers
     */
    private void startServers() {
        if (mIsRunning.get()) return;
        
        try {
            mHttpServer = new HttpDiscoveryServer(HTTP_PORT);
            mHttpServer.start();
            
            mWebSocketServer = new CdpWebSocketServer(WS_PORT);
            mWebSocketServer.start();
            
            mIsRunning.set(true);
            Log.i(TAG, "CDP servers started - HTTP:" + HTTP_PORT + " WS:" + WS_PORT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start servers", e);
            mIsRunning.set(false);
        }
    }
    
    /**
     * Stop servers
     */
    private void stopServers() {
        mIsRunning.set(false);
        
        if (mHttpServer != null) {
            mHttpServer.stopServer();
            mHttpServer = null;
        }
        
        if (mWebSocketServer != null) {
            try {
                mWebSocketServer.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping WebSocket server", e);
            }
            mWebSocketServer = null;
        }
    }
    
    // ... (createNotificationChannel, buildNotification, getVersionJson, getTargetsJson remain the same)
    
    /**
     * Create notification channel
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "WebView Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps WebView CDP bridge running for OpenClaw");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * Build notification
     */
    private Notification buildNotification() {
        Intent intent = new Intent(this, DashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        );
        
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BotDrop WebView Bridge")
            .setContentText("CDP server running on port " + HTTP_PORT)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    /**
     * Get version JSON
     */
    private JSONObject getVersionJson() {
        JSONObject version = new JSONObject();
        try {
            version.put("Browser", "BotDrop WebView/120.0.0.0");
            version.put("Protocol-Version", "1.3");
            version.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; BotDrop)");
            version.put("V8-Version", "12.0.0");
            version.put("WebKit-Version", "537.36");
            version.put("webSocketDebuggerUrl", "ws://" + CDP_HOST + ":" + WS_PORT + "/" + mTargetId);
        } catch (Exception e) {
            Log.e(TAG, "Error creating version JSON", e);
        }
        return version;
    }
    
    /**
     * Get targets JSON
     */
    private JSONArray getTargetsJson() {
        JSONArray targets = new JSONArray();
        try {
            JSONObject target = new JSONObject();
            target.put("description", "");
            target.put("devtoolsFrontendUrl", "devtools://devtools/bundled/inspector.html?ws=" + CDP_HOST + ":" + WS_PORT + "/" + mTargetId);
            target.put("faviconUrl", "");
            target.put("id", mTargetId);
            target.put("title", mTargetTitle);
            target.put("type", "page");
            target.put("url", mTargetUrl);
            target.put("webSocketDebuggerUrl", "ws://" + CDP_HOST + ":" + WS_PORT + "/" + mTargetId);
            targets.put(target);
        } catch (Exception e) {
            Log.e(TAG, "Error creating targets JSON", e);
        }
        return targets;
    }
    
    /**
     * HTTP Discovery Server
     */
    private class HttpDiscoveryServer {
        private ServerSocket mServerSocket;
        private final int mPort;
        private volatile boolean mRunning = false;
        private Thread mServerThread;
        
        public HttpDiscoveryServer(int port) {
            mPort = port;
        }
        
        public void start() {
            mServerThread = new Thread(this::runServer);
            mServerThread.start();
        }
        
        private void runServer() {
            try {
                mServerSocket = new ServerSocket(mPort, 0, java.net.InetAddress.getByName(CDP_HOST));
                mRunning = true;
                Log.i(TAG, "HTTP discovery server listening on " + CDP_HOST + ":" + mPort);
                
                while (mRunning && !mServerSocket.isClosed()) {
                    try {
                        Socket client = mServerSocket.accept();
                        handleClient(client);
                    } catch (IOException e) {
                        if (mRunning) {
                            Log.e(TAG, "Error accepting client", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "HTTP server error", e);
            }
        }
        
        private void handleClient(Socket client) {
            new Thread(() -> {
                try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    PrintWriter writer = new PrintWriter(client.getOutputStream(), true)
                ) {
                    String requestLine = reader.readLine();
                    if (requestLine == null) return;
                    
                    Log.d(TAG, "HTTP request: " + requestLine);
                    
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // Skip headers
                    }
                    
                    String[] parts = requestLine.split(" ");
                    if (parts.length < 2) {
                        send404(writer);
                        return;
                    }
                    
                    String path = parts[1];
                    
                    if (path.equals("/json/version")) {
                        sendJson(writer, getVersionJson());
                    } else if (path.equals("/json") || path.equals("/json/list")) {
                        sendJson(writer, getTargetsJson());
                    } else if (path.equals("/") || path.equals("/status")) {
                        sendStatus(writer);
                    } else {
                        send404(writer);
                    }
                    
                } catch (IOException e) {
                    Log.e(TAG, "Error handling HTTP client", e);
                } finally {
                    try {
                        client.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }).start();
        }
        
        private void sendJson(PrintWriter writer, Object json) {
            String body = json.toString();
            writer.print("HTTP/1.1 200 OK\r\n");
            writer.print("Content-Type: application/json; charset=utf-8\r\n");
            writer.print("Content-Length: " + body.getBytes().length + "\r\n");
            writer.print("Connection: close\r\n");
            writer.print("\r\n");
            writer.print(body);
            writer.flush();
        }
        
        private void sendStatus(PrintWriter writer) {
            String body = "<html><head><title>BotDrop CDP Bridge</title></head>" +
                "<body><h1>BotDrop WebView CDP Bridge</h1>" +
                "<p>Status: " + (mIsRunning.get() ? "Running" : "Stopped") + "</p>" +
                "<p>HTTP Port: " + HTTP_PORT + "</p>" +
                "<p>WebSocket Port: " + WS_PORT + "</p>" +
                "<p>Target: " + mTargetUrl + "</p>" +
                "<p><a href='/json/version'>/json/version</a></p>" +
                "<p><a href='/json'>/json</a></p>" +
                "</body></html>";
            writer.print("HTTP/1.1 200 OK\r\n");
            writer.print("Content-Type: text/html; charset=utf-8\r\n");
            writer.print("Content-Length: " + body.getBytes().length + "\r\n");
            writer.print("Connection: close\r\n");
            writer.print("\r\n");
            writer.print(body);
            writer.flush();
        }
        
        private void send404(PrintWriter writer) {
            String body = "<html><body><h1>404 Not Found</h1></body></html>";
            writer.print("HTTP/1.1 404 Not Found\r\n");
            writer.print("Content-Type: text/html; charset=utf-8\r\n");
            writer.print("Content-Length: " + body.getBytes().length + "\r\n");
            writer.print("Connection: close\r\n");
            writer.print("\r\n");
            writer.print(body);
            writer.flush();
        }
        
        public void stopServer() {
            mRunning = false;
            if (mServerSocket != null) {
                try {
                    mServerSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            if (mServerThread != null) {
                mServerThread.interrupt();
            }
        }
    }
    
    /**
     * CDP WebSocket Server
     */
    private class CdpWebSocketServer extends WebSocketServer {
        
        public CdpWebSocketServer(int port) {
            super(new InetSocketAddress(CDP_HOST, port));
        }
        
        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            Log.i(TAG, "CDP client connected: " + conn.getRemoteSocketAddress());
            
            try {
                // Send Target.targetCreated
                JSONObject event = new JSONObject();
                event.put("method", "Target.targetCreated");
                JSONObject params = new JSONObject();
                JSONObject targetInfo = new JSONObject();
                targetInfo.put("targetId", mTargetId);
                targetInfo.put("type", "page");
                targetInfo.put("title", mTargetTitle);
                targetInfo.put("url", mTargetUrl);
                targetInfo.put("attached", true);
                params.put("targetInfo", targetInfo);
                event.put("params", params);
                conn.send(event.toString());
                
                // Send Target.attachedToTarget
                JSONObject attachEvent = new JSONObject();
                attachEvent.put("method", "Target.attachedToTarget");
                JSONObject attachParams = new JSONObject();
                attachParams.put("sessionId", mSessionId);
                attachParams.put("targetInfo", targetInfo);
                attachParams.put("waitingForDebugger", false);
                attachEvent.put("params", attachParams);
                conn.send(attachEvent.toString());
                
            } catch (Exception e) {
                Log.e(TAG, "Error sending initial events", e);
            }
        }
        
        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            Log.i(TAG, "CDP client disconnected: " + reason);
        }
        
        @Override
        public void onMessage(WebSocket conn, String message) {
            Log.d(TAG, "CDP message: " + message);
            
            try {
                JSONObject request = new JSONObject(message);
                int id = request.optInt("id", 0);
                String method = request.optString("method", "");
                JSONObject requestParams = request.optJSONObject("params");
                
                // Handle CDP methods
                switch (method) {
                    case "Browser.getVersion":
                        handleGetVersion(conn, id);
                        break;
                        
                    case "Target.getTargets":
                        handleGetTargets(conn, id);
                        break;
                        
                    case "Target.attachToTarget":
                        handleAttachToTarget(conn, id, requestParams);
                        break;
                        
                    case "Target.createTarget":
                        handleCreateTarget(conn, id, requestParams);
                        break;
                        
                    case "Page.enable":
                    case "Runtime.enable":
                    case "DOM.enable":
                    case "Network.enable":
                    case "Target.setDiscoverTargets":
                    case "Target.setAutoAttach":
                    case "Security.enable":
                    case "Performance.enable":
                    case "Log.enable":
                        sendResult(conn, id, new JSONObject());
                        break;
                        
                    case "Page.navigate":
                        handleNavigate(conn, id, requestParams);
                        break;
                        
                    case "Runtime.evaluate":
                        handleEvaluate(conn, id, requestParams);
                        break;
                        
                    case "Page.captureScreenshot":
                        handleScreenshot(conn, id, requestParams);
                        break;
                        
                    case "Page.getContent":
                        handleGetContent(conn, id);
                        break;
                        
                    case "Input.click":
                        handleClick(conn, id, requestParams);
                        break;
                        
                    case "Input.insertText":
                        handleType(conn, id, requestParams);
                        break;
                        
                    default:
                        Log.w(TAG, "Unknown CDP method: " + method);
                        sendResult(conn, id, new JSONObject());
                        break;
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error handling CDP message", e);
            }
        }
        
        private void handleGetVersion(WebSocket conn, int id) throws Exception {
            JSONObject result = new JSONObject();
            result.put("protocolVersion", "1.3");
            result.put("product", "BotDrop/WebView");
            result.put("revision", "1.0");
            result.put("userAgent", "Mozilla/5.0 (Linux; Android 10)");
            result.put("jsVersion", "12.0");
            sendResult(conn, id, result);
        }
        
        private void handleGetTargets(WebSocket conn, int id) throws Exception {
            JSONObject result = new JSONObject();
            result.put("targetInfos", getTargetsJson());
            sendResult(conn, id, result);
        }
        
        private void handleAttachToTarget(WebSocket conn, int id, JSONObject params) throws Exception {
            JSONObject result = new JSONObject();
            result.put("sessionId", mSessionId);
            sendResult(conn, id, result);
        }
        
        private void handleCreateTarget(WebSocket conn, int id, JSONObject params) throws Exception {
            String url = params != null ? params.optString("url", "about:blank") : "about:blank";
            navigateTo(url);
            
            JSONObject result = new JSONObject();
            result.put("targetId", mTargetId);
            sendResult(conn, id, result);
        }
        
        private void handleNavigate(WebSocket conn, int id, JSONObject params) {
            String url = params != null ? params.optString("url", "about:blank") : "about:blank";
            navigateTo(url);
            
            mHandler.postDelayed(() -> {
                try {
                    JSONObject result = new JSONObject();
                    result.put("frameId", mTargetId);
                    result.put("loaderId", "loader-" + System.currentTimeMillis());
                    sendResult(conn, id, result);
                } catch (Exception e) {
                    Log.e(TAG, "Error sending navigate result", e);
                }
            }, 500);
        }
        
        private void handleEvaluate(WebSocket conn, int id, JSONObject params) {
            String expression = params != null ? params.optString("expression", "") : "";
            boolean returnByValue = params != null ? params.optBoolean("returnByValue", true) : true;
            
            CompletableFuture<String> future = executeJavaScript(expression);
            future.whenComplete((result, error) -> {
                try {
                    JSONObject response = new JSONObject();
                    response.put("id", id);
                    
                    if (error != null) {
                        JSONObject exception = new JSONObject();
                        exception.put("type", "string");
                        exception.put("value", error.getMessage());
                        response.put("result", new JSONObject().put("exceptionDetails", 
                            new JSONObject().put("text", error.getMessage())));
                    } else {
                        JSONObject resultObj = new JSONObject();
                        resultObj.put("type", "string");
                        resultObj.put("value", result);
                        response.put("result", new JSONObject().put("result", resultObj));
                    }
                    
                    conn.send(response.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error sending evaluate result", e);
                }
            });
        }
        
        private void handleScreenshot(WebSocket conn, int id, JSONObject params) {
            boolean fullPage = params != null ? params.optBoolean("fullPage", false) : false;
            String format = params != null ? params.optString("format", "png") : "png";
            
            CompletableFuture<String> future = takeScreenshot(fullPage);
            future.whenComplete((base64, error) -> {
                try {
                    JSONObject response = new JSONObject();
                    response.put("id", id);
                    
                    if (error != null) {
                        response.put("error", new JSONObject().put("message", error.getMessage()));
                    } else {
                        JSONObject result = new JSONObject();
                        result.put("data", base64);
                        response.put("result", result);
                    }
                    
                    conn.send(response.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error sending screenshot result", e);
                }
            });
        }
        
        private void handleGetContent(WebSocket conn, int id) {
            executeJavaScript("document.documentElement.outerHTML").whenComplete((html, error) -> {
                try {
                    JSONObject response = new JSONObject();
                    response.put("id", id);
                    
                    if (error != null) {
                        response.put("error", new JSONObject().put("message", error.getMessage()));
                    } else {
                        response.put("result", new JSONObject().put("content", html));
                    }
                    
                    conn.send(response.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error sending content result", e);
                }
            });
        }
        
        private void handleClick(WebSocket conn, int id, JSONObject params) {
            int x = params != null ? params.optInt("x", 0) : 0;
            int y = params != null ? params.optInt("y", 0) : 0;
            String selector = params != null ? params.optString("selector", "") : "";
            
            String script;
            if (!selector.isEmpty()) {
                // Click by CSS selector
                script = "(function() {" +
                    "  var el = document.querySelector('" + selector.replace("'", "\\'") + "');" +
                    "  if (el) { el.click(); return 'clicked: " + selector + "'; }" +
                    "  else return 'element not found: " + selector + "';" +
                    "})()";
            } else {
                // Click by coordinates using MouseEvent
                script = "(function() {" +
                    "  var el = document.elementFromPoint(" + x + ", " + y + ");" +
                    "  if (el) {" +
                    "    var evt = new MouseEvent('click', { bubbles: true, cancelable: true });" +
                    "    el.dispatchEvent(evt);" +
                    "    return 'clicked at: " + x + "," + y + "';" +
                    "  } else return 'no element at: " + x + "," + y + "';" +
                    "})()";
            }
            
            executeJavaScript(script).whenComplete((result, error) -> {
                try {
                    JSONObject response = new JSONObject();
                    response.put("id", id);
                    
                    if (error != null) {
                        response.put("error", new JSONObject().put("message", error.getMessage()));
                    } else {
                        response.put("result", new JSONObject());
                    }
                    
                    conn.send(response.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error sending click result", e);
                }
            });
        }
        
        private void handleType(WebSocket conn, int id, JSONObject params) {
            String selector = params != null ? params.optString("selector", "") : "";
            String text = params != null ? params.optString("text", "") : "";
            int x = params != null ? params.optInt("x", -1) : -1;
            int y = params != null ? params.optInt("y", -1) : -1;
            
            String script;
            if (!selector.isEmpty()) {
                // Type by CSS selector
                script = "(function() {" +
                    "  var el = document.querySelector('" + selector.replace("'", "\\'") + "');" +
                    "  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {" +
                    "    el.value = '" + text.replace("'", "\\'") + "';" +
                    "    el.dispatchEvent(new Event('input', { bubbles: true }));" +
                    "    el.dispatchEvent(new Event('change', { bubbles: true }));" +
                    "    return 'typed into: " + selector + "';" +
                    "  } else return 'input element not found: " + selector + "';" +
                    "})()";
            } else if (x >= 0 && y >= 0) {
                // Type by coordinates
                script = "(function() {" +
                    "  var el = document.elementFromPoint(" + x + ", " + y + ");" +
                    "  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {" +
                    "    el.value = '" + text.replace("'", "\\'") + "';" +
                    "    el.dispatchEvent(new Event('input', { bubbles: true }));" +
                    "    el.dispatchEvent(new Event('change', { bubbles: true }));" +
                    "    return 'typed at: " + x + "," + y + "';" +
                    "  } else return 'no input element at: " + x + "," + y + "';" +
                    "})()";
            } else {
                // Fallback: try to find focused element or first input
                script = "(function() {" +
                    "  var el = document.activeElement;" +
                    "  if (!el || el.tagName === 'BODY') {" +
                    "    el = document.querySelector('input, textarea');" +
                    "  }" +
                    "  if (el) {" +
                    "    el.value = '" + text.replace("'", "\\'") + "';" +
                    "    el.dispatchEvent(new Event('input', { bubbles: true }));" +
                    "    el.dispatchEvent(new Event('change', { bubbles: true }));" +
                    "    return 'typed into focused element';" +
                    "  } else return 'no input element found';" +
                    "})()";
            }
            
            executeJavaScript(script).whenComplete((result, error) -> {
                try {
                    JSONObject response = new JSONObject();
                    response.put("id", id);
                    
                    if (error != null) {
                        response.put("error", new JSONObject().put("message", error.getMessage()));
                    } else {
                        response.put("result", new JSONObject());
                    }
                    
                    conn.send(response.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error sending type result", e);
                }
            });
        }
        
        private void sendResult(WebSocket conn, int id, JSONObject result) {
            try {
                JSONObject response = new JSONObject();
                response.put("id", id);
                response.put("result", result);
                conn.send(response.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending result", e);
            }
        }
        
        public void broadcast(String message) {
            for (WebSocket conn : getConnections()) {
                if (conn.isOpen()) {
                    conn.send(message);
                }
            }
        }
        
        @Override
        public void onError(WebSocket conn, Exception ex) {
            Log.e(TAG, "WebSocket error", ex);
        }
        
        @Override
        public void onStart() {
            Log.i(TAG, "CDP WebSocket server started on port " + WS_PORT);
        }
    }
}
