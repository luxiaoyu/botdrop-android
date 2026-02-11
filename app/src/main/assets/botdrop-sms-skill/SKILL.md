# BotDrop SMS Skill

Read and manage SMS messages from the Android device.

## Trigger Conditions

This skill is triggered when the user asks about SMS messages, including but not limited to:

- "帮我看看最新短信" / "Show me the latest SMS"
- "最近收到了什么短信" / "What messages have I received recently"
- "有没有验证码" / "Do I have any verification codes"
- "查一下未读短信" / "Check unread messages"
- "搜索来自10086的短信" / "Search messages from 10086"
- "短信统计" / "SMS statistics"
- Any query containing keywords: "短信", "SMS", "message", "验证码", "verification code", "inbox"

## Actions

### latest
Get the most recent SMS message.

```bash
botdrop-sms latest
```

### recent [n]
Get recent n SMS messages (default: 5).

```bash
botdrop-sms recent 10
```

### unread
Get all unread SMS messages.

```bash
botdrop-sms unread
```

### search <keyword> [n]
Search SMS by keyword.

```bash
botdrop-sms search "验证码" 5
```

### from <sender> [n]
Get messages from specific sender/number.

```bash
botdrop-sms from "10086" 10
```

### stats
Get SMS statistics.

```bash
botdrop-sms stats
```

### period <time> [n]
Get messages from a time period (1d, 7d, 30d).

```bash
botdrop-sms period 7d
```

### check
Check if SMS permission is granted.

```bash
botdrop-sms check
```

## Response Format

All responses are in JSON format:

```json
{
  "id": "12345",
  "sender": "+8613800138000",
  "body": "Your verification code is 123456.",
  "timestamp": 1707504000000,
  "date": "2026-02-09 18:00:00",
  "read": false,
  "type": "inbox"
  }
```

## Examples

**User**: "帮我看看最新短信"
**Action**: `botdrop-sms latest`

**User**: "最近有什么银行短信"
**Action**: `botdrop-sms search "银行" 10` or `botdrop-sms from "95588" 5`

**User**: "有没有未读消息"
**Action**: `botdrop-sms unread`

**User**: "本周的短信"
**Action**: `botdrop-sms period 7d`

## Error Handling

If the response contains an `error` field, explain the error to the user:
- "SMS permission not granted" → Ask user to grant SMS permission in BotDrop app
- "Timeout waiting for response" → BotDrop app may not be running
- "No SMS found" → No matching messages found

## Security Notes

- SMS data is processed locally on the device
- No SMS content is uploaded to external servers
- Requires explicit SMS permission granted by user
