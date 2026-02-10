# OpenClaw Agent 配置示例 - 短信功能

这个示例展示如何在你的 OpenClaw agent 中集成 BotDrop 的短信读取功能。

## 基础配置

### 1. 直接在 agent config.json 中添加 tool

编辑 `~/.openclaw/agents/main/agent/config.json`:

```json
{
  "name": "main",
  "model": "anthropic/claude-sonnet-4-5",
  
  "systemPrompt": {
    "base": "You are a helpful AI assistant running on the user's Android device through BotDrop. You can help the user with various tasks including reading and searching their SMS messages. Always be respectful of the user's privacy."
  },
  
  "tools": {
    "sms": {
      "type": "exec",
      "command": "botdrop-sms",
      "description": "Read and search SMS messages from the Android device. Supports filtering by keyword.",
      "parameters": {
        "action": {
          "type": "string",
          "enum": ["latest", "recent", "unread", "check", "search"],
          "required": true
        },
        "limit": {
          "type": "number",
          "description": "Number of messages for 'recent' or 'search' action"
        },
        "keyword": {
          "type": "string",
          "description": "Search keyword for filtering messages (used with 'search' or 'recent' action)"
        }
      }
    }
  },
  
  "skills": []
}
```

### 2. 在 system prompt 中指导 AI 使用 SMS tool

```json
{
  "systemPrompt": {
    "base": "You are a helpful AI assistant running on the user's Android phone via BotDrop.\n\n## Available Tools\n\n### SMS Reader (tool: sms)\nYou can read SMS messages from the user's device using the 'sms' tool.\n\nAvailable actions:\n- latest: Get the most recent SMS message\n- recent: Get multiple recent messages (use 'limit' parameter, default 5, max 50)\n- unread: Get all unread messages\n- check: Check if SMS permission is granted\n\n## Guidelines\n\n1. Only read SMS when the user explicitly asks about messages, codes, or texts\n2. Present SMS information in a clear, formatted way\n3. Highlight important info like verification codes, bank alerts, etc.\n4. If no permission, guide user: 'Please open BotDrop app and grant SMS permission in Dashboard'\n5. Respect privacy - never summarize message content without user request\n\n## SMS Response Format\n\nWhen showing SMS to user:\n- Format: [Time] From: Sender | Message: Content\n- For verification codes, bold the code: **123456**\n- Group related messages (same sender) together"
  }
}
```

## 进阶配置

### 使用条件 tool 调用

配置 AI 在特定场景下自动读取 SMS：

```json
{
  "tools": {
    "sms": {
      "type": "exec",
      "command": "botdrop-sms",
      "description": "Read and search SMS messages. Use when: user asks about verification codes, messages, texts, OTP, SMS notifications, or wants to search SMS by keyword",
      "autoUse": {
        "patterns": [
          "verification code",
          "验证码",
          "OTP",
          "短信",
          "what.*text",
          "new message",
          "check.*sms",
          "search.*sms",
          "查找.*短信"
        ]
      }
    }
  }
}
```

### 创建专用的 SMS Agent

创建一个专门处理短信相关任务的 agent：

**~/.openclaw/agents/sms-helper/agent.json:**

```json
{
  "name": "sms-helper",
  "description": "SMS assistant - helps read and manage text messages",
  "model": "anthropic/claude-sonnet-4-5",
  
  "systemPrompt": {
    "base": "You are an SMS assistant. Your job is to help the user read and understand their text messages.\n\nWhen reading SMS:\n1. Always check permission first with 'check' action\n2. Use 'latest' for single recent message queries\n3. Use 'recent' with appropriate limit for multiple messages\n4. Use 'unread' when user asks about new/unread messages\n\nFormatting rules:\n- Show sender name/number prominently\n- Display time in local format\n- For verification codes: extract and highlight them\n- For bank messages: summarize transaction details\n- For spam: warn user if message looks suspicious"
  },
  
  "tools": {
    "sms": {
      "type": "exec",
      "command": "botdrop-sms"
    }
  },
  
  "handlers": {
    "verification_code": {
      "pattern": "(verification code|验证码|OTP|passcode)",
      "action": {
        "tool": "sms",
        "params": { "action": "latest" }
      },
      "response": "Extract the code from the latest SMS and present it clearly"
    }
  }
}
```

## 使用示例

### 示例 1：验证码查询

**用户**: "我收到验证码了吗？"

**AI 行为**:
1. 调用 `botdrop-sms latest`
2. 解析响应
3. 提取验证码（如果有）

**AI 回复**:
```
是的，您收到了一条包含验证码的短信：

**时间**: 2026-02-09 18:15:32
**来自**: +86 138-0013-8000
**验证码**: **568291**
**完整内容**: "【支付宝】验证码 568291，用于登录，5分钟内有效。请勿向他人透露。"

验证码将在 3 分钟后过期。
```

### 示例 2：银行通知

**用户**: "看看最近有什么银行短信"

**AI 行为**:
1. 调用 `botdrop-sms recent 10`
2. 筛选银行相关短信（根据发送者号码或关键词）
3. 按时间排序展示

**AI 回复**:
```
在最近的 10 条短信中，找到 3 条银行相关消息：

1. **工商银行** (95588) - 今天 14:30
   💳 信用卡账单 ¥1,234.56，到期日 2026-03-05

2. **建设银行** (95533) - 昨天 09:15
   💰 收入 +¥5,000.00，余额 ¥23,456.78

3. **招商银行** (95555) - 前天 16:45
   🔔 您的朝朝宝收益已到账 +¥12.34
```

### 示例 3：未读消息检查

**用户**: "有未读短信吗？"

**AI 行为**:
1. 调用 `botdrop-sms unread`
2. 统计未读数量
3. 展示未读消息摘要

**AI 回复**:
```
您有 **2 条未读短信**：

1. **10086** - 今天 17:20
   📱 您本月已使用流量 8.5GB，剩余 1.5GB

2. **顺丰速运** - 今天 16:45
   📦 您的快递已到达 [XX小区驿站]，取件码：8-2-9012
```

### 示例 4：关键字搜索

**用户**: "帮我找一下顺丰的快递短信"

**AI 行为**:
1. 调用 `botdrop-sms search 顺丰`
2. 筛选匹配的短信
3. 按时间排序展示

**AI 回复**:
```
找到 **3 条**包含"顺丰"的短信：

1. **顺丰速运** - 今天 16:45
   📦 您的快递已到达 [XX小区驿站]，取件码：8-2-9012

2. **顺丰速运** - 昨天 09:30
   📦 您的包裹 SF1234567890 已签收

3. **顺丰速运** - 2025-09-27 17:02
   💉 医院提醒：明天宝贝该打狂犬疫苗了
```

### 示例 5：搜索验证码

**用户**: "最近有没有收到验证码？"

**AI 行为**:
1. 调用 `botdrop-sms search 验证码`
2. 查找包含"验证码"的短信

**AI 回复**:
```
找到 **2 条**验证码短信：

1. **中信银行** (955581101) - 今天 12:17
   🔐 验证码：**649922**
   用途：向尾号4232账户转账 10000.00元

2. **中信银行** (955581101) - 2025-09-11 08:26
   🔐 验证码：**611165**
   用途：向尾号4232账户转账 12000.00元
```

## 故障排除指南

当 AI 遇到错误时，向用户提供清晰的指导：

### 权限错误

```
❌ 无法读取短信 - 权限未授予

请按以下步骤操作：
1. 打开 BotDrop 应用
2. 进入 Dashboard 页面
3. 点击 "Grant SMS Permission" 按钮
4. 或者在系统设置中找到 BotDrop → 权限 → 短信 → 允许
```

### 超时错误

```
❌ 请求超时

可能原因：
- BotDrop 应用未在运行
- SMS 服务未启动

解决方法：
1. 打开 BotDrop 应用
2. 确保 Dashboard 页面已加载
3. 重试您的请求
```

### 无短信数据

```
ℹ️ 未找到短信

可能原因：
- 设备上没有短信
- 使用的是不同的短信应用
- 短信被清理了

建议：检查系统默认短信应用中是否有消息。
```

## 安全最佳实践

1. **敏感信息处理**: 验证码、银行信息应该只向已验证的用户展示
2. **日志记录**: 考虑记录 SMS 读取操作到日志文件
3. **访问控制**: 在 channel 配置中使用 allowlist 限制谁能访问 SMS 功能
4. **自动清理**: 定期清理旧的 SMS 请求/响应文件

## 调试

启用调试模式查看详细日志：

```bash
# 在 Termux 中
export BOTDROP_SMS_DEBUG=1
botdrop-sms latest
```

查看 Android 日志：

```bash
# 通过 adb
adb logcat -s SmsReader:S SmsCommandHandler:S
```
