# DSH Android Approver

DeepSeek Harness (DSH) 的 Android companion：手机查看当前活跃 agent，并接收、批准或拒绝 DSH 的一次性权限请求。

## 组成

- **DSH plugin**：监听官方 `approval/request` waterfall，为每个请求生成一次性 ID，并等待手机决定。
- **WebSocket**：插件独立监听一个带 token 的 WebSocket 端口，避免要求 DSH 浏览器 Web UI 必须暴露到局域网。
- **Android / Jetpack Compose**：显示活跃任务和待审批操作；收到请求时发送系统通知。
- **一次性授权**：只有 `allowed-once` 会让 DSH 操作继续；拒绝、取消、超时、任务退出都会 fail-closed。

## DSH 安装

```bash
pnpm install
pnpm build
dsh plugin --profile web add /absolute/path/to/dsh-android-approver
```

然后在 profile 的 bundle 配置中设置强随机 token：

```yaml
- id: dsh-android-approver
  name: dsh-android-approver
  config:
    host: "0.0.0.0"
    port: 38741
    token: "a-long-random-secret"
    approvalTimeoutMs: 120000
```

重启 DSH。

## Android

打开 Android Studio 导入仓库根目录，运行 `app`。

在应用中填写：

```
ws://<运行 DSH 的电脑局域网 IP>:38741/ws
```

以及相同的 token，然后点击 **Connect**。

应用会启动 foreground service 保持 WebSocket 连接；收到审批请求时，Android 会显示通知，同时 Compose 页面出现 **Allow once / Reject**。

## 协议

DSH → Android：

```json
{
  "type": "approval/requested",
  "approval": {
    "id": "...",
    "taskId": "...",
    "sessionId": "...",
    "toolName": "bash",
    "reason": "..."
  }
}
```

Android → DSH：

```json
{"type":"approval/decision","approvalId":"...","decision":"allow-once"}
```

或：

```json
{"type":"approval/decision","approvalId":"...","decision":"reject"}
```

DSH 只接受一次决定，重复的 approval ID 会被忽略。

## 安全

不要把 38741 端口直接暴露到公网。当前版本使用共享 token + WebSocket，推荐只在可信 LAN、Tailscale/WireGuard 等 VPN 内使用。如果以后需要完全离线连接也能收到系统级推送，可以在相同协议上增加 FCM transport。

## 当前任务模型

插件通过官方 `agent/created` / `agent/disposed` 生命周期维护 live agent 列表；因此 Android 展示的是当前活跃 agent，而不是历史 session。
