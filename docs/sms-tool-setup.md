# BotDrop SMS Tool 配置指南

BotDrop 现在支持读取手机短信功能，让 AI 可以在你的移动 ClawBot 上执行短信监控任务。

## 功能特性

- **读取最新短信**: 获取最近收到的一条短信
- **读取多条短信**: 获取最近 N 条短信
- **读取未读短信**: 获取所有未读短信
- **权限检查**: 检查 SMS 权限状态

## 架构

```
OpenClaw (Node.js in Termux)
    ↓ 执行 botdrop-sms 命令
Shell Script (botdrop-sms)
    ↓ 写入请求文件
File-based IPC (sms_request.json)
    ↓ Android 系统
BotDrop App (SmsCommandHandler)
    ↓ ContentResolver
SMS Provider (Android System)
    ↓
返回 JSON 响应 (sms_response.json)
```

## 快速开始

### 1. 授予 SMS 权限

首次启动 DashboardActivity 时会自动请求 SMS 权限。你也可以在系统设置中手动授予：

```
设置 → 应用 → BotDrop → 权限 → 短信 → 允许
```

### 2. 验证 SMS 工具已安装

在 BotDrop 终端中运行：

```bash
botdrop-sms check
```

如果显示权限已授予，则可以开始使用。

### 3. 命令行使用

```bash
# 获取最新短信
botdrop-sms latest

# 获取最近 10 条短信
botdrop-sms recent 10

# 获取未读短信
botdrop-sms unread
```

## OpenClaw 集成

要让 AI 模型能够读取短信，需要在 OpenClaw 中添加一个 tool。

### 方法一：在 agent 配置中添加 tool (推荐)

编辑你的 OpenClaw agent 配置文件（通常位于 `~/.openclaw/agents/<agent>/agent/config.json`）：

```json
{
  "tools": {
    "sms_reader": {
      "type": "exec",
      "command": "botdrop-sms",
      "description": "Read SMS messages from the Android device",
      "parameters": {
        "action": {
          "type": "string",
          "enum": ["latest", "recent", "unread"],
          "description": "The action to perform"
        },
        "limit": {
          "type": "number",
          "description": "Number of messages to retrieve (for 'recent' action)",
          "default": 5
        }
      }
    }
  }
}
```

然后在你的 agent system prompt 中添加：

```
You can read SMS messages from the user's device using the sms_reader tool.
Available actions:
- latest: Get the most recent SMS
- recent: Get recent SMS (specify count with limit parameter)
- unread: Get all unread SMS

When the user asks about SMS or messages, use the appropriate action to retrieve the information.
```

### 方法二：使用 OpenClaw 的 exec tool

如果你的 OpenClaw 配置允许使用 exec tool，可以直接执行：

```json
{
  "tools": {
    "exec": {
      "enabled": true,
      "allowed_commands": ["botdrop-sms"]
    }
  }
}
```

然后在对话中使用：

```
用户: 我收到了什么短信？
AI: <调用 exec("botdrop-sms latest")>
```

### 方法三：创建 custom skill

在 OpenClaw 中创建一个自定义 skill：

**~/.openclaw/agents/<agent>/skills/sms-reader/index.js:**

```javascript
module.exports = {
  name: 'sms-reader',
  description: 'Read SMS messages from Android device',
  
  async latest() {
    const { execSync } = require('child_process');
    try {
      const output = execSync('botdrop-sms latest', { encoding: 'utf8' });
      return JSON.parse(output);
    } catch (e) {
      return { error: e.message };
    }
  },
  
  async recent(limit = 5) {
    const { execSync } = require('child_process');
    try {
      const output = execSync(`botdrop-sms recent ${limit}`, { encoding: 'utf8' });
      return JSON.parse(output);
    } catch (e) {
      return { error: e.message };
    }
  },
  
  async unread() {
    const { execSync } = require('child_process');
    try {
      const output = execSync('botdrop-sms unread', { encoding: 'utf8' });
      return JSON.parse(output);
    } catch (e) {
      return { error: e.message };
    }
  }
};
```

## 响应格式

### 最新短信 (latest)

```json
{
  "id": "12345",
  "sender": "+8613800138000",
  "body": "您的验证码是 123456，5分钟内有效。",
  "timestamp": 1707504000000,
  "date": "2026-02-09 18:00:00",
  "read": false,
  "type": "inbox"
}
```

### 多条短信 (recent)

```json
{
  "count": 3,
  "messages": [
    {
      "id": "12345",
      "sender": "+8613800138000",
      "body": "最新消息内容",
      "timestamp": 1707504000000,
      "date": "2026-02-09 18:00:00",
      "read": true,
      "type": "inbox"
    },
    ...
  ]
}
```

### 错误响应

```json
{
  "error": "No SMS found or permission denied"
}
```

或

```json
{
  "error": "Timeout waiting for response. Is BotDrop running?"
}
```

## 使用场景示例

### 场景 1：验证码获取

```
用户: 帮我看看刚收到的验证码
AI: <调用 botdrop-sms latest>
AI: 您最新的短信来自 +8613800138000，内容是："您的验证码是 123456，5分钟内有效。"
```

### 场景 2：短信监控

```
用户: 检查是否有未读短信
AI: <调用 botdrop-sms unread>
AI: 您有 2 条未读短信：
   1. 来自 10086: "您的流量已使用 80%"
   2. 来自 95588: "您的信用卡账单已出"
```

### 场景 3：银行通知查询

```
用户: 最近有什么银行短信吗？
AI: <调用 botdrop-sms recent 10>
AI: 在最近的 10 条短信中，找到以下银行相关消息：
   - 来自 95588: 信用卡账单通知
   - 来自 95533: 转账到账通知
```

## 故障排除

### 问题 1: "Timeout waiting for response"

**原因**: BotDrop App 未运行或 SMS Command Handler 未启动

**解决**: 
1. 确保 BotDrop App 正在前台或后台运行
2. 检查 DashboardActivity 是否已启动（SMS Handler 在这里初始化）

### 问题 2: "SMS permission not granted"

**原因**: 应用没有 SMS 读取权限

**解决**:
1. 打开 BotDrop App
2. 进入 Dashboard
3. 授予 SMS 权限
4. 或者在系统设置中手动授予

### 问题 3: 返回空结果

**原因**: 
1. 设备没有短信
2. 短信数据库为空
3. 应用未被设为默认短信应用（某些 Android 版本）

**解决**:
- 检查设备上是否有短信
- 确保 BotDrop 使用的是设备的短信应用数据

### 问题 4: 脚本未找到 (command not found)

**原因**: botdrop-sms 脚本未正确安装

**解决**:
```bash
# 手动复制脚本
cp /data/data/app.botdrop/files/usr/share/botdrop/botdrop-sms $PREFIX/bin/
chmod +x $PREFIX/bin/botdrop-sms
```

## 隐私和安全

- SMS 数据**仅在本地处理**，不会上传到任何服务器
- OpenClaw 只能通过你配置的 channel (Telegram/Discord/飞书) 访问 SMS 数据
- 建议仅在可信的 AI provider 上启用此功能
- 可以在任何时候撤销 SMS 权限

## 技术细节

### 文件位置

- **请求文件**: `/data/data/app.botdrop/files/home/.openclaw/sms_request.json`
- **响应文件**: `/data/data/app.botdrop/files/home/.openclaw/sms_response.json`
- **脚本位置**: `/data/data/app.botdrop/files/usr/bin/botdrop-sms`

### 权限要求

- `android.permission.READ_SMS` - 读取短信内容
- `android.permission.RECEIVE_SMS` - 接收新短信通知（用于未来功能）

### Android 版本兼容性

- **Android 7.0+ (API 24+)**: 完全支持
- **Android 10+ (API 29+)**: 需要用户手动授予 SMS 权限
- **Android 13+ (API 33+)**: 需要运行时权限请求

## 未来扩展

计划中的功能：
- [ ] 实时短信通知（新短信到达时推送到频道）
- [ ] 短信发送功能（通过 AI 发送短信）
- [ ] 短信删除/标记已读
- [ ] 短信搜索/过滤
- [ ] 联系人名称解析（将号码映射到联系人名称）
