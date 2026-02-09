# SMS 功能快速上手指南

## 1. 编译并安装应用

```bash
./gradlew installDebug
```

## 2. 首次启动

1. 打开 BotDrop 应用
2. 完成向导设置（如果未完成）
3. 进入 Dashboard 页面
4. 授予 SMS 权限（会弹出请求对话框）

## 3. 验证安装

在 Dashboard 页面等待 5 秒后，打开 Termux 终端：

```bash
# 检查脚本是否安装
which botdrop-sms

# 检查权限
botdrop-sms check

# 测试读取最新短信
botdrop-sms latest
```

## 4. OpenClaw 配置

编辑 `~/.openclaw/agents/main/agent/config.json`:

```json
{
  "tools": {
    "sms": {
      "type": "exec",
      "command": "botdrop-sms",
      "description": "Read SMS from device"
    }
  },
  "systemPrompt": {
    "base": "You can read SMS using the 'sms' tool with actions: latest, recent [n], unread"
  }
}
```

重启 OpenClaw gateway。

## 5. 测试 AI 读取短信

在飞书/ Telegram / Discord 中给你的 bot 发送：

```
帮我看看最新的一条短信
```

AI 应该能够调用 tool 并返回短信内容。

## 常见问题

**Q: 提示 "Timeout waiting for response"**
A: 确保 Dashboard 页面已打开（可以后台运行），SMS Command Handler 在这里启动。

**Q: 提示 "SMS permission not granted"**
A: 在 Dashboard 页面会请求权限，也可以去系统设置 → 应用 → BotDrop → 权限 → 短信 → 允许。

**Q: botdrop-sms 命令找不到**
A: 关闭 Dashboard 重新打开，脚本会在 Service 绑定时安装。

**Q: 返回空结果**
A: 确保设备上有短信，且 BotDrop 有权限读取。
