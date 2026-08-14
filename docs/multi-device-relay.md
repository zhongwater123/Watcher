# Multi-Device Relay - 跨端会话流转技术指引

## 概述

Watcher Multi-Device Relay 实现了 PC Agent 与用户手机之间的跨端会话接续。PC 端 AI Agent 可通过 MCP 工具发起会话，将上下文传递到手机，用户在手机上继续对话后交回 PC。

核心技术栈：ntfy pub/sub 消息中继 + MCP (Model Context Protocol) 工具 + Android Jetpack Compose UI。

---

## 架构

```
PC Agent (Claude/GPT/etc.)
    ↕ MCP stdio
watcher-mcp (Node.js)
    ↕ HTTP poll (relay.v1 protocol)
ntfy server (self-hosted, auth + ACL)
    ↕ HTTP stream (JSON lines)
Watcher Android App (NtfyRelayClient)
    ↕ Compose UI
Phone User
```

### 数据流

```
PC → Phone (handoff):
  Agent → handoff_conversation MCP → ntfyPublish → ntfy server → phone subscribe stream → notification + UI

Phone → PC (reply):
  User input → NtfyRelayClient.publish → ntfy server → MCP ntfyPoll → inbox → wait_for_relay_reply returns

Phone → PC (hand_back):
  User taps "交回" → publish(type=hand_back) → ntfy → MCP detects isHandBack=true → Agent resumes
```

---

## 消息协议 — relay.v1

### Payload 结构

```json
{
  "schema": "relay.v1",
  "type": "message",
  "messageId": "msg_a3f8b2c1d4e5",
  "conversationId": "sess_1781005909858",
  "turnId": 5,
  "replyTo": "msg_01HZABC000",
  "author": "phone_user",
  "content": "消息内容",
  "createdAt": "2026-06-09T19:55:14.258+08:00",
  "ts": 1781006114258,
  "title": null,
  "summary": null,
  "status": null
}
```

### 消息类型

| type | 方向 | 用途 |
|------|------|------|
| `handoff` | PC → Phone | 发起跨端会话，携带 title + summary |
| `message` | 双向 | 普通对话消息 |
| `hand_back` | Phone → PC | 交回控制权 |
| `presence` | Phone → ntfy | 心跳/在线状态广播 |

### 关键字段

| 字段 | 作用 |
|------|------|
| `messageId` | 客户端生成唯一 ID，用于幂等去重（retry 不产生重复） |
| `turnId` | 会话内递增序号，用于排序 |
| `conversationId` | 会话标识，一个 topic 内可有多个会话 |
| `schema` | 协议版本标记，向后兼容用 |

---

## MCP 工具包 (watcher-mcp)

### 安装

```bash
npm install -g watcher-mcp
# 或在 MCP 配置中指定 npx watcher-mcp
```

### 工具分类

#### Relay 工具（5个，跨端对话，无需 LAN）

| 工具 | 用途 |
|------|------|
| `check_phone_available` | 检测手机在线状态（presence 心跳） |
| `handoff_conversation` | 发起跨端会话 |
| `wait_for_relay_reply` | 阻塞等待手机回复（内部 inbox poll） |
| `get_relay_messages` | 查看完整消息历史 |
| `send_relay_message` | 发送追加消息 |

#### Gateway 工具（13个，LAN 设备控制）

| 工具 | 用途 |
|------|------|
| `discover_devices` | mDNS 局域网发现 |
| `bind_device` | 配对绑定设备 |
| `capture_snapshot` | 摄像头抓帧 |
| `create_task` | 创建监控/视频分析任务 |
| 其他 | list_tasks, get_task, cancel_task, etc. |

### 首次配对流程

```
1. 用户打开 Watcher App → 多端聚合 → 配置 Tab → 点"复制配置"
2. 用户粘贴给 PC Agent：
   topic: watcher-fb02ec34
   server: http://ntfy.shokz-watcher.cn
   token: tk_xxxxx
3. Agent 调用 check_phone_available(topic, authToken) → 存储到 relay-config.json
4. 后续所有调用自动使用，永不再配
```

### 本地持久化

```
~/.watcher-mcp/
├── devices.json       — 绑定的设备信息
├── relay-config.json  — topic + token + serverUrl
└── inbox.json         — 消息 inbox + 消费 cursor
```

### Inbox + Cursor 架构

```
ntfy poll → inbox.ingest() → inbox.json (messages + cursors)
                                    ↓
wait_for_relay_reply → inbox.getUnconsumed() → markConsumed() → return
get_relay_messages   → inbox.getAll() → return full history
```

- **幂等入库**：按 messageId 去重，retry 不产生重复
- **持久化 cursor**：跨 tool call / 跨 MCP 重启保持消费位点
- **不丢消息**：poll 失败不影响已入库消息

---

## Android 端实现

### 关键类

| 类 | 职责 |
|----|------|
| `NtfyRelayClient` | ntfy 连接管理、publish/subscribe、消息解析、心跳 |
| `GatewayDelegate` | ntfy 生命周期、配置持久化、通知、turnId 管理 |
| `GatewayStateHolder` | 跨 Activity 状态桥接 |
| `MultiDeviceViewModel` | UI 状态暴露 |
| `MultiDeviceScreen` | Compose UI（侧边栏、对话、加载动画） |
| `WatcherForegroundService` | 后台保活（reason-based 引用计数） |

### 消息发送流程

```kotlin
// 乐观发送 + 状态管理
1. addMessage(payload, SendStatus.Sending)  // 立即显示，带转圈
2. publishDirect(cfg, payload)              // HTTP POST to ntfy
3a. 成功 → updateMessageStatus(Confirmed)   // 转圈消失
3b. 失败 → updateMessageStatus(Failed)      // 红色感叹号
    → retry (attempt 2)
4. subscribe stream 收到自己的消息 → reconcile（Sending→Confirmed）
```

### 持久化策略

| 数据 | 存储方式 | 恢复时机 |
|------|----------|----------|
| 会话列表 | SharedPreferences JSON | App 启动 |
| 消息历史 | SharedPreferences JSON (最新200条) | App 启动 |
| ntfy 配置 | SharedPreferences | App 启动 |
| turnId 计数 | SharedPreferences (per-conversation) | 发消息时 |

### 后台保活

```kotlin
WatcherForegroundService.start(context, "ntfy 消息通道运行中", REASON_NTFY_RELAY)
// reason-based: monitor/video/ntfy_relay 可并存，全部 reason 移除才 stopSelf
```

### UI 结构

```
MultiDeviceActivity
└── MultiDeviceScreen (Tabs: 对话 / 配置)
    └── RelayChatTab
        ├── ModalNavigationDrawer (侧边栏：活跃/历史会话 + 删除)
        └── Content
            ├── Header (hamburger + 状态点+Switch + 上下文按钮 + 交回按钮)
            ├── CollapsibleContextCard (PC 上下文摘要)
            ├── MessageListCard (消息列表 + Rose Four 加载动画)
            └── InputBar (多行输入 + 发送按钮 + 键盘收起)
```

---

## 安全架构

### ntfy 服务端配置

```yaml
# /etc/ntfy/server.yml
auth-default-access: "deny-all"
```

```bash
# 创建 service 账户 + 通配符权限
ntfy user add watcher-service
ntfy access watcher-service 'watcher-*' rw
ntfy token add watcher-service
# → tk_xxxxx (所有设备共享此 token)
```

### 安全边界

| 层 | 机制 |
|---|------|
| 外部访问 | deny-all，无 token 全部拒绝 |
| 设备间隔离 | per-device 随机 topic (watcher-{8hex}) |
| 认证 | Bearer token（service level） |
| 传输 | HTTP（待升级 HTTPS） |

---

## 并发安全

| 资源 | 保护机制 |
|------|----------|
| `_messages` StateFlow | `synchronized(messageLock)` |
| `nextMessageId` | `AtomicLong` |
| `publishCount` / `receiveCount` | `AtomicInteger` |
| ForegroundService `activeReasons` | `Collections.synchronizedSet` |

---

## 网络容错

| 场景 | 处理 |
|------|------|
| publish HTTP 超时 | 30s timeout + 自动 retry 1次 |
| subscribe 断连 | 90s watchdog + 指数退避重连 |
| MCP poll 超时 | 8s timeout + 3s 间隔循环 |
| 消息到达但确认丢失 | subscribe 回来后 reconcile (Sending→Confirmed) |

---

## 加载动画 — Rose Four

数学公式（四瓣玫瑰线）：
```
r(t) = (9.2 + 0.6·s) · (0.72 + 0.28·s) · cos(4t)
x(t) = cx + cos(t) · r(t) · scale
y(t) = cy + sin(t) · r(t) · scale
```

实现：Compose Canvas + InfiniteTransition，3个动画维度：
- 粒子沿曲线运动（5.4s 一圈）
- 曲线脉动呼吸（4.5s 周期）
- 整体缓慢旋转（28s 一圈）

触发条件：最后一条消息是 phone_user 发的且已确认，会话未交回。

---

## 版本历史

| 版本 | 主要变更 |
|------|----------|
| 0.5.0 | 初始 ntfy relay 实现 |
| 0.6.0 | 安全加固 + per-device topic + auth token |
| 0.7.0 | relay.v1 协议 + inbox cursor + 工具描述优化 |
