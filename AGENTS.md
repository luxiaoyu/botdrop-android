# BotDrop Android Project

BotDrop is an Android application that runs AI agents (via OpenClaw) on mobile devices without requiring users to interact with a terminal. It wraps [OpenClaw](https://github.com/nicepkg/openclaw) into a user-friendly GUI, providing a Linux environment through Termux for running Node.js-based AI agents.

## Project Overview

**Goal**: Enable users to run AI agents on their Android phone with a guided 4-step setup (Auth → Agent → Install → Channel), no terminal or CLI required.

**Architecture Stack**:
```
┌──────────────────────────────────┐
│     BotDrop UI (app.botdrop)     │  ← Java/Kotlin Android UI
├──────────────────────────────────┤
│     Termux Core (com.termux)     │  ← Terminal emulator & Linux env
├──────────────────────────────────┤
│  Linux Environment (proot/apt)   │  ← Bootstrap packages
├──────────────────────────────────┤
│  OpenClaw + Node.js + npm        │  ← AI agent runtime
└──────────────────────────────────┘
```

**Core Philosophy**: App GUI only handles ignition; configuration is done by chatting with the AI agent itself.

## Technology Stack

- **Language**: Java (Android)
- **Build System**: Gradle with Android Gradle Plugin 8.13.2
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 28 (Android 9.0) - intentionally kept lower for broader compatibility
- **Compile SDK**: 36
- **NDK**: r29.0.14206865
- **JDK**: 17+
- **Architecture**: arm64-v8a only

### Key Dependencies
- AndroidX (Core, AppCompat, ViewPager2, Material Design)
- Markwon (Markdown rendering)
- Guava (Google core libraries)
- Robolectric (Unit testing)

## Project Structure

```
botdrop-android/
├── app/                          # Main Android application
│   ├── src/main/java/
│   │   ├── app/botdrop/          # BotDrop GUI components
│   │   │   ├── BotDropLauncherActivity.java   # Entry point & routing
│   │   │   ├── SetupActivity.java             # 3-step setup wizard
│   │   │   ├── DashboardActivity.java         # Status dashboard
│   │   │   ├── BotDropService.java            # Background command execution
│   │   │   ├── BotDropConfig.java             # Config file I/O
│   │   │   ├── InstallFragment.java           # Step 1: Auto-install
│   │   │   ├── AgentSelectionFragment.java    # Agent selection
│   │   │   ├── AuthFragment.java              # Step 2: API key auth
│   │   │   ├── ChannelFragment.java           # Step 3: Channel setup
│   │   │   ├── ChannelSetupHelper.java        # Setup code parsing
│   │   │   └── ProviderInfo.java              # AI provider metadata
│   │   └── com/termux/           # Termux core (terminal, services)
│   ├── src/test/java/            # Unit tests
│   └── src/main/cpp/             # Native code & bootstrap
│       └── bootstrap-aarch64.zip # Linux environment packages
├── termux-shared/                # Shared library (published to JitPack)
├── terminal-emulator/            # Terminal emulation engine (JNI/C)
├── terminal-view/                # Terminal UI component
├── docs/                         # Design documentation
├── art/                          # Assets & graphics
└── gradle/                       # Gradle wrapper
```

## Module Dependencies

```
app (application)
  ├─ depends on: terminal-view
  ├─ depends on: termux-shared
  └─ depends on: terminal-emulator (via terminal-view)

termux-shared (library, publishable)
  ├─ depends on: terminal-view
  └─ published to: JitPack (com.termux:termux-shared:0.118.0)

terminal-view (library, publishable)
  └─ depends on: terminal-emulator

terminal-emulator (library, publishable)
  └─ published to: JitPack (com.termux:terminal-emulator:0.118.0)
```

## Build Commands

### Prerequisites
- Android SDK (API level 34+)
- NDK r29+
- JDK 17+

### Development Build
```bash
# Debug build (universal APK)
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug

# Run tests
./gradlew :app:testDebugUnitTest

# Clean build
./gradlew clean
```

### Output Locations
- Debug APK: `app/build/outputs/apk/debug/botdrop-app_apt-android-7-debug_universal.apk`
- Split APKs: `app/build/outputs/apk/debug/` (per-architecture)

### Bootstrap Download
The build automatically downloads bootstrap packages from GitHub releases:
- Source: `https://github.com/louzhixian/botdrop-packages/releases/latest/download/bootstrap-aarch64.zip`
- Target: `app/src/main/cpp/bootstrap-aarch64.zip`

## Key Components

### 1. BotDropLauncherActivity
Entry point that handles:
- Guided permission requests (notifications, battery optimization)
- State-based routing: checks bootstrap → OpenClaw install → config → dashboard

### 2. SetupActivity
3-step wizard (ViewPager2):
- Step 1: Auto-install OpenClaw
- Step 2: AI provider selection + API key input
- Step 3: Channel setup (Telegram/Discord via @BotDropSetupBot)

### 3. BotDropService
Background service for:
- Executing shell commands in Termux environment
- OpenClaw installation (via install.sh script)
- Gateway lifecycle management (start/stop/restart/status)

### 4. BotDropConfig
Configuration management:
- Reads/writes `~/.openclaw/openclaw.json`
- Manages auth profiles in `~/.openclaw/agents/main/agent/auth-profiles.json`
- Thread-safe file operations with synchronized locking
- Sets restrictive file permissions (owner-only access for API keys)

### 5. GatewayMonitorService
Foreground service for keeping the OpenClaw gateway alive with auto-restart.

## Configuration Files

### OpenClaw Config (`~/.openclaw/openclaw.json`)
```json
{
  "agents": {
    "defaults": {
      "model": { "primary": "anthropic/claude-sonnet-4-5" },
      "workspace": "~/botdrop"
    }
  },
  "gateway": {
    "mode": "local",
    "auth": { "token": "uuid-generated" }
  },
  "channels": {
    "telegram": {
      "accounts": { "default": { "token": "bot_token" } },
      "bindings": [{ "account": "default", "agent": "main" }],
      "pairing": { "mode": "allowlist", "allowlist": ["user_id"] }
    }
  }
}
```

### Auth Profiles (`~/.openclaw/agents/main/agent/auth-profiles.json`)
```json
{
  "version": 1,
  "profiles": {
    "anthropic:default": {
      "type": "api_key",
      "provider": "anthropic",
      "key": "api_key_value"
    }
  }
}
```

## Testing Strategy

### Unit Tests
- Framework: JUnit 4 + Robolectric
- Location: `app/src/test/java/`
- Run: `./gradlew :app:testDebugUnitTest`

### Key Test Files
- `BotDropConfigTest.java`: Config I/O operations, JSON structure validation
- `BotDropServiceTest.java`: Service lifecycle and command execution
- `ChannelSetupHelperTest.java`: Setup code parsing logic

### Testing Notes
- Tests use Robolectric for Android framework mocking
- Some tests cannot fully test file I/O due to hardcoded Termux paths
- Tests verify graceful error handling when paths don't exist

## Code Style Guidelines

- Follow standard Android Java conventions
- Use meaningful variable and method names
- Keep methods focused and small
- Add comments only where logic isn't self-evident
- Use `final` keyword where appropriate
- Synchronize access to shared resources (see `BotDropConfig.CONFIG_LOCK`)

## Security Considerations

### API Key Storage
- API keys stored in `auth-profiles.json` with owner-only permissions
- `configFile.setReadable(false, false)` / `setReadable(true, true)`
- Config directory: `~/.openclaw/` (private to app)

### Sensitive Permissions
The app requires several sensitive permissions:
- `FOREGROUND_SERVICE_SPECIAL_USE`: Keep gateway running
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Prevent system from killing service
- `MANAGE_EXTERNAL_STORAGE`: Access files for AI workspace
- `SYSTEM_ALERT_WINDOW`: Display over other apps (optional)

### Security Policy
- Report vulnerabilities privately (do not open public issues)
- See `SECURITY.md` for contact information

## Runtime Architecture

### Bootstrap Installation
On first launch, `TermuxInstaller` extracts the bootstrap archive:
- Contains: Node.js, npm, bash, coreutils, termux-chroot (proot)
- Location: `/data/data/app.botdrop/files/usr/`
- Install script: `PREFIX/share/botdrop/install.sh`

### Command Execution
Commands are executed via `ProcessBuilder` with Termux environment:
```java
pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
```

### termux-chroot Usage
OpenClaw commands run under `termux-chroot` (proot) because:
- Android kernel blocks `os.networkInterfaces()` which OpenClaw needs
- proot provides a virtual chroot environment that bypasses this limitation

## Development Workflow

### Adding New AI Providers
1. Add provider metadata to `ProviderInfo.java`
2. Update auth handling in `AuthFragment.java`
3. Add provider-specific setup instructions

### Modifying Config Structure
1. Update `BotDropConfig.java` for read/write logic
2. Update `BotDropConfigTest.java` for validation
3. Ensure backward compatibility for existing users

### Working with Bootstrap
Bootstrap contains the Linux environment. To update:
1. Build new bootstrap packages (separate repo: botdrop-packages)
2. Update download URL in `app/build.gradle` `downloadBootstrap()`
3. Bump version and test fresh install

## Common Issues

### Bootstrap Download Failures
- Check network connectivity
- Verify GitHub releases URL is accessible
- Check disk space for ~130MB bootstrap file

### Command Execution Timeouts
- Default timeout: 60 seconds for commands, 5 minutes for installation
- Adjust in `BotDropService.executeCommandSync()` if needed

### Permission Denied on Config Files
- Ensure `mkdirs()` succeeded before writing
- Check `setReadable/setWritable` permissions are set correctly

## License

GPLv3 - See `LICENSE` file. Built on Termux (also GPLv3).

## References

- [Termux GitHub](https://github.com/termux/termux-app)
- [OpenClaw GitHub](https://github.com/nicepkg/openclaw)
- Design docs: `docs/design.md`
