# Zen Read 原生 Android 架构（方案 B）

用 Kotlin + Jetpack Compose（Material 3 Expressive）重写，只做 Android。
上游 Flutter 代码（仓库根目录的 `lib/`、`assets/`）保留在 `native` 分支上作为**行为规格参考**，逐模块移植完成后再删除。

## 1. 范围

| 保留 | 删除 |
|---|---|
| 多格式书库（EPUB / MOBI / AZW3 / FB2 / TXT / PDF / CBZ） | iOS、macOS、Windows、Linux、鸿蒙 |
| 标注、笔记、书签 | OCR、插件系统、微信读书、词典 |
| 翻译（AI / DeepL / Google / Microsoft） | 阅读统计热力图、自动更新、问题反馈 |
| 听书 TTS（系统 / Edge / OpenAI 兼容） | 扫描书重排、分格阅读 |
| AI 阅读（对话、技能、RAG 检索） | |
| WebDAV、S3 同步 | |

## 2. 技术栈

| 领域 | 选型 | 说明 |
|---|---|---|
| 语言 / 构建 | Kotlin 2.4、AGP 9.5（内置 Kotlin）、Gradle 9.8、版本目录 `libs.versions.toml`、`build-logic` 约定插件 | |
| UI | Compose BOM 2026.11（material3 1.5）+ Expressive API、`material3-adaptive-navigation-suite` | 动态取色、预测性返回、edge-to-edge |
| 导航 | Navigation 3 | 返回栈由应用自己持有，天然支持预测性返回 |
| 依赖注入 | Hilt | |
| 异步 | Coroutines + Flow | |
| 数据库 | Room（KSP） | 表结构参考上游 `tb_books / tb_notes / tb_groups / tb_reading_time …` |
| 偏好 | DataStore | 密钥类数据用 Android Keystore 加密后再存 |
| 网络 | OkHttp 5 + kotlinx.serialization | WebDAV、S3、LLM、翻译、Edge TTS（WebSocket）共用一个客户端 |
| 后台任务 | WorkManager | 同步、RAG 索引、整书翻译 |
| 听书 | Media3 `MediaSessionService` | 通知栏 / 锁屏 / 蓝牙耳机控制 |
| 本地向量 | onnxruntime-android | 直接移植上游 `LocalEmbeddingPlugin.kt` |
| 图片 | Coil 3 | 封面 |

## 3. 模块划分

```
native/
├─ app                     Activity、导航宿主、Hilt 入口
├─ build-logic             约定插件（android-library / compose / feature / hilt / room）
├─ core/
│  ├─ model                纯 Kotlin 数据类（Book、Annotation、Progress…）
│  ├─ designsystem         主题（动态取色 + 自定义种子色 + 纯黑）、Expressive 组件封装
│  ├─ database             Room：DAO、迁移
│  ├─ datastore            用户偏好、加密凭据
│  ├─ network              OkHttp 实例、代理、日志
│  └─ common               Result、调度器、日志工具
├─ reader/
│  └─ engine               foliate-js 资源 + WebView 宿主 + JS 桥
├─ data/
│  ├─ library              导入、元数据解析、封面、分组
│  ├─ sync                 同步引擎 + WebDAV / S3 传输
│  ├─ ai                   LLM 客户端、技能、RAG（分块、向量、检索）
│  ├─ tts                  TTS 后端 + Media3 服务
│  └─ translate            翻译后端
└─ feature/
   ├─ library              书架、分组、搜索、导入
   ├─ reader               阅读页、目录、排版面板、选区菜单
   ├─ notes                标注与笔记列表、导出
   ├─ ai                   AI 对话面板
   └─ settings             设置（同步、AI 服务商、TTS、翻译、外观）
```

依赖方向：`feature → data → core`，`feature` 之间互不依赖，跳转通过 `app` 中的导航键完成。

## 4. 阅读引擎：复用 foliate-js

上游的阅读能力几乎都在 `assets/foliate-js`（约 9.7 万行 JS，含 pdf.js）中，与平台无关，**原样复用**。

| 上游（Flutter） | 原生实现 |
|---|---|
| `shelf` 在 127.0.0.1 起 HTTP 服务提供页面和书籍文件 | `androidx.webkit.WebViewAssetLoader` 拦截 `https://appassets.androidplatform.net/…`：`/reader/` 指向 assets，`/book/` 指向书籍文件（`ContentResolver` 流式读取），不开端口 |
| `flutter_inappwebview` 的 `callHandler(name, …args)` | 页面加载前注入兼容层 `window.flutter_inappwebview.callHandler`，底层用 `WebViewCompat.addWebMessageListener`（限定同源），返回 Promise；并派发 `flutterInAppWebViewPlatformReady` 事件 |
| Dart 侧 `evaluateJavascript('nextPage()')` 等 | Kotlin `ReaderController` 封装为挂起函数，内部用 `evaluateJavascript` |

**JS → 宿主的 32 个事件**按职责分组，映射为 `ReaderEvent` 密封类，由 `ReaderViewModel` 消费：

- 位置：`onRelocated` `onSetToc` `onSetProgressChapters` `onReaderChapterState` `onPushState` `onLoadEnd` `onReaderLoadError`
- 交互：`onClick` `onReaderTap` `onPullUp` `onImageClick` `onExternalLink` `onReaderShortcut` `onReaderWheelZoom` `onFootnoteClose`
- 选区与标注：`onSelectionEnd` `onSelectionCleared` `onAnnotationClick` `renderAnnotations` `onCustomHighlightStatus` `onQuickMark` `onQuickMarkError` `handleBookmark` `onReaderTextInteraction` `onReaderWordBounds` `onReaderParagraphWords`
- 功能：`onSearch` `translateText` `onTtsChapter` `onTtsProgress` `onAutoScrollStopped` `onPdfReadingSettings`

foliate-js 后续若需修改，只改 `native/reader/engine/src/main/assets/reader/` 下的副本，在改动处注明，并登记到该目录的 `UPSTREAM.md`。

**宿主实现中踩过的坑**（阶段 1 在模拟器上验证）：

- **WebView 必须是 `MATCH_PARENT`**。Compose `AndroidView` 默认给 `WRAP_CONTENT`，这时 WebView 的 `100vh` 为 0，整页排版塌成 0 高度。
- **导航拦截只拦主框架**。章节在 `blob:` iframe 中渲染，其 `Uri.host` 为 null，若一并拦截，章节永远加载不出来。
- **只保存真实的翻页**。`onRelocated.readingAction` 为 true 时（翻页、跳转、手动滚动）才写库。窗口尺寸变化（如退出时底栏出现）引起的重新分页不保存，否则位置会漂移。
- **保存点 CFI，不存区间 CFI**。foliate-js 用区间 CFI 表示一页，区间恰好从分页处开始时，恢复时会落到上一页。保存前用 `Cfi.collapseToStart` 收拢成起点；`paginator.js` 的 `uncollapse` 也做了相应修补。
- PDF 和 CBZ 的“章节”就是单页，所以页脚显示整本书的页码。
- **屏蔽系统选区工具栏**：`ReaderWebView` 让 ActionMode 的菜单保持为空，系统的「复制 / 全选」浮条就不会盖住应用自己的选区菜单，选区手柄照常可用。
- **重开时补报书签状态**：foliate-js 只在翻页时上报当前页有没有书签，而第一次翻页事件比标注加载得早。所以 `replaceAnnotations` 绘制完后会自行判断一次，并通过宿主专用的 `zenBookmarkState` 回调上报。在 `evaluateJavascript` 里 `import()` 必须用绝对 URL，因为脚本的基准地址是 `about:blank`。
- 标注表由 Room 从 v1 自动迁移到 v2，外键随书籍级联删除；绘制格式沿用上游的 `{id, value, type, color, note, hasReaderNote}`。

## 5. 数据与同步

- 数据库沿用上游的表语义，但重新命名并使用 Room 迁移管理。书籍文件放在应用私有目录，导入时复制或通过 SAF 持久授权引用。
- 同步引擎（`data:sync`）借鉴上游 `row_sync_engine / sync_merge / immutable_sync_log`：按行带时间戳和墓碑记录合并，传输层抽象为 `SyncTransport`，WebDAV 与 S3（自实现 SigV4，用 AWS 文档示例和上游 `@smithy/signature-v4` 向量做回归测试）各一个实现。
- **决定：不兼容 Modu 的远端格式**，用自己的、有文档的格式（下文）。Modu 的格式是整库 SQLite 归档 + 大量兼容分支，接管它的代价大于收益；版本号写在目录名和 `manifest.json` 里，以后不兼容的改动换 `v2` 目录。

### 5.1 数据库 v3（同步标识）

| 表 | 变更 | 用途 |
|---|---|---|
| `books` | 新增 `updated_at`（迁移时 = `created_at`） | 书籍元数据的时钟；阅读进度用已有的 `last_read_at` 作时钟 |
| `annotations` | 新增 `uuid`（迁移时为每行生成随机 UUID v4，唯一索引） | 标注的全局 id |
| `sync_tombstones(kind, sync_id, deleted_at)` | 新表 | 本机删除记录，供同步传播 |

书籍的全局 id 就是文件的 SHA-256（`file_hash`）。`BookDao.delete` / `AnnotationDao.delete` 在同一事务中写墓碑；删书时其标注随外键级联删除但不写墓碑，云端仍保留，同一文件再次导入时标注会回来。手写迁移 `MIGRATION_2_3`（唯一索引必须在回填之后创建，自动迁移做不到），由 Robolectric + `MigrationTestHelper` 测试覆盖。

### 5.2 远端布局

根目录是 WebDAV 地址，或 S3 的 bucket + 可选前缀：

```
ZenRead/v1/
├─ manifest.json          {"format":"zenread-sync","version":1,"createdAt":…}，首次同步时写入
├─ log/<sha256>.json      不可变的记录分段，文件名 = 内容的 SHA-256
├─ books/<sha256>.<ext>   书籍文件，文件名 = 文件内容的 SHA-256 + 主扩展名（epub/mobi/fb2/pdf/cbz）
└─ covers/<sha256>        封面图片，文件名 = 书的 SHA-256
```

分段格式（JSON，UTF-8）：

```json
{"format":"zenread-sync-segment","version":1,"device":"<uuid>","createdAt":1764200000000,
 "records":[
  {"kind":"book","id":"<sha256>","updatedAt":…,"device":"…","data":{"title":"…","author":"…","format":"EPUB","createdAt":…}},
  {"kind":"progress","id":"<sha256>","updatedAt":…,"device":"…","data":{"location":"epubcfi(…)","progress":0.42}},
  {"kind":"annotation","id":"<uuid>","updatedAt":…,"device":"…","data":{"book":"<sha256>","type":"HIGHLIGHT","cfi":"…","text":"…","chapter":"…","color":"F6C945","note":"…","position":0.3,"createdAt":…}},
  {"kind":"annotation","id":"<uuid>","updatedAt":…,"device":"…","deleted":true}
 ]}
```

### 5.3 合并规则

- 每行（`kind/id`）最后写入者胜：比较 `updatedAt`；相同时墓碑优先；内容相同视为一致；否则依次比较 `device`、规范化 JSON，保证每台设备选出同一个赢家。
- 标注的墓碑永远胜出（离线编辑不会复活已删除的标注；重新标注会生成新 uuid）。书籍是普通 LWW，所以删除后重新导入同一文件会恢复。
- 两台设备独立标注了同一范围（同书、同类型、同 CFI，uuid 不同）时，保留较新的（再比 uuid），另一条在所有设备上写墓碑。
- 未知 `kind` 的记录（来自更新的版本）照常参与合并和压缩，但不应用到本地。
- 应用远端记录时在一个事务里与当前本地行再比较一次，同步期间的本地修改不会被旧数据覆盖。

### 5.4 写入安全

- 分段内容寻址、只增不改，多台设备并发发布无需锁或 CAS；读取时校验 SHA-256，校验过的分段缓存在本机 `noBackupFilesDir/sync/<账号哈希>/segments/`，不再重复下载。
- WebDAV 写入先 PUT 到同目录的 `.zenread-<uuid>.part`，再 MOVE 到目标名（服务器不支持 MOVE 时退回直接 PUT）；S3 的 PUT 本身是原子的，并带签名的 `x-amz-content-sha256`，服务器会拒收损坏的上传。
- 分段超过 32 个时压缩：先发布包含全部记录（含墓碑）的新分段，再只删除本次读到的旧分段；并发发布的分段不会被删。读者遇到被删除的分段（404）会重新列目录。
- 书籍文件按需下载，下载到临时文件并校验 SHA-256 后再改名。任何一步中断，下次同步都会根据当前状态重新计算缺什么。

### 5.5 调度

`SyncManager` 用进程内互斥锁保证同一时间只有一次同步。WorkManager（均要求联网）：每小时一次、本地修改后 90 秒去抖（每次修改重置计时）、应用启动后 5 秒、同步进行中数据库有变化时之后再补一次；设置页的「立即同步」直接在应用作用域运行。坚果云的自动同步间隔至少 10 分钟，并在本机限制每 30 分钟 480 次请求；任何服务器返回 429/503 后按 Retry-After 或指数退避暂停。`SyncInitializer`（androidx.startup）在第一个 Activity 创建时启动调度，不改 `Application`。

## 6. AI 与 RAG

- LLM：统一的 `ChatProvider` 接口，覆盖 OpenAI 兼容（含 DeepSeek 等）、Anthropic、Gemini，使用 OkHttp + SSE 流式输出。不引入 langchain。
- RAG：章节文本由 foliate-js 抽取 → 分块 → 向量化（本地 ONNX 或在线 embedding API）→ 存入 SQLite（向量以 BLOB 保存，小规模直接暴力余弦检索；混合检索加 FTS5）。索引任务放在 WorkManager 中执行。
- 技能（总结、解释、角色分析…）的提示词从上游 `readany_skills.dart` 迁移成资源文件。

**阶段 5 实现说明**（`data:ai`）：

- 对外 API 是 `ChatClient`（`stream` / `complete` / `embed` / `isConfigured`），翻译等功能直接复用；设置页另用 `ProviderAdmin`（拉取模型列表、测试连接）。
- 对话记录存 `ai.db`；索引存 `ai_index.db`，用 `BundledSQLiteDriver`，因为平台 SQLite 不保证带 FTS5（bundled 版已确认启用 FTS5）。分块 id = `bookId * 1_000_000 + ordinal`，同时作为 FTS5 的 rowid。中文按单字 + 二元组写入 FTS5，不需要分词器。
- 全书文本由 `BookTextExtractor` 在离屏 WebView 中以普通阅读模式打开书，轮询到 `reader.view.book` 就绪后，逐个 section 调用 `createDocument()`，并用 `view.getCFI(index, range)` 为每个段落生成 CFI（与 foliate-js 搜索结果的做法一致），所以引用能直接跳转。不依赖页面渲染完成。
- 索引任务放在 WorkManager 中执行，通过 Hilt EntryPoint 获取依赖，不改应用的 WorkerFactory；任务被系统中断后会从已保存的分块和向量处续跑。

## 7. 路线图

| 阶段 | 内容 | 完成标志 |
|---|---|---|
| 0 骨架 | 多模块工程、约定插件、M3 Expressive 主题、导航框架 | 能编译运行，可在三个主标签页间切换 |
| 1 能读 ✅ | 书籍导入、书架、foliate-js 宿主与 JS 桥、翻页、目录、排版设置、阅读进度 | 能打开 EPUB 和 PDF 并记住阅读位置（已在 API 36 模拟器上验证）。TXT 尚未支持：上游是在 Dart 中先转换为 EPUB，需要另行移植 |
| 2 标注 ✅ | 选区菜单、高亮、笔记、书签、笔记页、导出 | 标注重启后仍在，能导出 Markdown（已在模拟器上验证） |
| 3 听书 | 系统 TTS、Edge TTS、在线 TTS，Media3 通知 | 锁屏后能继续朗读，并能在通知栏控制 |
| 4 翻译 | 选区翻译、段落对照翻译 | 可配置多个服务商 |
| 5 AI | 对话、技能、RAG 索引与检索 | 能针对整本书提问并给出引用位置 |
| 6 同步 | 同步引擎、WebDAV、S3、冲突处理 | 两台设备之间能同步进度、标注和书籍（单元测试覆盖两台设备经 WebDAV / S3 模拟服务器收敛；真机待验证） |
| 7 收尾 | 性能、无障碍、平板适配、删除 Flutter 代码 | 发布 release |

## 7.1 并行开发约定（阶段 3–6）

阶段 3–6 由多个子代理在各自的 git worktree 中并行开发，最后统一合并。为减少冲突：

- **各管各的模块**：TTS → `data:tts`，翻译 → `data:translate`，AI → `data:ai`，同步 → `data:sync`。各阶段的设置页放在 `feature:settings` 中，按领域分子包，例如 `com.zenread.feature.settings.tts`。
- **公共部件已经备好，不要另起炉灶**：HTTP 用 `core:network` 注入的 `OkHttpClient` / `Json`（需要不同超时就用 `newBuilder()`）；API Key 和密码一律存入 `core:datastore` 的 `SecretStore`；普通偏好用各自模块的 DataStore 文件。
- **主数据库**（`ZenReadDatabase`）只允许同步阶段修改（升到 v3）。其他阶段需要持久化时，在自己的模块里建独立的 Room 数据库，例如 AI 索引放在 `ai_index.db`。
- **共享文件只做追加**：`settings.gradle.kts`、`libs.versions.toml`、`ZenReadApp.kt` 里设置分类的 `when` 分支、阅读页的 `SelectionMenu` 按钮、`ReaderController` / `ReaderEvent` 的新方法和新事件，都只追加，不重排、不改动别人的代码。阅读页的新界面写在新文件里。
- **不连设备**：不使用 adb、模拟器或真机，设备验证由合并方统一做。纯逻辑要写单元测试，最后必须让 `./gradlew :app:assembleDebug test lintDebug` 全部通过。
- 多个构建会同时运行，请加参数控制资源：`./gradlew --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process …`

## 8. 构建

需要 JDK 17 以上，以及 Android SDK Platform **37.2**（Compose BOM 2026.11 要求 compileSdk ≥ 37.1）和 Build-Tools 37。`targetSdk` 仍为 36，`minSdk` 为 26。

```bash
cd native
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

## 9. 许可证

本项目复用了 foliate-js（MIT），并参照 ReadAny（GPL-3.0-or-later）的代码移植逻辑，因此整体仍以 **GPL-3.0-or-later** 发布。保留根目录下的 `LICENSE`、`LICENSES/`、`NOTICE` 和 `UPSTREAM.md`。移植过来的文件在文件头注明来源路径。

## 10. 听书（阶段 3）

- **模块**：`data:tts`。引擎接口 `TtsEngine`（`speak` 挂起到读完，取消即静音）有三个实现：系统 `TextToSpeech`（`SystemTtsEngine`）；Edge 与 OpenAI 兼容服务都实现 `SpeechSynthesizer`，由 `OnlineTtsEngine` 统一做预取（当前句起最多 3 句）和播放（内部 ExoPlayer 播 MP3）。Edge 的 `Sec-MS-GEC` 令牌移植自上游 `edge_tts_token.dart`（edge-tts 7.3.0，LGPL-3.0-only），用上游的测试向量校验。
- **文本来源**：沿用上游做法，句子由 foliate-js 的朗读游标提供（`ttsHere` / `ttsFromCfi` / `ttsNext` / `ttsPrev` / `ttsCollectDetails`），游标只由播放循环推进，预取只“看”不动。`evaluateJavascript` 不能等待 Promise，所以 `ReaderController.evaluateAsync` 让脚本把结果通过宿主专用的 `zenAsyncResult` 回调送回，由 `ReaderBridge` 在监听器里直接完成对应的 Deferred。在线引擎开启段落模式（`ttsSetParagraphMode(true, 240)`），系统引擎按句。
- **会话**：`TtsPlaybackController`（单例，全部在主线程）负责播放循环、音频焦点（暂时失去焦点后自动恢复，被其他应用接管时不恢复）、拔耳机暂停、唤醒锁和定时关闭；`TtsPlaybackService`（Media3 `MediaSessionService`）把它包装成 `SimpleBasePlayer`，通知栏、锁屏和耳机的上一首/下一首映射为上一句/下一句。偏好存在独立的 DataStore 文件 `tts_settings`，OpenAI 密钥存在 `SecretStore` 的 `tts.openai.apiKey`。
- **息屏后继续朗读**：WebView 是唯一的文本来源，所以靠前台媒体服务保住进程；应用从不调用 WebView 的 `onPause` / `pauseTimers`，JS 照常执行。进入后台时调用 `ttsSetBackground(true)`，页面不再绘制高亮和翻页，只推进游标；foliate-js 跨章时用 `createDocument` 离屏解析下一章（EPUB、MOBI、FB2、PDF 都支持），不依赖布局和 `requestAnimationFrame`。朗读期间把渲染进程优先级设为 `IMPORTANT`，不可见时也不降低。进度由 `onTtsProgress` 保存，因为后台页面不会触发 `onRelocated`。
- **已知限制**：退出阅读页会停止朗读（文本来源随 WebView 一起销毁）；旋转屏幕、切换深色模式等配置变更时会话先暂停，新页面就绪后从当前句继续（10 秒内没有重新接上就停止）；从最近任务中划掉应用会停止朗读。系统引擎不会按书籍语言自动选音色，未选音色时使用系统默认音色。
