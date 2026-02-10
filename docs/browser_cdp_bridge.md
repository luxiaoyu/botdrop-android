# OpenClaw WebView CDP Bridge 技术方案

## 概述

本方案为 BotDrop Android 应用提供了完整的浏览器自动化能力，使 OpenClaw AI Agent 能够浏览网页、执行 JavaScript、截取屏幕截图等。

## 架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Android App (app.botdrop)                          │
│                                                                              │
│  ┌─────────────────────────┐        ┌─────────────────────────────────────┐ │
│  │   GatewayMonitorService │        │      WebViewBridgeService           │ │
│  │   (前台服务)             │───────►│      (前台服务)                      │ │
│  │                         │        │                                     │ │
│  │  - 启动 Gateway         │        │  ├─ HTTP Server  :9222              │ │
│  │  - 监控 Gateway 状态    │        │  ├─ WebSocket    :9223              │ │
│  │  - 自动重启             │        │  └─ Hidden WebView (无UI)           │ │
│  └─────────────────────────┘        │       - setWebContentsDebugging     │ │
└─────────────────────────────────────└─────────────────────────────────────┘
                                              │
                                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      OpenClaw Gateway (Termux)                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │  Browser Profile: webview                                                │ │
│  │  ├─ cdpUrl: http://127.0.0.1:9222                                       │ │
│  │  └─ 工具: open / snapshot / click / type 等                              │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────┘
```

## 实现细节

### 1. WebViewBridgeService.java

**位置**: `app/src/main/java/app/botdrop/WebViewBridgeService.java`

**功能**:
- **HTTP 发现服务** (端口 9222): 提供 CDP 兼容的 REST API
  - `GET /json/version` - 返回浏览器版本信息
  - `GET /json` - 返回可用 targets 列表
- **WebSocket 服务** (端口 9223): CDP 协议通信
- **Hidden WebView**: 实际的浏览器引擎
  - 启用 `setWebContentsDebuggingEnabled(true)`
  - JavaScript 执行通过 `evaluateJavascript()` 回调获取结果
  - 截图通过 `WebView.draw()` 到 Canvas 实现

**关键代码**:
```java
// 启用 WebView 调试
WebView.setWebContentsDebuggingEnabled(true);

// JavaScript 执行并获取结果
mWebView.evaluateJavascript(script, result -> {
    future.complete(result);
});

// 截图实现
Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
Canvas canvas = new Canvas(bitmap);
mWebView.draw(canvas);
```

### 2. GatewayMonitorService.java 集成

**修改**: `app/src/main/java/app/botdrop/GatewayMonitorService.java`

**改动**:
- 在 `onStartCommand()` 中自动启动 `WebViewBridgeService`
- 添加 `startWebViewBridgeService()` 方法

**代码**:
```java
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    // ... existing code ...
    
    // Start WebView Bridge Service for OpenClaw browser support
    startWebViewBridgeService();
    
    return START_STICKY;
}

private void startWebViewBridgeService() {
    Intent serviceIntent = new Intent(this, WebViewBridgeService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(serviceIntent);
    } else {
        startService(serviceIntent);
    }
}
```

### 3. 依赖配置

**修改**: `app/build.gradle`

```gradle
dependencies {
    // ... existing dependencies ...
    implementation 'org.java-websocket:Java-WebSocket:1.5.6'
}
```

### 4. AndroidManifest.xml 注册

**修改**: `app/src/main/AndroidManifest.xml`

```xml
<service
    android:name="app.botdrop.WebViewBridgeService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="CDP bridge for OpenClaw browser control" />
</service>
```

### 5. BotDropConfig.java 自动配置

**修改**: `app/src/main/java/app/botdrop/BotDropConfig.java`

**功能**: 用户首次配置 API Key 时，自动添加 browser 配置到 `openclaw.json`

**代码**:
```java
// Configure browser to use WebView Bridge (auto-starts with Gateway)
if (!config.has("browser")) {
    config.put("browser", new JSONObject());
}
JSONObject browser = config.getJSONObject("browser");
if (!browser.has("enabled")) {
    browser.put("enabled", true);
}
if (!browser.has("defaultProfile")) {
    browser.put("defaultProfile", "webview");
}
if (!browser.has("profiles")) {
    JSONObject profiles = new JSONObject();
    JSONObject webview = new JSONObject();
    webview.put("cdpUrl", "http://127.0.0.1:9222");
    webview.put("color", "#FF4500");
    profiles.put("webview", webview);
    browser.put("profiles", profiles);
}
```

**效果**: 新用户安装后，首次设置 API Key 时自动配置好 browser，无需手动编辑配置文件。

## OpenClaw 配置

在 `~/.openclaw/openclaw.json` 中添加 browser 配置：

```json
{
  "browser": {
    "enabled": true,
    "defaultProfile": "webview",
    "profiles": {
      "webview": {
        "cdpUrl": "http://127.0.0.1:9222",
        "color": "#FF4500"
      }
    }
  }
}
```

## 使用方式

### 1. 启动 Gateway

在 BotDrop App 中点击 **Start Gateway**，WebView Bridge 会自动启动。

### 2. 验证服务

```bash
# 测试 HTTP 端点
curl http://127.0.0.1:9222/json/version

# 预期输出
{"Browser":"BotDrop WebView/120.0.0.0","Protocol-Version":"1.3",...}
```

### 3. 命令行使用

```bash
# 查看浏览器状态
termux-chroot openclaw browser --browser-profile webview status

# 打开网页
termux-chroot openclaw browser --browser-profile webview open https://github.com

# 获取页面快照
termux-chroot openclaw browser --browser-profile webview snapshot
```

### 4. 飞书对话使用

直接在飞书中与 BotDrop 对话：
```
User: 帮我查一下 https://github.com 的首页
Bot: [自动使用 browser tool 打开网页并返回截图和文字摘要]
```

## 已实现的 CDP 方法

| 方法 | 描述 | 状态 |
|------|------|------|
| `Browser.getVersion` | 获取浏览器版本 | ✅ |
| `Target.getTargets` | 获取目标列表 | ✅ |
| `Target.attachToTarget` | 附加到目标 | ✅ |
| `Page.navigate` | 页面导航 (open) | ✅ |
| `Page.enable` | 启用页面事件 | ✅ |
| `Page.captureScreenshot` | 截图 (snapshot) | ✅ |
| `Runtime.evaluate` | 执行 JavaScript | ✅ |
| `Runtime.enable` | 启用运行时事件 | ✅ |
| `Page.loadEventFired` | 页面加载完成事件 | ✅ |
| `Input.click` | 点击元素 (click) | ✅ |
| `Input.insertText` | 输入文本 (type) | ✅ |

### Click 实现

支持两种方式：
- **坐标点击**: `{"x": 100, "y": 200}`
- **选择器点击**: `{"selector": "#submit-button"}`

使用 JavaScript 实现：
```javascript
// 选择器点击
document.querySelector(selector).click();

// 坐标点击
var el = document.elementFromPoint(x, y);
el.dispatchEvent(new MouseEvent('click', { bubbles: true }));
```

### Type 实现

支持三种方式：
- **选择器输入**: `{"selector": "#username", "text": "hello"}`
- **坐标输入**: `{"x": 100, "y": 200, "text": "hello"}`
- **自动查找**: `{"text": "hello"}` (查找 focused 元素或第一个 input)

使用 JavaScript 实现：
```javascript
el.value = text;
el.dispatchEvent(new Event('input', { bubbles: true }));
el.dispatchEvent(new Event('change', { bubbles: true }));
```

## Git 改动摘要

```bash
$ git status

Changes not staged for commit:
  modified:   app/build.gradle
  modified:   app/src/main/AndroidManifest.xml
  modified:   app/src/main/java/app/botdrop/BotDropConfig.java
  modified:   app/src/main/java/app/botdrop/GatewayMonitorService.java

Untracked files:
  app/src/main/java/app/botdrop/WebViewBridgeService.java
  docs/browser_cdp_bridge.md
```

**已修改**:
| 文件 | 说明 |
|------|------|
| `app/build.gradle` | 添加 `org.java-websocket:Java-WebSocket:1.5.6` 依赖 |
| `app/src/main/AndroidManifest.xml` | 注册 `WebViewBridgeService` 前台服务 |
| `app/src/main/java/app/botdrop/BotDropConfig.java` | 用户首次配置时自动写入 browser 配置 |
| `app/src/main/java/app/botdrop/GatewayMonitorService.java` | Gateway 启动时自动启动 WebView Bridge |

**新增**:
| 文件 | 说明 |
|------|------|
| `app/src/main/java/app/botdrop/WebViewBridgeService.java` | CDP Bridge 服务实现（HTTP 9222 + WebSocket 9223 + Hidden WebView） |
| `docs/browser_cdp_bridge.md` | 技术文档 |

## 注意事项

1. **WebView 限制**: 使用系统 WebView，某些高级功能（如多标签页）可能受限
2. **内存管理**: WebView 在后台运行，系统可能在内存不足时回收
3. **网络权限**: WebView 使用应用的网络权限，无需额外配置
4. **CDP 兼容性**: 实现了核心 CDP 方法，但并非完整的 Chrome DevTools Protocol

## 故障排除

### 检查服务状态
```bash
# 检查端口是否监听
netstat -tln | grep -E "9222|9223"

# 检查日志
logcat -s WebViewBridge:D
```

### 重启服务
```bash
# 在 App 中停止并重新启动 Gateway
# 或强制停止应用后重新打开
```

## 参考

- [Chrome DevTools Protocol](https://chromedevtools.github.io/devtools-protocol/)
- [Android WebView](https://developer.android.com/reference/android/webkit/WebView)
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket)
