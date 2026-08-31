# Lucid Dream

An experimental self-hosted Android AI agent powered by DeepSeek tool calling, local safety policy, and a vision-based Android executor.

Lucid Dream connects an Android owner chat, local policy enforcement, and a Termux-hosted vision agent. It is a source-first research workflow for developers who are comfortable configuring Android system tools. It is **not** a zero-configuration consumer app, production-ready automation platform, or guarantee that every Android interface can be operated reliably.

## Overview

```text
Owner
  ↓
Lucid Dream Android app
  ↓
DeepSeek chat + official tool calling
  ↓
Local RiskClassifier + ToolPolicyEngine
  ↓
Localhost Agent Server (Termux)
  ↓
GLM vision + Mobilerun Portal + Shizuku/rish
  ↓
Android UI
  ↓
Tool result → DeepSeek final response
```

DeepSeek can propose an Android action, but it cannot grant itself permission. The Android app assigns the request source, classifies risk locally, applies policy, and requires confirmation where appropriate. The Python executor then reasons from screenshots and Android foreground state, performs one bounded UI action at a time, and reports a terminal result.

This repository combines the Android application with a v1.0 source snapshot of the Python runtime used by the local Agent Server.

## v1.0 features

- Owner Chat backed by DeepSeek with conversation context.
- DeepSeek official tool calling for owner-requested Android tasks.
- Deterministic local Android risk classification and fail-closed tool policy.
- Explicit confirmation for medium- and high-risk actions.
- Authentication and credential operations denied to automation.
- Vision-based Android execution with bounded steps and retries.
- Dynamic installed-app and launcher resolution instead of a fixed package allowlist.
- Generic UI navigation, tapping, swiping, foreground verification, and guarded text input.
- Tool-result continuation back to DeepSeek after Android execution.
- Cross-app task lifecycle feedback through Android Toasts.
- Independent **Mobile Agent** entry for direct testing and recovery.
- WeChat notification delegation with session-based reply limits.
- Inspector, delegation history, settings, and encrypted credential storage.

## Security model

Policy is enforced locally by Kotlin code:

| Source / risk | Decision |
| --- | --- |
| `OWNER_CHAT + LOW` | `ALLOW` |
| `OWNER_CHAT + MEDIUM/HIGH` | `REQUIRE_CONFIRMATION` |
| `AUTHENTICATION` | `DENY` |
| `EXTERNAL_UNTRUSTED` | `DENY` |
| Unknown source, tool, action, or risk | `DENY` |

DeepSeek does **not** choose or override the trusted source, risk category, authorization, or policy decision. Model-provided authorization-like fields are not trusted.

Passwords, OTPs, PINs, payment credentials, fingerprints, face recognition, new-device verification, and other identity checks remain user-only actions. The Python vision agent also has an authentication-page guard and stops for user handoff.

The Agent Server binds only to `127.0.0.1:8765` and authenticates protected endpoints with a user-generated local bearer secret. External WeChat content is classified as `EXTERNAL_UNTRUSTED` and cannot invoke the owner's Android Agent tool.

## Components and dependencies

### Build-time

- JDK 17.
- Android SDK Platform 36 and compatible Android build tools.
- The included Gradle 8.13 wrapper.

### Runtime

- An Android device (the project currently targets Android 8.0 / API 26 and later).
- The Lucid Dream Android app.
- Termux with Python 3.
- Shizuku and a working `rish` integration for Termux.
- Mobilerun Portal with its local REST service and keyboard/input integration enabled.
- DeepSeek API access for the Android app.
- Zhipu/GLM API access for vision inference (`glm-4.6v` in the current runtime).

The Python files in `agent/` use the Python standard library and do not require a Python package installation step. Shizuku, rish, Mobilerun Portal, DeepSeek, and Zhipu are separate products/services and are not bundled in this repository.

## Credentials

No credentials are included. Each user must obtain or generate their own and keep them local.

| Credential | Purpose | Where it is configured |
| --- | --- | --- |
| DeepSeek API Key | Owner Chat and delegation decisions | Lucid Dream **Settings**; encrypted with Android Keystore + AES-GCM |
| `ZHIPU_API_KEY` | GLM vision inference | Termux environment |
| `MOBILERUN_TOKEN` | Mobilerun Portal REST/device control | Termux environment |
| `LUCID_AGENT_TOKEN` | Local bearer authentication between Lucid Dream and the Termux Agent Server | Same value in the Termux environment and Lucid Dream **Settings → Android Agent Token** |

`LUCID_AGENT_TOKEN` is not a third-party API key. Generate it locally, for example:

```sh
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

`agent_server.py` reads the value directly from the `LUCID_AGENT_TOKEN` environment variable when it starts. It does not automatically load an env file. The example below explicitly sources a private local file before starting the server.

## Quick start

1. **Clone the repository.**

   ```sh
   git clone https://github.com/hyy070518-code/Lucid-Dream.git
   cd Lucid-Dream
   ```

2. **Install JDK 17 and Android SDK Platform 36.** Create a local, untracked `local.properties` pointing to your SDK, for example `sdk.dir=C:\\path\\to\\Android\\Sdk` on Windows.

3. **Build the Android app.**

   ```powershell
   .\gradlew.bat assembleDebug
   ```

4. **Install the debug APK** from `app/build/outputs/apk/debug/app-debug.apk` on your development device. The APK is a local build artifact and is not committed to this repository.

5. **Install and configure Shizuku.** Start Shizuku using its supported device method and authorize the Termux/rish integration. Do not weaken Android authentication or device security to do this.

6. **Install and configure Mobilerun Portal.** Enable its local REST service at `http://127.0.0.1:8080`, configure a private Portal token, and enable the input method/keyboard integration required by your Portal build.

7. **Install Termux and copy the runtime.** From a clone accessible to Termux, copy the three runtime/test files into a private Termux directory:

   ```sh
   mkdir -p ~/lucid-agent
   cp agent/mr_agent.py agent/agent_server.py agent/test_mr_agent_app_launch.py ~/lucid-agent/
   cp agent/env.example ~/lucid-agent/env
   ```

8. **Configure rish.** The current executor expects the executable at `~/shizuku/rish`. Follow the Shizuku/rish instructions for your environment and verify Termux has the required Shizuku permission.

9. **Configure local Agent credentials.** Edit `~/lucid-agent/env`, replace every placeholder with your own value, restrict its permissions, and source it:

   ```sh
   chmod 600 ~/lucid-agent/env
   . ~/lucid-agent/env
   ```

10. **Start the Agent Server.**

    ```sh
    cd ~/lucid-agent
    python agent_server.py
    ```

    It listens on `http://127.0.0.1:8765`. `GET /health` is unauthenticated; task endpoints require the local bearer token.

11. **Configure Lucid Dream.** In **Settings**, enter your DeepSeek Base URL, model, and API key. Enter the exact same `LUCID_AGENT_TOKEN` value under **Android Agent Token**. Use the connection checks to verify both the Agent Server and Mobilerun Portal.

12. **Run a low-risk first task.** Use the independent **Mobile Agent** entry or ask Owner Chat to open a harmless app or settings page. Keep the device unlocked and observe the first runs closely.

## Termux operation notes

- Run `agent_server.py` from the same directory as `mr_agent.py` so the server can import the executor.
- The server is intentionally localhost-only and supports a single active Android UI task.
- `mr_agent.py` reads `ZHIPU_API_KEY` and `MOBILERUN_TOKEN` at the start of each task.
- The current runtime sets `RISH_APPLICATION_ID=com.termux` for rish subprocesses.
- Mobilerun Portal is expected at `http://127.0.0.1:8080`; the Agent Server is expected at `http://127.0.0.1:8765`.
- If Termux or Mobilerun is stopped by Android/OEM background management, restart it manually and repeat the health checks.

## WeChat delegation

Lucid Dream can monitor WeChat notifications while a user-controlled delegation session is active, ask DeepSeek for a bounded decision, and attempt a reply through notification `RemoteInput` or the explicitly enabled accessibility bridge. The delegated assistant is instructed to identify itself as an AI assistant rather than impersonating the owner. Per-contact reply limits, session summaries, Inspector events, and local history are included.

WeChat is not an official open automation surface for personal accounts. UI changes, OEM behavior, missing `RemoteInput`, lock-screen state, and accessibility timing can cause failure or misrouting risk. This feature requires careful supervision. WeChat notification content remains `EXTERNAL_UNTRUSTED` and cannot call the owner-only Android Agent tool.

## Current limitations

- One Android tool execution per Owner Chat turn; there is no multi-tool planner yet.
- Only one Android Agent task can control the phone UI at a time.
- Vision decisions can fail when layouts, text, animations, dialogs, or OEM behavior change.
- App launch support is dynamic but does not imply reliable operation of every installed app.
- Text injection depends on a correctly focused editable field and Mobilerun/Shizuku integration.
- High-risk actions require explicit owner confirmation.
- Authentication secrets and biometric steps are intentionally not automated.
- Agent Server and Mobilerun Portal may require manual restart after Android background-process cleanup.
- There is no industrial-grade background self-healing, durable task queue, or multi-device orchestration.
- Device/ROM validation is limited; behavior has not been broadly tested across Android vendors and versions.
- WeChat automation depends on notification and accessibility behavior and is not equivalent to a supported messaging API.

## Project structure

```text
app/                       Android application source, resources, tests, and Room schemas
agent/                     Termux/Python vision-agent runtime snapshot and offline tests
gradle/                    Gradle wrapper files
build.gradle.kts           Root Android build configuration
settings.gradle.kts        Gradle project settings
README.md                  Setup, security model, and operating notes
```

Local build outputs, credentials, databases, diagnostics, screenshots, SDK installations, and machine-specific configuration are excluded by `.gitignore`.

## Development checks

Android unit tests and debug build:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Python syntax and offline resolver/executor tests:

```sh
python -m py_compile agent/mr_agent.py agent/agent_server.py
python -m unittest -v agent/test_mr_agent_app_launch.py
```

## License

No open-source license has been specified yet. Public availability of the source does not by itself grant a license to use, modify, or redistribute it.
