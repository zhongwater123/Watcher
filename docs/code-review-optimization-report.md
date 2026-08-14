# Watcher 项目代码优化审查报告

> 生成日期: 2026-06-10
> 审查范围: 全项目（Android App + MCP Server）
> 五轮累计发现: **271 项优化建议**

---

## 目录

1. [安全问题](#1-安全问题)
2. [并发与线程安全](#2-并发与线程安全)
3. [性能问题](#3-性能问题)
4. [资源泄漏与 OOM 风险](#4-资源泄漏与-oom-风险)
5. [网络与连接管理](#5-网络与连接管理)
6. [错误处理](#6-错误处理)
7. [架构与职责划分](#7-架构与职责划分)
8. [代码重复](#8-代码重复)
9. [API 设计](#9-api-设计)
10. [Agent Framework](#10-agent-framework)
11. [数据层与持久化](#11-数据层与持久化)
12. [Compose UI 性能](#12-compose-ui-性能)
13. [可访问性与输入验证](#13-可访问性与输入验证)
14. [推荐修复路径](#14-推荐修复路径)

---

## 1. 安全问题

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | API Key 为空时 `apiKey.isNotBlank()` 跳过认证，所有保护端点暴露 | GatewayServer:78-86 | Critical |
| 2 | ntfy 认证 token 硬编码在源码中 | ntfy-client.js:54, GatewayDelegate:195 | Critical |
| 3 | ntfy 通信使用 HTTP 非 HTTPS | ntfy-client.js:52 | High |
| 4 | `withDevice()` 传递 undefined 作为 auth 参数，可能绕过网关认证 | mcp/server.js:351-352 | High |
| 5 | API Key 默认值硬编码 | GatewayModels:169 | High |
| 6 | WalletShareManager 导出 API Key 明文 JSON 至剪贴板 | WalletShareManager:50-75 | Medium |
| 7 | importFromText() 不验证 endpoint URL 格式 | WalletShareManager:102-105 | Medium |
| 8 | OpenAiCompatibleProvider 不拒绝 HTTP 端点 | OpenAiCompatibleProvider | Low |

---

## 2. 并发与线程安全

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | `@Synchronized` 内更新 StateFlow，观察者触发可能死锁 | GatewayAutomationManager:62-110 | Critical |
| 2 | `synchronizedSet(activeReasons)` 迭代未加锁 | WatcherForegroundService:164, 81-92 | Critical |
| 3 | `@Volatile streamOwner/reclaimRequested` 复合操作无原子保证 | GatewayDelegate:79-80, 477-499 | Critical |
| 4 | Relay 消息列表 load→modify→save 非原子，并发丢消息 | GatewayAutomationManager:174-178 | Critical |
| 5 | AiAudienceManager 15+ 可变集合无同步，多心跳协程并发修改 | AiAudienceManager:68-90 | Critical |
| 6 | MonitorManager 多个 var 字段多协程无同步 | MonitorManager:52-62 | High |
| 7 | `startMonitoring()` 多状态突变非原子 | MonitorManager:86-96 | High |
| 8 | LlmWalletRepository `secretsMigrated` volatile 无锁检查 | LlmWalletRepository:66-67, 244 | High |
| 9 | ArkStreamingClient `StringBuilder` 跨挂起点使用 | ArkStreamingClient:28, 99-119 | High |
| 10 | AgentFrameworkService 嵌套 mutex 死锁风险 | AgentFrameworkService:619-639 | High |
| 11 | VideoExecutionOrchestrator CopyOnWriteArrayList worker 写入同时主线程 sort | VideoExecutionOrchestrator:198 | High |
| 12 | GraphRuntime `waitForSignal()` 轮询有竞态，信号可丢 | GraphRuntime:306-321 | High |
| 13 | AgentFrameworkService `completeAutonomousRuntime()` 释放锁后 runtime 可被删 | AgentFrameworkService:641-682 | High |
| 14 | GraphExecutionState `state.outputs += output` 直接突变无同步 | DefaultAgentGraph:195 | High |
| 15 | LiteRtEngineManager `withConversation()` 持 mutex 执行用户 block 10s+ | LiteRtEngineManager:103-129 | High |
| 16 | ManagedAgent Channel(128) 满后 sender 无限挂起 | ManagedAgent:65, 93-100 | High |
| 17 | `subscriptionJob` 可被快速连续调用重复启动 | NtfyRelayClient:142-158 | Medium |
| 18 | `messageLock` 不保护 `_conversations` 更新 | NtfyRelayClient:395-415 | Medium |
| 19 | LiveCommentaryRepository `pendingCount` 检查与使用之间竞态 | LiveCommentaryRepository:407-411 | Medium |
| 20 | MainScreen `navigationJob/lastNavigationAtMillis` 闭包内可变状态 | MainScreen:895-932 | Medium |
| 21 | HistoryRepository 同步 DAO 调用阻塞协程 | HistoryRepository:169, 188 | Medium |
| 22 | `lastPersistedCheckTime` 无同步，多协程竞态 | MonitorWorkflowController:46 | Medium |
| 23 | `activeRunId` 异步设置但 finally 块读取 | VideoWorkflowController:153-189 | Medium |
| 24 | WatcherApplication lazy 初始化竞态 | WatcherApplication:76-77 | Medium |
| 25 | CouncilManager debug caches 无锁 | CouncilManager:102-108, 578 | Medium |
| 26 | DefaultStructuredMemoryManager 单 mutex 序列化所有会话操作 | StructuredMemorySupport:60-179 | Medium |
| 27 | AutonomousAgentRuntime mutateSnapshot 持锁发事件 | AutonomousAgentRuntime:575-587 | Medium |
| 28 | AiAudienceManager `recentGifts.size > 10` + `removeAt(0)` 非原子 | AiAudienceManager:768-769 | Medium |
| 29 | LiteRtViewModel `_chatHistory.value` 直接赋值无原子操作 | LiteRtViewModel:43-44, 129 | Medium |
| 30 | AiAudienceManager `providerMutexes` map 本身非线程安全 | AiAudienceManager:289 | Medium |

---

## 3. 性能问题

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | SharedPreferences 每次操作全量反序列化 JSON 无缓存 | GatewayAutomationManager:489-599 | High |
| 2 | 多次 `.toMutableList() + filter + sort` 链产生中间集合 | GatewayAutomationManager:269, 539-563 | High |
| 3 | `maxOfOrNull()` O(n) 扫描每请求重复 | GatewayServer:217, 323, 493 | High |
| 4 | HistoryRepository `combine()` 5 个 observeAll，任一变化 5 表全查 O(n²) | HistoryRepository:53-110 | High |
| 5 | HistoryRepository `sumOf(::fileSize)` 每次 emission 遍历文件 stat | HistoryRepository:132 | High |
| 6 | MonitorManager `performCheck()` 与 `performCheckWithWallet()` 完全重复 | MonitorManager:279-337 vs 127-187 | High |
| 7 | VideoExecutionOrchestrator `segmentFeedbacks.toList()` 9 处重复 | VideoExecutionOrchestrator:191-953 | High |
| 8 | VideoExecutionOrchestrator `partialTimelineEvents.sortedBy()` 热路径重复排序 | VideoExecutionOrchestrator:177, 587, 633 | High |
| 9 | `VideoProcessingStatus` 40+ 字段 copy() 每次状态更新全量复制 | VideoWorkflowController:341-389 | High |
| 10 | MainScreen 40+ `collectAsStateWithLifecycle()` 单 Composable | MainScreen:109-149 | High |
| 11 | Compose 内 `conversations.sortedByDescending()` 每帧重算 | MultiDeviceScreen:187-189 | High |
| 12 | 200+ 调试日志用 `Column + forEach` 未 Lazy 化 | MultiDeviceScreen:589-605 | High |
| 13 | 10+ StateFlow 独立收集，任一变化触发全屏 recomposition | MultiDeviceActivity:59-69 | High |
| 14 | SharedPreferences 多次 `.apply()` 无批量合并 | GatewayAutomationManager:497-598 | Medium |
| 15 | StateFlow 更新每次重新 sort 完整列表 | GatewayDelegate:573-576 | Medium |
| 16 | MonitorManager 每帧 252 像素采样无缓存网格 | MonitorManager:149 | Medium |
| 17 | VideoSegmentProcessor 固定 2s 轮询 ×150 次无指数退避 | VideoSegmentProcessor:352-376 | Medium |
| 18 | LiveCommentaryRepository `entries.map().distinctBy().sortedByDescending().take()` 每更新 O(n log n) | LiveCommentaryRepository:576-581 | Medium |
| 19 | IntentRepository 每次 Bitmap→Base64 无缓存 | IntentRepository:32-35 | Medium |
| 20 | RoseFourLoader 每帧重建 240 点 Path + 48 粒子无缓存 | RoseFourLoader:61-87 | Medium |
| 21 | Bitmap 解码未指定 `Dispatchers.Default` 阻塞主线程 | MonitorWorkflowController:71, 144, 173 | Medium |
| 22 | 权限检查每次同步调用（结果不变） | VideoWorkflowController:230-233 | Medium |
| 23 | Snapshot 每请求 bitmap.compress() 无帧缓存 | GatewayRoutes:161-168 | Medium |
| 24 | GatewayStateHolder 空 delegate 时每次属性访问新建 MutableStateFlow | GatewayStateHolder:44-72 | Medium |
| 25 | AppDatabase 批量查询无分页，全量加载内存 | HistoryRepository:114-119 | Medium |
| 26 | AgentFrameworkService `listInvocations` N+1 查询 | AgentFrameworkService:514-518 | Medium |
| 27 | LiveCommentaryRepository `join()` 顺序调用而非 `awaitAll()` | LiveCommentaryRepository:190-194 | Medium |
| 28 | DefaultPerceptionPipeline 每信号 `.lowercase()` 无缓存 | DefaultAutonomousModules:32-56 | Medium |
| 29 | BrainBackedReasoningEngine 4+ 中间列表/cycle | DefaultAutonomousModules:154-178 | Medium |
| 30 | InMemorySharedBlackboard query 线性扫描 + 嵌套 tag 匹配 O(n*m) | SharedCollaboration:41-46 | Medium |
| 31 | AgentFrameworkService 每事件 takeLast(256) 创建新列表 | AgentFrameworkService:61, 629 | Medium |
| 32 | GraphRuntime snapshot 更新持 mutex 整个 copy() | GraphRuntime:279-287 | Medium |
| 33 | DefaultAgentGraph `graph.resolve(nodeId)` 热路径重复查找 | DefaultAgentGraph:81-143 | Medium |
| 34 | InMemoryAgentMemoryStore `removeAt(0)` O(n) | AgentMemoryStore:39-42 | Low |
| 35 | AppDefaultAgentBrainFactory 每次 decision 调 resolveProvider() | AppDefaultAgentBrainFactory:72-98 | Medium |
| 36 | BehaviorModelDao `observeAllClaims()` 全表无 LIMIT | BehaviorModelDao:15 | Medium |
| 37 | BlackboardDao 单日 10K+ 无 LIMIT | BlackboardDao:49-53 | Medium |

---

## 4. 资源泄漏与 OOM 风险

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | MjpegStreamPlayer Bitmap 直接持有在 State 中无回收/池化 | MjpegStreamPlayer:212-216 | Critical |
| 2 | ArkStreamingClient `StringBuilder` 全量累积响应无上限 | ArkStreamingClient:28-119 | Critical |
| 3 | OpenAiCompatibleProvider response.body 读两次 | OpenAiCompatibleProvider:91-99 | Critical |
| 4 | FrontCameraStreamFallback 每帧分配新 ByteArray+IntArray 无池化 | FrontCameraStreamFallback:238-260 | High |
| 5 | HistoryWorkbenchPage VideoView 无 release() | HistoryWorkbenchPage:701-738 | High |
| 6 | HistoryWorkbenchPage Snapshot bitmap remember 后永不 recycle | HistoryWorkbenchPage:742-758 | High |
| 7 | MjpegStreamPlayer ByteArrayOutputStream 可无限增长 | MjpegStreamPlayer:609 | High |
| 8 | AiAudienceManager lastPostContent/lastResponse 全量缓存无 TTL | AiAudienceManager:80-83 | High |
| 9 | MonitorManager `lastAnalyzedFrame` 持大 Bitmap 引用 | MonitorManager:52 | High |
| 10 | MonitorManager `release()` 取消 scope 但不等待 Job | MonitorManager:256-258 | High |
| 11 | IntentViewModel 循环引用 gatewayDelegate→stateHolder→delegate | IntentViewModel:273 | High |
| 12 | LiveCommentaryRepository Channel 4 段 ×50MB = 200MB 常驻 | LiveCommentaryRepository:91 | High |
| 13 | WatcherApplication LiteRT 模型 lazy 加载后永不释放 | WatcherApplication:84-104 | High |
| 14 | WatcherApplication `appScope` SupervisorJob 永不取消 | WatcherApplication:37-42 | Medium |
| 15 | VideoSegmentProcessor mergedFile 后续失败无清理 | VideoSegmentProcessor:102-103 | Medium |
| 16 | AgentFrameworkService `autonomousMemoryManager` lazy 永不释放 | AgentFrameworkService:70-72 | Medium |
| 17 | GatewayDelegate scope.launch 监听无法取消 | GatewayDelegate:110-114 | Medium |
| 18 | GatewayDelegate server 异常后引用非 null 但无功能 | GatewayDelegate:72, 418, 502 | Medium |
| 19 | GatewayDelegate ntfyClient 回调持 Delegate 引用 | GatewayDelegate:92, 271 | Medium |
| 20 | WakeLock 无 try-finally 保障释放 | WatcherForegroundService:142, 147-150 | Medium |
| 21 | VideoProcessRepository 闭包捕获整个实例 | VideoProcessRepository:35-114 | Medium |
| 22 | NtfyRelayClient shutdown 未关闭 response body | NtfyRelayClient:304-305 | Medium |
| 23 | Fire-and-forget launches 在 catch 块内未追踪 | VideoWorkflowController:267, 297 | Medium |
| 24 | MultiAgentCoordinator messages 无限累积 | MultiAgentCoordinator:169-175 | High |
| 25 | InMemoryTeamMessageBus 广播 N 代理 = N 倍内存 | SharedCollaboration:89-93 | Medium |
| 26 | InMemoryGraphCheckpointStore 检查点无 TTL | GraphCheckpoint:65-72 | Medium |
| 27 | InMemoryAgentKnowledgeStore 死会话条目永不淘汰 | AgentKnowledgeStore:33-94 | Medium |
| 28 | SharedFlow replay=32 + extraBuffer=128 每 runtime 160 项 | AutonomousAgentRuntime:47-50 | Medium |
| 29 | LiteRtViewModel tempFile 每张图存 cache 永不清理 | LiteRtViewModel:178-182 | Medium |
| 30 | AsrConfigRepository 每次 test 新建 OkHttpClient 不 shutdown | AsrConfigRepository:194-351 | Medium |

---

## 5. 网络与连接管理

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | RetrofitClient 无 ConnectionPool 配置，流式 readTimeout=0 | RetrofitClient:24-36 | High |
| 2 | OpenAiCompatibleProvider 每实例独立 OkHttpClient | OpenAiCompatibleProvider:22-26 | High |
| 3 | AiAudienceManager 每消息/tick 新建 Provider | AiAudienceManager:197-201, 280-286 | High |
| 4 | CouncilManager 每分析新建 Provider | CouncilManager:765-772 | High |
| 5 | RetrofitClient 无重试拦截器/离线检测/gzip | RetrofitClient:25 | Medium |
| 6 | 流式客户端断流后无自动重连 | ArkStreamingClient 全文 | Medium |
| 7 | NanoHTTPD 每请求新线程无连接池 | GatewayServer:37 | Medium |
| 8 | ntfy 仅单次重试无指数退避 | ntfy-client.js:31-44 | Medium |
| 9 | ApiWalletViewModel `testProvider()` 无 timeout | ApiWalletViewModel:207-264 | Medium |
| 10 | LiteRtLlmProvider `withConversation()` 无 timeout | LiteRtLlmProvider:28-37 | Medium |

---

## 6. 错误处理

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | JSON 解析失败静默返回空列表，数据无声丢失 | GatewayAutomationManager:493-583 | High |
| 2 | ntfy publish 失败仅日志不更新 `_relayError` | GatewayDelegate:136-149 | High |
| 3 | Task 执行器抛异常但调用方仅检字符串 | GatewayDelegate:596-756 | Medium |
| 4 | `onHandoffReceived?.invoke()` 回调异常崩溃订阅循环 | NtfyRelayClient:419 | High |
| 5 | LiveCommentaryRepository `enqueueSegmentWithBackpressure` 无限循环 | LiveCommentaryRepository:376-405 | High |
| 6 | MonitorManager LED/日志多个 fire-and-forget 静默失败 | MonitorManager:580-640 | Medium |
| 7 | ArkStreamingClient JSON 解析失败 runCatching 静默丢弃 | ArkStreamingClient:84-86 | Medium |
| 8 | HistoryRepository `runCatching` 吞掉文件删除异常 | HistoryRepository:319-324 | Medium |
| 9 | LlmWalletRepository enum valueOf 失败静默降级 Untested | LlmWalletRepository:187 | Medium |
| 10 | VideoExecutionOrchestrator catch-all 丢失原始堆栈 | VideoExecutionOrchestrator:769-775 | Medium |
| 11 | AgentFrameworkService scope.launch 异常被 SupervisorJob 吞 | AgentFrameworkService:311-315 | Medium |
| 12 | MainScreen speech/URL launch catch 吞异常 | MainScreen:386-389, 461-464 | Medium |
| 13 | VideoSegmentProcessor 音频解析失败继续但不通知 | VideoSegmentProcessor:206-228 | Medium |
| 14 | WatcherApplication LiteRT init 无超时可永不取消 | WatcherApplication:50-69 | Medium |
| 15 | FileStructuredMemoryManager JSON 解析失败静默返回空 | FileStructuredMemoryManager:28-36 | Medium |
| 16 | AgentOrchestrator `runGathering()` async 无 try-catch | AgentOrchestrator:45-72 | Medium |
| 17 | ToolExecutor 工具执行无 timeout | ToolExecutor:18-27 | Medium |
| 18 | JsonProtocolAgentBrain 解析失败静默返回 Finish | JsonProtocolAgentBrain:105-119 | Medium |
| 19 | MonitorSessionRecorder 帧编码失败空 catch | MonitorSessionRecorder:37-54 | Low |
| 20 | CouncilManager 知识提取异常 catch 无日志 | CouncilManager:460 | Low |

---

## 7. 架构与职责划分

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | GatewayDelegate 承担 8+ 职责 | GatewayDelegate 全文 | High |
| 2 | GatewayServer.serve() 50+ 分支路由混合解析和业务逻辑 | GatewayServer:101-238 | High |
| 3 | IntentViewModel 605 行、30+ 依赖、init 创建 15+ 对象 | IntentViewModel 全文 | High |
| 4 | MonitorManager 700+ 行兼管监控/日志/LLM/快照/LED | MonitorManager 全文 | High |
| 5 | VideoProcessRepository 构造器 11 个组件 | VideoProcessRepository:35-114 | High |
| 6 | GatewayAutomationManager 回调驱动循环依赖 | GatewayAutomationManager:31-32 | Medium |
| 7 | 3+ JSON 库并存（Gson/JSONObject/JSONArray） | 多文件 | Medium |
| 8 | HubOverviewPage 24+ 参数直接传递 | HubOverviewPage:51-81 | Medium |
| 9 | MultiDeviceViewModel 薄 wrapper 无附加值 | MultiDeviceViewModel:37-46 | Low |

---

## 8. 代码重复

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | MonitorManager performCheck / performCheckWithWallet 完全重复 | MonitorManager:279-337 vs 127-187 | High |
| 2 | RelayConversation CRUD 每次 load→filter→sort→save | GatewayAutomationManager:162-311 | Medium |
| 3 | 6 个 parse 函数同一 map/null-coalescing 模式 | GatewayServer:661-736 | Medium |
| 4 | 8 个错误响应辅助函数几乎相同 | GatewayServer:544-589 | Medium |
| 5 | 6 个 loadFromPrefs/saveToPrefs 模式完全重复 | GatewayAutomationManager:489-598 | Medium |
| 6 | Relay handlers "resolve config→call ntfy→handle error" | mcp/server.js:679-879 | Medium |
| 7 | ApiWalletViewModel testProvider/setProviderEnabled 重复模式 | ApiWalletViewModel:207-318 | Medium |
| 8 | LlmWalletRepository Ark 兼容检查两处重复 | LlmWalletRepository:275-284 | Low |
| 9 | InMemoryCommunicationHub / MemoryStore 相同 trim 模式 | DefaultAutonomousModules/AgentMemoryStore | Low |

---

## 9. API 设计

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | 认证模型不一致：部分端点需 API key 部分无 auth | GatewayServer:619-643 | Medium |
| 2 | Task events 无 streaming/long-poll | GatewayServer:125 | Medium |
| 3 | Automation events 同时用 timestamp 和 eventId 两套游标 | GatewayServer:486-495 | Medium |
| 4 | Snapshot 端点无 Cache-Control/ETag | GatewayServer:524-534 | Low |
| 5 | Task events 列表无上限，长时间任务可 OOM | GatewayModels:19 | Medium |
| 6 | `parseTrigger()` 硬编码 trigger 类型无扩展性 | GatewayAutomationManager:452-461 | Low |

---

## 10. Agent Framework

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | AgentFrameworkService 多处 `runBlocking` 在异步上下文 | AgentFrameworkService:114, 129-146 | High |
| 2 | MultiAgentCoordinator 失败任务重分配无历史追踪可无限循环 | MultiAgentCoordinator:208-245 | High |
| 3 | GraphRuntime 步骤失败仅计数无可配置重试策略 | GraphRuntime:172-186 | Medium |
| 4 | InMemoryCommunicationHub 先 append 后 trim 可超限 1 项 | DefaultAutonomousModules:510-576 | Low |
| 5 | Tool 执行无 circuit breaker，30s 阻塞整个 cycle | DefaultAutonomousModules:239-320 | Medium |
| 6 | FileStructuredMemoryManager 原子写无完整性验证 | FileStructuredMemoryManager:68-81 | Medium |
| 7 | Coordinator 生命周期状态机无合法转换验证 | MultiAgentCoordinator:145-206 | Low |
| 8 | LlmBackend 直接调 provider.chat() 无 timeout/retry/circuit breaker | LlmBackend:12-37 | Medium |

---

## 11. 数据层与持久化

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | AppDatabase 版本 57，46 次迁移无合并 | AppDatabase:71 | Medium |
| 2 | 缺常用查询组合索引 `(runId, timestamp)` | AppDatabase 多处 | High |
| 3 | 破坏性迁移 DELETE 无归档 | AppDatabase:664, 682 | Medium |
| 4 | Nullable UNIQUE index 行为不一致 | AppDatabase:769 | Low |
| 5 | SceneEntity 含 MutableMap/MutableList/var 字段 | SceneEntity:11-15 | High |
| 6 | TemplateEntities/CommentaryModels/BehaviorModels 无 @Stable | 多文件 | Medium |
| 7 | BehaviorClaim confidenceScore 无 [0,1] 验证 | BehaviorModels:54-55 | Low |
| 8 | MonitorTaskTemplates.all 静态列表可被外部修改 | MonitorTaskTemplates:173-184 | Low |
| 9 | LiteRtModelLocator 仅校验文件大小不验证 SHA256 | LiteRtModelLocator:21-24 | Medium |
| 10 | LiteRtModelDownloader renameTo 失败遗留 temp 文件 | LiteRtModelDownloader:124-127 | Medium |
| 11 | LlmWalletRepository `ensureSecretsMigrated()` 每次查询前调用 | LlmWalletRepository:83-100 | Low |

---

## 12. Compose UI 性能

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | InfiniteTemplateTicker key 含绝对 index，滚动全量重组合 | InfiniteTemplateTicker:259-261 | High |
| 2 | VideoAnalysisWorkbenchPage remember 依赖对象引用而非 ID | VideoAnalysisWorkbenchPage:103-129 | High |
| 3 | MonitorWorkbenchPage 同样按对象引用 remember | MonitorWorkbenchPage:121-132 | High |
| 4 | VideoAnalysisWorkbenchPage editedTask 每 recomposition copy() | VideoAnalysisWorkbenchPage:131-143 | Medium |
| 5 | MjpegStreamPlayer 用 collectAsState 而非 collectAsStateWithLifecycle | MjpegStreamPlayer:110-112 | Medium |
| 6 | CommentaryFeedPanel items 仅 segmentIndex 为 key | LiveAnalysisPanels:89-95 | Medium |
| 7 | MonitorWorkbenchPage Base64→Bitmap 每次 remember 重算 | MonitorWorkbenchPage:325-335 | Medium |
| 8 | NavigationDrawerItem 循环无 key | MultiDeviceScreen:217-263 | Medium |
| 9 | Activity 层 mutableStateOf | MultiDeviceActivity:21 | Medium |
| 10 | MainScreen 7 个 Activity launcher 分配 | MainScreen:315-370 | Low |

---

## 13. 可访问性与输入验证

| # | 问题 | 位置 | 严重度 |
|---|------|------|--------|
| 1 | VideoAnalysisWorkbenchPage 数字输入无范围验证 | VideoAnalysisWorkbenchPage:136-143 | Medium |
| 2 | WatcherCards Icon 按钮缺 contentDescription | WatcherCards:190-200 | Medium |
| 3 | LiveAnalysisPanels 硬编码颜色未验证 WCAG 对比度 | LiveAnalysisPanels:141-144 | Low |
| 4 | VideoStreamSettingsDialog IP/端口无格式校验 | VideoStreamSettingsDialog:71-99 | Medium |
| 5 | MonitorWorkbenchPage BitmapFactory.decodeFile 失败无 fallback | MonitorWorkbenchPage:639-645 | Medium |
| 6 | MultiDeviceScreen Send 按钮未校验 selectedConversation null | MultiDeviceScreen:452-457 | Medium |
| 7 | NtfyConfigCard 允许空 URL 提交无 URI 验证 | MultiDeviceScreen:716-720 | Low |

---

## 14. 推荐修复路径

### P0 — 立即修复（崩溃/数据丢失/安全风险）

| 优先 | 类别 | 关键项 |
|------|------|--------|
| 1 | 安全 | API Key 空绕过认证、硬编码 token、HTTP 通信 |
| 2 | 资源 | response body double-read、Streaming 无限累积、Bitmap 无回收 |
| 3 | 并发 | AiAudienceManager 15+ 无锁集合、Relay 消息非原子、StateFlow 死锁 |

### P1 — 本周修复（性能/稳定性）

| 优先 | 类别 | 关键项 |
|------|------|--------|
| 4 | 网络 | OkHttpClient 爆炸式创建 → 共享连接池 |
| 5 | 性能 | HistoryRepository O(n²) combine、SharedPreferences 反序列化缓存 |
| 6 | 并发 | 嵌套 mutex 死锁、GraphRuntime 轮询竞态 |
| 7 | Compose | MainScreen 40+ flow 收集、key 不稳定 |

### P2 — 短期迭代（1-2 周）

| 优先 | 类别 | 关键项 |
|------|------|--------|
| 8 | 架构 | IntentViewModel/MonitorManager/GatewayDelegate 拆分 |
| 9 | Agent | 图执行引擎 timeout/retry、消息无界增长 |
| 10 | 数据层 | 缺失索引、全表查询分页、迁移合并 |
| 11 | 错误 | 静默失败→日志+错误传播 |

### P3 — 中期规划

| 优先 | 类别 | 关键项 |
|------|------|--------|
| 12 | 重复 | 消除 performCheck 等重复代码 |
| 13 | API | 统一认证模型、streaming 支持 |
| 14 | DI | 引入依赖注入框架 |
| 15 | 可访问性 | contentDescription、WCAG 对比度 |

---

## 附录：文件覆盖清单

本报告覆盖以下文件/目录：

**App 层:**
- `ui/viewmodel/`: IntentViewModel, MonitorWorkflowController, VideoWorkflowController, GatewayDelegate, MultiDeviceViewModel, LiveInteractionController, ApiWalletViewModel, LiteRtViewModel
- `ui/screens/`: MainScreen, MultiDeviceScreen, HistoryWorkbenchPage, VideoAnalysisWorkbenchPage, MonitorWorkbenchPage, HubOverviewPage, LiveAnalysisPanels, ApiWalletScreen
- `ui/components/`: MjpegStreamPlayer, InfiniteTemplateTicker, FrontCameraStreamFallback, RoseFourLoader, WatcherCards, VideoStreamSettingsDialog
- `data/gateway/`: GatewayServer, GatewayRoutes, GatewayAutomationManager, GatewayStateHolder, GatewayModels, NtfyRelayClient
- `data/repository/`: MonitorManager, VideoProcessRepository, VideoExecutionOrchestrator, VideoSegmentProcessor, VideoTaskPlanner, HistoryRepository, LlmWalletRepository, LiveCommentaryRepository, AiAudienceManager, CouncilManager, IntentRepository, TemplateRepository, AsrConfigRepository, MonitorSessionRecorder, ProviderSecretStore, WalletShareManager
- `data/remote/`: OpenAiCompatibleProvider, ArkStreamingClient, RetrofitClient, LiteRtLlmProvider
- `data/local/`: AppDatabase, BehaviorModelDao, BlackboardDao, LiteRtEngineManager, LiteRtModelLocator, LiteRtModelDownloader, LiteRtConfigStore
- `data/model/`: TemplateEntities, SceneEntity, BehaviorModels, CommentaryModels, AiAudienceModels, BlackboardModels, MonitorTaskTemplates, VideoTaskTemplates
- `data/agent/`: AgentOrchestrator, AgentRuntime, LlmBackend, ToolExecutor
- `agentframework/`: AgentFrameworkService, AutonomousAgentRuntime, DefaultAutonomousModules, StructuredMemorySupport, FileStructuredMemoryManager, AgentKnowledgeStore, AgentMemoryStore, GraphRuntime, DefaultAgentGraph, GraphCheckpoint, MultiAgentCoordinator, SharedCollaboration, ManagedAgent, JsonProtocolAgentBrain, AppDefaultAgentBrainFactory, AgentTool
- Root: WatcherApplication, WatcherForegroundService, MultiDeviceActivity

**MCP 层:**
- server.js, lib/gateway-client.js, lib/paths.js, lib/state.js, lib/inbox.js, lib/ntfy-client.js
