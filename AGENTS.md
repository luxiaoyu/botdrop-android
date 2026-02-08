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
│   │   │   ├── SetupActivity.java             # 4-step setup wizard
│   │   │   ├── DashboardActivity.java         # Status dashboard
│   │   │   ├── BotDropService.java            # Background command execution
│   │   │   ├── GatewayMonitorService.java     # Foreground service for gateway
│   │   │   ├── BotDropConfig.java             # Config file I/O
│   │   │   ├── InstallFragment.java           # Step 1: Auto-install
│   │   │   ├── AgentSelectionFragment.java    # Step 2: Agent selection
│   │   │   ├── AuthFragment.java              # Step 2: API key auth
│   │   │   ├── ChannelFragment.java           # Step 3: Channel setup
│   │   │   ├── ChannelSetupHelper.java        # Setup code parsing
│   │   │   ├── ProviderInfo.java              # AI provider metadata
│   │   │   ├── UpdateChecker.java             # App update checking
│   │   │   └── PlaceholderFragment.java       # Empty placeholder
│   │   └── com/termux/           # Termux core (terminal, services)
│   ├── src/test/java/            # Unit tests
│   └── src/main/cpp/             # Native code & bootstrap
│       ├── Android.mk            # NDK build script
│       ├── termux-bootstrap.c    # Bootstrap loader
│       ├── termux-bootstrap-zip.S
│       └── bootstrap-aarch64.zip # Linux environment packages
├── termux-shared/                # Shared library (published to JitPack)
├── terminal-emulator/            # Terminal emulation engine (JNI/C)
├── terminal-view/                # Terminal UI component
├── docs/                         # Design documentation
│   └── design.md                 # Architecture design (Chinese)
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
- Android SDK (API level 36)
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
- Size: ~130MB
- Contains: Node.js, npm, bash, coreutils, termux-chroot (proot), OpenSSH

## Key Components

### 1. BotDropLauncherActivity
Entry point that handles:
- Guided permission requests (notifications, battery optimization)
- Two-phase startup: Welcome (permissions) → Loading (routing)
- State-based routing: checks bootstrap → OpenClaw config → OpenClaw install → channel config → dashboard

### 2. SetupActivity
4-step wizard (ViewPager2):
- Step 0: API Key (AuthFragment) - AI provider selection + API key input
- Step 1: Agent Selection (AgentSelectionFragment) - Choose agent to install
- Step 2: Install (InstallFragment) - Auto-install OpenClaw with progress
- Step 3: Channel (ChannelFragment) - Telegram/Discord/Feishu setup

### 3. DashboardActivity
Main status screen showing:
- Gateway status (running/stopped) with indicator
- Uptime display
- Connected channels (Telegram, Discord, Feishu)
- Control buttons (Start/Stop/Restart)
- SSH connection info
- Update notifications
- Link to Terminal (advanced users)

### 4. BotDropService
Background service for:
- Executing shell commands in Termux environment
- OpenClaw installation (via install.sh script)
- Gateway lifecycle management (start/stop/restart/status)
- Command execution with 60s timeout (300s for install)

### 5. GatewayMonitorService
Foreground service for keeping the OpenClaw gateway alive:
- Persistent notification with status
- Auto-restart on failure (max 5 attempts)
- Partial wake lock for Doze mode handling
- 30-second monitoring interval

### 6. BotDropConfig
Configuration management:
- Reads/writes `~/.openclaw/openclaw.json`
- Manages auth profiles in `~/.openclaw/agents/main/agent/auth-profiles.json`
- Thread-safe file operations with synchronized locking (`CONFIG_LOCK`)
- Sets restrictive file permissions (owner-only access for API keys)

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
- **Framework**: JUnit 4 + Robolectric
- **Location**: `app/src/test/java/`
- **Run**: `./gradlew :app:testDebugUnitTest`

### Key Test Files
- `BotDropConfigTest.java`: Config I/O operations, JSON structure validation
- `BotDropServiceTest.java`: Service lifecycle and command execution
- `ChannelSetupHelperTest.java`: Setup code parsing logic

### Testing Notes
- Tests use Robolectric for Android framework mocking
- Some tests cannot fully test file I/O due to hardcoded Termux paths (`/data/data/app.botdrop/files/home/`)
- Tests verify graceful error handling when paths don't exist
- Integration tests require actual device/emulator with Termux filesystem

### Test Categories
1. **Unit tests**: Data structures, static utilities, JSON validation
2. **Integration tests**: Command execution, file I/O, gateway control (require device)

## Code Style Guidelines

- Follow standard Android Java conventions
- Use meaningful variable and method names
- Keep methods focused and small
- Add comments only where logic isn't self-evident
- Use `final` keyword where appropriate
- Synchronize access to shared resources (see `BotDropConfig.CONFIG_LOCK`)
- Use try-with-resources for file operations to prevent resource leaks

## Security Considerations

### API Key Storage
- API keys stored in `auth-profiles.json` with owner-only permissions
- `configFile.setReadable(false, false)` / `setReadable(true, true)`
- Config directory: `~/.openclaw/` (private to app)
- Never log API keys or sensitive credentials

### Sensitive Permissions
The app requires several sensitive permissions:
- `FOREGROUND_SERVICE_SPECIAL_USE`: Keep gateway running
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Prevent system from killing service
- `MANAGE_EXTERNAL_STORAGE`: Access files for AI workspace
- `SYSTEM_ALERT_WINDOW`: Display over other apps (optional)
- `INTERNET`, `ACCESS_NETWORK_STATE`: Network access
- `WAKE_LOCK`: Keep CPU awake for gateway

### Security Policy
- Report vulnerabilities privately (do not open public issues)
- See `SECURITY.md` for contact information

## Runtime Architecture

### Bootstrap Installation
On first launch, `TermuxInstaller` extracts the bootstrap archive:
- Contains: Node.js, npm, bash, coreutils, termux-chroot (proot), OpenSSH
- Location: `/data/data/app.botdrop/files/usr/`
- Install script: `PREFIX/share/botdrop/install.sh`

### Command Execution
Commands are executed via `ProcessBuilder` with Termux environment:
```java
pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
```

### termux-chroot Usage
OpenClaw commands run under `termux-chroot` (proot) because:
- Android kernel blocks `os.networkInterfaces()` which OpenClaw needs
- proot provides a virtual chroot environment that bypasses this limitation

### Gateway Process Management
- PID file: `~/.openclaw/gateway.pid`
- Log file: `~/.openclaw/gateway.log`
- Debug log: `~/.openclaw/gateway-debug.log`
- SSH server runs on port 8022 for remote access

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

### Gateway Not Starting
- Check `~/.openclaw/gateway-debug.log` for shell trace
- Verify termux-chroot is available
- Check that openclaw binary exists and is executable

## Publishing

### JitPack Configuration
- `jitpack.yml` specifies JDK 17 and NDK r29
- Libraries published: termux-shared, terminal-emulator, terminal-view
- Version: 0.118.0

### Version Format
- Follows Semantic Versioning 2.0.0
- Format: `major.minor.patch(-prerelease)(+buildmetadata)`
- Validated in `app/build.gradle` `validateVersionName()`

## License

GPLv3 - See `LICENSE` file. Built on Termux (also GPLv3).

## References

- [Termux GitHub](https://github.com/termux/termux-app)
- [OpenClaw GitHub](https://github.com/nicepkg/openclaw)
- Design docs: `docs/design.md` (Chinese)
