# BotDrop SMS 功能实现

## 概述

本项目已实现 Android 短信读取功能，让运行在移动设备上的 OpenClaw AI 可以读取手机短信，执行短信监控任务。

## 实现的功能

1. **读取最新短信** - 获取最近收到的一条短信
2. **读取多条短信** - 获取最近 N 条短信
3. **读取未读短信** - 获取所有未读短信
4. **权限检查** - 检查 SMS 权限状态

## 核心组件

### 1. SmsReader.java
- 位置: `app/src/main/java/app/botdrop/SmsReader.java`
- 功能: 通过 Android ContentResolver 读取系统 SMS 数据库
- 方法:
  - `getLatestSms()` - 获取最新短信
  - `getRecentSms(limit)` - 获取最近 N 条
  - `getUnreadSms()` - 获取未读短信
  - `hasSmsPermission()` - 检查权限

### 2. SmsCommandHandler.java
- 位置: `app/src/main/java/app/botdrop/SmsCommandHandler.java`
- 功能: 通过文件 IPC 机制监听 OpenClaw 的短信读取请求
- 通信协议:
  - 请求文件: `~/.openclaw/sms_request.json`
  - 响应文件: `~/.openclaw/sms_response.json`
  - 轮询间隔: 1 秒

### 3. botdrop-sms 脚本
- 位置: `app/src/main/assets/botdrop-sms`
- 功能: 提供命令行接口供 OpenClaw 调用
- 命令:
  - `botdrop-sms latest` - 最新短信
  - `botdrop-sms recent [n]` - 最近 n 条 (默认 5)
  - `botdrop-sms unread` - 未读短信
  - `botdrop-sms check` - 检查权限

### 4. DashboardActivity 集成
- 在启动时请求 SMS 权限 (`READ_SMS`, `RECEIVE_SMS`)
- 启动 SmsCommandHandler 监听请求
- 在 Activity 销毁时清理资源

### 5. BotDropService 集成
- 在绑定服务时安装 botdrop-sms 脚本到 `$PREFIX/bin/`

## 权限声明

在 `AndroidManifest.xml` 中添加了:
```xml
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
```

## 使用流程

### 1. 首次使用
1. 打开 BotDrop App
2. 进入 Dashboard 页面
3. 授予 SMS 权限（会自动弹出请求）
4. 等待 SMS Command Handler 启动

### 2. 命令行测试
在 Termux 终端中执行:
```bash
# 检查权限
botdrop-sms check

# 获取最新短信
botdrop-sms latest

# 获取最近 10 条
botdrop-sms recent 10

# 获取未读短信
botdrop-sms unread
```

### 3. OpenClaw 集成
在 OpenClaw agent 配置中添加 tool:

```json
{
  "tools": {
    "sms": {
      "type": "exec",
      "command": "botdrop-sms",
      "description": "Read SMS messages from device"
    }
  }
}
```

然后在 system prompt 中指导 AI 使用这个 tool。

## 响应格式

### 单条短信
```json
{
  "id": "12345",
  "sender": "+8613800138000",
  "body": "您的验证码是 123456",
  "timestamp": 1707504000000,
  "date": "2026-02-09 18:00:00",
  "read": false,
  "type": "inbox"
}
```

### 多条短信
```json
{
  "count": 3,
  "messages": [...]
}
```

### 错误
```json
{"error": "SMS permission not granted"}
```

## 文档

- 详细配置指南: `docs/sms-tool-setup.md`
- OpenClaw tool 配置: `openclaw-tools/sms-reader.json`
- Agent 配置示例: `openclaw-tools/example-agent-config.md`

## 安全注意事项

1. SMS 数据仅在本地处理，不会上传
2. 需要用户明确授予权限
3. 建议仅在可信的 AI provider 上启用
4. 可以随时在系统设置中撤销权限

## 故障排除

| 问题 | 原因 | 解决 |
|-----|------|-----|
| Timeout | BotDrop 未运行 | 打开 Dashboard 页面 |
| No permission | 权限未授予 | 在 Dashboard 授予权限 |
| Command not found | 脚本未安装 | 重新绑定 BotDropService |

## 未来扩展

- [ ] 实时新短信通知 (广播接收器)
- [ ] 发送短信功能
- [ ] 删除/标记已读
- [ ] 联系人名称解析
