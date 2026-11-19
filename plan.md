# ReForgedPlay 自定义目录与 RAMDisk 支持计划

> 状态（剩余存储需求统一收尾已交付可测试候选；EXR已知不可用、真实游戏未验收）：A→B→C1→C2a→C2b→C3→C4→D顺序完成，六目录/两开关UI、raw可关闭、事务保存恢复/cache/native路径已实现；D两窄修及唯一完整build54s、最终JAR生产核心回归通过。最终23Java＋两语言＋计划，收据与限制见15.13。D首UI误写旧C4样本子树的流程偏差已披露，不能称旧证据全部未动。停止实现、不Git写/安装/真游戏，交用户小回放验收；目标控制面当前disarmed，本后台通知回合complete被拒，未假称目标历史已删除。
> 用户目标：独立配置 cache、raw、temp、tmp、record 等目录，支持绝对路径及相对于游戏目录的路径；将可丢弃缓存和临时文件放在 RAMDisk，用完清理，减少实体硬盘写入。没有已报告故障，本次属于预防性兼容与容错设计。

## 1. 项目基线与证据边界

- Minecraft 1.21.1、NeoForge 21.1.82：[gradle.properties](<gradle.properties#L6-L20>)。
- Architectury Loom 1.7-SNAPSHOT、Java 21：[build.gradle](<build.gradle#L1-L11>)；Gradle 8.8：[gradle-wrapper.properties](<gradle/wrapper/gradle-wrapper.properties#L1-L7>)。
- 不更换加载器、映射、Java、Gradle 或依赖版本，不处理与存储无关的问题。
- 历史审查完成过主代理源码检查及只读队员源码/JAR 字节码检查；当时记录的 Minecraft 技能/专用阶段子代理/mc_gradle 缺失仅是历史状态，不再是当前阻塞。
- 当前主代理报告：Gradle transform 缓存已修复，完整基线 build 成功，用时 6m3s，工作区 clean；本 A 代理不重复构建，不把主代理报告冒充本轮实测。前一 A 代理失败，不依赖其未完成产物。
- 当前 A 已读取本机 minecraft-neoforge-mod 与 minecraft-java-build 匹配技能，专用阶段工具及 mc_gradle 已可见；既有 Architectury Loom 栈优先，不套用技能的新项目 ModDevGradle 模板，也不 scaffold、不升级依赖。
- 历史初始 `git status --short` 为空；历史 `src/test/**/*` 未找到文件，不据此宣称整个项目绝无测试。本批禁止新增/修改仓库测试，隔离验证不写入仓库。

## 2. 已确认现状

| 范围 | 源码证据与行为 | 设计影响 |
| --- | --- | --- |
| 已有设置 | [Setting.java](<src/main/java/com/replaymod/core/Setting.java#L5-L6>) 已有 `recordingPath`、`cachePath` | 保留旧键；`recordingPath` 实际表示正式回放根目录，不改成录制暂存目录 |
| 固定子目录 | [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java#L23-L47>) 将 raw、recording 固定在正式目录下 | 补充独立路径解析，而非重复实现已有功能 |
| 缓存身份 | [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java#L59-L69>) 对正式根目录与回放路径做 `relativize` | 不同 Windows 盘符无法相对化。仅把 cache 放在另一盘并不必然触发；将 raw/工作回放放到其他盘后必须处理 |
| 文件属性 | [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java#L46-L54>) 设置 `dos:hidden`，失败并非致命 | 隐藏属性不能作为 RAMDisk 可用性前提，不把这里描述成已发生的崩溃 |
| raw 内容 | [MarkerProcessor.java](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java#L132-L139>) 先移动原件到 raw；[PacketListener.java](<src/main/java/com/replaymod/recording/packet/PacketListener.java#L265-L285>) 无剪辑输出时仍保存完整录制 | raw 是原始回放备份，不是一次性缓存 |
| 恢复数据 | [RestoreReplayGui.java](<src/main/java/com/replaymod/core/gui/RestoreReplayGui.java#L116-L120>) 先提交未保存文件，避免覆盖未提交包数据 | `.mcpr.tmp` 可能是唯一可恢复副本，不能按名字清理 |
| 跨盘路径 | [MarkerProcessor.java](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java#L137>)、[GuiSavingReplay.java](<src/main/java/com/replaymod/recording/gui/GuiSavingReplay.java#L156-L166>)、[ReplayFilesService.java](<src/main/java/com/replaymod/core/files/ReplayFilesService.java#L73-L103>) 直接移动文件或恢复目录 | 不假定跨盘移动原子，也不假定非空目录可直接跨盘搬移 |
| 现有清理 | [ReplayFilesService.java](<src/main/java/com/replaymod/core/files/ReplayFilesService.java#L113-L184>) 对 raw 保留 21 天、cache 保留 7 天或对应回放不存在即清理、已拒绝恢复材料保留 2 天 | 默认保留兼容；即时清理须显式启用且按数据类型处理 |
| native 混用 | [Lwjgl3Loader.java](<src/main/java/com/replaymod/render/utils/Lwjgl3Loader.java#L32-L44>) 将 natives 放在 cache 下；上述清理按回放缓存解释顶层子目录 | 回放缓存与进程级 native 隔离，不能误清理占用中的库 |
| 关闭与锁 | [ReplayFilesService.java](<src/main/java/com/replaymod/core/files/ReplayFilesService.java#L37-L70>) 构造异常只捕获 IOException；[ManagedReplayFile.java](<src/main/java/com/replaymod/core/files/ManagedReplayFile.java#L16-L21>) 仅底层 close 成功后回调 | 覆盖构造失败、关闭失败、重复关闭，不能在 finally 强删仍占用文件 |
| 热更新 | [SettingsRegistryBackend.java](<src/main/java/com/replaymod/core/SettingsRegistryBackend.java#L102-L139>) 支持配置重载 | 每项任务固定路径快照，不能保存途中改变目标盘 |

### ReplayStudio 的关键限制

只读审查使用 `javap -p -c` 检查 [本地 ReplayStudio JAR](<libs/ReplayStudio-master-SNAPSHOT-slim.jar>) 的 `ZipReplayFile`。以下是方法/字节码偏移，不是源码行号：

1. 四参数构造最后一项是 cache，不是 tmp；偏移 39–72 将 `.tmp` 固定为 output 同级的 `output.name + ".tmp"`，随后使用 changed、removed、source 等内部路径。
2. `save()` 偏移 32–94：在默认系统临时目录创建文件，调用 `saveTo`，调用 `close()`，删除旧 output，最后将临时文件移到 output。
3. `close()` 偏移 83–88 删除 tmpFiles；`saveTo` 直接写目标 ZIP，不提供本计划要求的事务发布。
4. 因而存在恢复暂存与旧输出已删除、新文件尚未搬回的窗口。系统 temp 若在 RAMDisk，此时唯一新副本可能在易失存储，异常后不能假定原件还在。
5. [build.gradle](<build.gradle#L86-L87>) 使用动态 `master-SNAPSHOT-slim` 坐标。本地 JAR 不等于已验证的实际解析产物；实施第一步须确认编译、打包实际使用的类及语义。

**必须先避开或适配旧提交顺序，不能只增加路径配置，也不能把第四构造参数当 tmp。** 不偷偷修改依赖 JAR，不通过设置 JVM 全局 `java.io.tmpdir` 冒充实现。

## 3. 拟议目录模型

键名待批准。保留旧正式目录键，不另设含义冲突的 record 键。

| 用户概念 | 键 | 未设置时 | 推荐策略 |
| --- | --- | --- | --- |
| record / 正式回放 | 现有 `recordingPath` | `./replay_recordings/` | 实体盘；不参与临时文件清理 |
| cache | 现有 `cachePath` | `./.replay_cache/` | 可用 RAMDisk；可重建回放缓存最后一个使用者释放后删除 |
| raw | 新增 `rawPath` | 空值继承正式目录下 raw | 实体盘；保留原始回放及原 21 天策略，不即时清理 |
| 录制工作目录 | 新增 `recordingWorkPath` | 空值继承正式目录下 recording | 默认实体盘；保存失败保留恢复数据 |
| temp / 普通临时数据 | 新增 `tempPath` | 空值沿用系统临时目录选择，在其下创建模组专属任务空间 | 可用 RAMDisk；图片转换、资源包解包等真正用完后清理 |
| tmp / 未提交工作区 | 新增 `tmpPath` | 空值兼容旧的同级恢复布局 | 默认实体盘；成功提交后清理；独立指定需通过第 5 节的适配门槛 |

配套规则：

- 绝对路径直接解析；相对路径固定相对游戏运行目录，不依赖 shell 工作目录。不默认展开 `%TEMP%`、`~` 等表达式。
- 空值表示继承，不能误解析成游戏根目录再清理；未配置新键时保持原路径和行为。
- 任务开始时固定完整布局，配置热更新仅作用于新任务；不自动迁移或删除旧目录。
- 检测路径同一性、祖先/后代覆盖和链接别名；缓存清理范围不得覆盖正式录制、raw、恢复工作区。允许共用父目录，但可清理范围必须是归属明确的专属子目录。
- 缓存身份不能只依赖文件名或跨盘相对路径。采用规范化逻辑回放身份及受控索引/摘要，处理同名文件、路径别名与旧缓存格式；不盲目删除无法识别的旧缓存。
- RAMDisk 是用户已挂载的普通文件系统目录，不创建/格式化盘、不安装驱动、不凭盘符识别易失性。
- 建议新增 `ephemeralCache`，默认 false 保持旧保留行为；本次推荐 RAMDisk 配置开启它。先复用设置注册，不扩大为新设置 GUI。

**推荐：cache + 普通 temp 放 RAMDisk；raw + tmp + 录制工作目录 + 正式回放放实体盘。** 用户仍可自选路径，但把未提交数据也放 RAMDisk，必须明确接受断电/卸载丢失。不能将“tmp 可以配置”解释成“tmp 可以随时删”。

## 4. “用完就删”的生命周期

按数据使用者与恢复价值判断，不按扩展名或单个函数返回判断：

- 图片转换：[Image.java](<src/main/java/de/johni0702/minecraft/gui/versions/Image.java#L94-L101>) 接入可注入临时目录提供者，避免通用 GUI 库直接依赖模组单例；写流完成后清理，清理异常不覆盖原始错误。
- 回放资源包：[FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java#L271-L287>) 改用任务临时目录；[终止路径](<src/main/java/com/replaymod/replay/FullReplaySender.java#L350-L363>) 须确认异步使用者与资源包句柄释放，不能仅发出关闭请求就删除。
- 视频元数据：[MetadataInjector.java](<src/main/java/com/replaymod/render/metadata/MetadataInjector.java#L95-L140>) 会复制完整视频到系统 temp，再写回原视频。定制 temp 必须考虑容量，并避免覆盖原视频失败后在 finally 删除唯一备份。
- LWJGL JAR/native：[Lwjgl3Loader.java](<src/main/java/com/replaymod/render/utils/Lwjgl3Loader.java#L24-L44>) 与 [JAR 临时创建](<src/main/java/com/replaymod/render/utils/Lwjgl3Loader.java#L96-L108>) 属于进程/类加载器生命周期。Windows 下 native 可能持续占用，不能承诺每次渲染后立即删除；安全释放后或下次启动确认旧实例结束后处理。
- 回放缓存进行实例/任务隔离和使用者管理，最后一个使用者退出后清理。预览关闭不得删掉播放仍使用的缓存。
- 删除仅限本模组本任务拥有且无恢复价值的文件；不删用户指定根目录，不跟随符号链接/Windows junction，不因名称相似认定归属。
- 启动清理必须证明目录归属和非活跃状态，多实例不能只依据时间戳判断。若文件系统不支持所需锁定能力，保守跳过可疑目录并报告。
- 清理失败不把已经成功保存的录制改判为保存失败；记录遗留位置，受控重试。不靠 deleteOnExit/退出钩子承诺崩溃清理。

## 5. 事务保存、跨盘与恢复

### 5.1 安全发布协议

**准备新文件 → 关闭写入句柄 → 校验 → 发布 → 更新状态 → 才清理源数据。**

- 在目标盘受控位置生成独立发布暂存文件，避免最后一步仍依赖 RAMDisk 上的唯一副本。
- 校验包括容器可打开、该录制模式的必要条目/元数据、复制完整性；样本验收还需实际回放。不能只检查文件存在或长度相同。
- 可用时使用目标文件系统原子操作；不支持时采用保留旧版/源数据的恢复协议，不能退化为先删目标再 move。跨盘移动不是必然失败，但也不是天然原子。
- 新录制同名默认不覆盖，发布阶段处理并发冲突，不能仅 exists 后写入。编辑已有回放的替换场景保留旧版至新版本确认发布。
- 发布失败保留原件、恢复材料和可定位的新暂存，不提示保存成功。句柄释放和数据删除分离，尤其防止底层 close 顺手删除唯一恢复数据。
- 整份跨盘复制不搬到 GUI 线程；在现有任务边界完成必要异步处理，不重构全项目线程模型。
- `dos:hidden`、属性复制、原子移动、空间查询等能力不能无条件要求支持；仅对可选能力不足降级，真实读写错误不能吞掉。空间预检不替代实际 IO 错误处理。

### 5.2 ReplayStudio 适配门槛

优先评估模组侧事务适配层：逻辑回放路径与物理工作路径分离，通过已验证的 `saveTo` 写目标盘暂存，再由发布服务提交；自定义 tmp 使用受控工作输出布局，让底层同级 `.tmp` 落在指定工作区。

实施前必须证明：

1. 实际依赖的构造、saveTo、close 语义已核实，资源释放满足发布需要。
2. 支持同一 ReplayFile 多次 save 后继续读取/编辑，不能只支持录制结束即关闭。
3. 已有未提交 sidecar 完整接入，不能只复制旧 ZIP 而丢失未提交数据。
4. 逻辑 output、工作 output、缓存身份与恢复定位一致；必要时用小型任务描述持久记录目标，不只存在内存。
5. 保存失败时先保住恢复材料，再安排底层 close；不能套用盲目 finally close。
6. 重启能识别新工作布局，且继续兼容旧同级 `.mcpr.tmp` 恢复。

如果无依赖改动的适配不能满足上述条件，停在门槛处提交具体限制和新方案，请用户决定。不得悄悄替换依赖、设置全局临时目录或交付实际不生效的 tmp 配置。

### 5.3 raw 与启动恢复

- raw 备份走可靠跨盘发布；确认完整原件备份后，才移除原位置或开始破坏性后处理。
- 不先盲目跨盘移动非空恢复目录再期待旧扫描器发现；扫描明确理解旧布局与新的受控任务布局。
- 保留 `.no_recover`、`.del` 语义，但标记创建/删除须对应真实提交状态，不能因无剪辑输出就提前认定 raw 已保存。
- 用户拒绝恢复与普通缓存过期是不同状态，不共享即时清理策略。
- 配置更改后不保证自动发现任意历史路径；通过受控任务记录或明确恢复入口处理，不无界扫描全部磁盘。

## 6. RAMDisk 异常与能力边界

- 建议默认不静默回退实体盘，避免违背减少写入的目标；这是待用户批准的产品策略。
- 可重建缓存失败：只有调用方确实支持无缓存/重算才降级，否则中止相关操作并说明。不能假定所有底层缓存访问均可禁用。
- 普通 temp 失败：终止依赖它的操作，保留已有正式成果；实体盘回退如需提供，按用途显式开启并报告实际落点。
- 关键录制/保存失败：停止相关写入，提示原因、实际路径、恢复材料位置，不假装成功。
- 不承诺断电不丢数据。RAMDisk 丢失后不能从已经消失的未提交内容恢复；实体盘也受驱动写缓存和文件系统影响。
- 中途持久化检查点会增加实体盘写入，本次不默认加入；RAMDisk 也会占用系统内存，容量不足和内存压力需有明确诊断，不能靠无限重试掩盖。

## 7. 候选修改范围与阶段

以下不是当前代码修改授权。新增组件名称在依赖核实后收敛，实施前确认精确文件白名单。

| 阶段 | 候选文件/组件 | 内容 |
| --- | --- | --- |
| P0：核实保存协议 | [build.gradle](<build.gradle#L86-L87>)、[本地 JAR](<libs/ReplayStudio-master-SNAPSHOT-slim.jar>)，仅只读 | 核对实际解析类及适配可行性，不升级依赖 |
| P1：路径模型 | [Setting.java](<src/main/java/com/replaymod/core/Setting.java>)、[ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>)；拟新增布局/任务上下文 | 路径键、校验、快照、缓存身份和命名空间 |
| P2：事务与恢复 | [ReplayFilesService.java](<src/main/java/com/replaymod/core/files/ReplayFilesService.java>)、[ManagedReplayFile.java](<src/main/java/com/replaymod/core/files/ManagedReplayFile.java>)、必要时 [DelegatingReplayFile.java](<src/main/java/com/replaymod/core/files/DelegatingReplayFile.java>)、[RestoreReplayGui.java](<src/main/java/com/replaymod/core/gui/RestoreReplayGui.java>)；拟新增发布/工作区适配组件 | 安全提交、失败留存、跨盘恢复、锁与清理分离 |
| P3：调用方接入 | [ConnectionEventHandler.java](<src/main/java/com/replaymod/recording/handler/ConnectionEventHandler.java>)、[PacketListener.java](<src/main/java/com/replaymod/recording/packet/PacketListener.java>)、[MarkerProcessor.java](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java>)、[GuiSavingReplay.java](<src/main/java/com/replaymod/recording/gui/GuiSavingReplay.java>)；按核查决定 [GuiEditReplay.java](<src/main/java/com/replaymod/editor/gui/GuiEditReplay.java>)、[GuiReplayViewer.java](<src/main/java/com/replaymod/replay/gui/screen/GuiReplayViewer.java>) 是否需改 | 发布统一；IO 失败不误报成功/继续破坏性后处理 |
| P4：临时文件接入 | [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>)、[MetadataInjector.java](<src/main/java/com/replaymod/render/metadata/MetadataInjector.java>)、[Lwjgl3Loader.java](<src/main/java/com/replaymod/render/utils/Lwjgl3Loader.java>)、[Image.java](<src/main/java/de/johni0702/minecraft/gui/versions/Image.java>)；必要时 [ReplayMod.java](<src/main/java/com/replaymod/core/ReplayMod.java>) 注册提供者 | temp 定向、native 生命周期、即时清理、启动遗留处理 |
| P5：验证 | 待单独批准的测试位置与命令 | 不预先修改测试、配置、依赖 |

只处理本需求直接相关的 IO 分支；不顺手优化无关功能。设置注册源码与用户实际配置文件分开授权；实际游戏配置仍不代改。新增文案、本地化、README 变更也另列范围。

## 8. 验证矩阵与完成标准

下表是未来验收，不是已经通过的测试。

| 场景 | 预期 |
| --- | --- |
| 默认配置/旧布局 | 原录制、列表、回放、编辑、恢复正常；不重命名旧键、不误删旧缓存 |
| 路径组合 | 绝对/相对/中文/空格/不同盘符/正式目录外工作路径可用，非法路径明确报错 |
| 路径安全 | 重叠目录、链接/junction、同名目录、无关文件不被误删 |
| 跨盘 | 完整回放、raw、恢复目录、正式发布均按协议处理；同名并发不覆盖 |
| 故障注入 | 无权限、空间耗尽、盘符消失、占用文件、属性不支持、原子操作不可用时正确保留数据 |
| 中断点 | 复制、校验、发布、清理各阶段前后终止隔离测试进程，重启识别状态；非易失源数据不提前消失 |
| 异步保存 | 写队列未结束或写入失败时不提前关闭并宣称成功；只调整相关 IO 提交分支 |
| 生命周期 | 正常、取消、异常、重复关闭、关闭失败均可解释；句柄释放后清理，不删除正在使用的 native |
| 多使用者/多实例 | 预览和播放并存、两个客户端并存时不相互删缓存或任务目录 |
| 依赖契约 | 连续 save/saveTo、读取、编辑、关闭、恢复均符合实际 ReplayStudio 行为 |
| 实际成果 | 正式回放能播放、时长/关键事件正确；资源包、图片转换、视频元数据路径正常 |
| 减少写入 | 隔离观察本模组 cache/temp 的实际落点和回收；不承诺系统或其他模组零写盘 |

执行约束：

- 实施后必须构建成功才报告完成。使用专用 mc_gradle，任务参数保留用户要求 `build -Djava.io.tmpdir=C:\Temp`；这是构建参数，不是运行时目录功能的实现。
- 历史上曾缺少 mc_gradle；当前专用工具已恢复。A 不构建，D 按第 11 节用 mc_gradle 验证，不通过改构建文件绕过环境问题。
- 尚未获测试代码授权或指定测试命令，实施前确认。无自动化测试授权时如实列出手工证据及缺口，不虚构输出。
- 故障注入仅在获授权的隔离样本上进行，不卸载用户在用 RAMDisk、不杀真实录制进程、不灌满真实磁盘。
- 每阶段提供真实 diff 与实际执行验证的输出；不做 git add/commit/push/reset/checkout 等写操作。

## 9. 待确认决策

1. 采用上述数据分级：cache/temp 优先 RAMDisk；raw/tmp/录制工作目录默认实体盘；正式目录沿用旧键。
2. 新增路径键和显式即时缓存清理开关，默认兼容旧行为，推荐 RAMDisk 配置开启即时清理。
3. 不可用默认明确失败或仅安全降级，不静默回退实体盘。
4. 下一阶段精确源码授权；适配门槛若要求改变依赖，先停下提交新方案。
5. 测试代码权限和测试命令；未确认前不新增测试、不改用户配置或依赖。

## 10. 本轮交付说明

交付仅为本计划，包含源码证据、目录语义、安全协议、候选文件范围与验收矩阵。未实施功能、未编译、未运行游戏、未做真实 RAMDisk 测试。本计划不等于已经支持 RAMDisk，也不为没有错误日志的环境编造故障根因。

优先级：先解决保存/恢复的数据安全门槛，再完成自定义目录与即时清理，不能以路径能改代替功能完成。

审查队员原话：减少硬盘写入可以做，但应先把“可重建缓存”和“尚未保存的唯一回放”分开；名字叫 tmp，不代表里面的数据可以丢。

主代理想说：我更希望这次改完后，省下的是重复写入，而不是把一次珍贵录制的恢复机会也省掉。

## 11. 当前第一批：已关闭回放安全搬移基础（A → B → C → D）

### 11.1 目标与现状

本节是本批实施白名单和验收门槛；上文是长期路线/历史审查，不是越批授权。仅替换 MarkerProcessor 将完整、已关闭原件移入 raw 的步骤。旧命名规则保持 name.mcpr、name.1.mcpr、name.2.mcpr。成功返回意味着 raw 完整、不覆盖既有目标、源已删除，然后才允许生成剪辑输出；失败中止后处理，至少保留原件或已确认完整的 raw。

已重读 [构建脚本](<build.gradle#L1-L11>)、[版本属性](<gradle.properties#L6-L20>)、[wrapper](<gradle/wrapper/gradle-wrapper.properties#L1-L7>)、[NeoForge 元数据](<src/main/resources/META-INF/neoforge.mods.toml#L1-L28>)。保留 Minecraft 1.21.1 / NeoForge 21.1.82 / Architectury Loom 1.7-SNAPSHOT / Java 21 / Gradle 8.8 / 现有 layered Yarn 映射。这是现代 Java 21 线，但不是要迁移为 ModDevGradle 的项目；并存 fabric.mod.json/mcmod.info 不改变 loom.platform=neoforge。没有新增 Maven 坐标，无需更换现有配对。

**不碰** ReplayStudio save/saveTo/close 实现、.mcpr.tmp、未关闭录制、PacketListener 无输出 saveTo(raw) 分支、路径设置、缓存身份/清理、恢复扫描、资源包/native/temp、GUI 线程模型。本批只搬已经完成的文件，不宣称修复底层提交窗口或实现自定义 RAMDisk 目录。

### 11.2 精确最小白名单及所有权

除下表外不得写仓库文件；B/C/D 顺序交接，不并行修改同一文件。

| 文件 | B（最小框架） | C（实现） | D（审查/验证） |
| --- | --- | --- | --- |
| [plan.md](<plan.md>) | 只读 | 只读 | 只读，结论回报 Lead |
| 拟新增 src/main/java/com/replaymod/core/files/ReplayFileOperations.java | package、final 类、私有构造、一个有明确契约的静态入口；不能返回假成功 | 实现算法与少量私有辅助方法，不建泛化框架 | 仅在该文件修本批问题 |
| [MarkerProcessor.java](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java#L132-L139>) | 不改 | 仅替换 raw 选名/Files.move 及必要 import，以返回 Path 作为 inputPath | 仅修该接入局部 |

建议自有接口：`public static Path moveToRaw(Path source, Path rawFolder) throws IOException`。这是本项目新增接口设计，不是外部现有 API。内部处理原名和编号；只用 JDK 21，不依赖 MC/ReplayStudio，便于外部独立验证。不新增策略对象、全局服务、持久任务格式、重试框架、配置键、测试钩子或文案。B 若有占位实现须明确抛错，C 完成后才交 D 构建。

**GuiSavingReplay 本批不接入、不列白名单。** [applyOutput](<src/main/java/com/replaymod/recording/gui/GuiSavingReplay.java#L139-L166>) 是 GUI 回调；直接接入大文件复制会阻塞主线程，留第二批单独设计异步 UI。MarkerProcessor 两个调用点均为工作线程：[录制结束](<src/main/java/com/replaymod/recording/packet/PacketListener.java#L246-L306>)、[编辑应用](<src/main/java/com/replaymod/editor/gui/GuiEditReplay.java#L152-L178>)。二者仅只读核查，不修顺路发现的其他问题。

### 11.3 简洁算法与发布能力取舍

**采用硬链接创建新目录项作为本批不覆盖发布原语。** ATOMIC_MOVE 即使不带 REPLACE_EXISTING，也不能通用保证目标存在时不覆盖。硬链接是可选能力；不支持时明确失败并保留源，不静默降级为有覆盖竞态的 rename、不安装库。无硬链接的 FAT/exFAT/RAMDisk 若也要求成功，先停下另议非原子协议，不由 C/D 自行扩范围。这是显著兼容限制，不可隐藏在“跨盘支持”宣传后面。

1. 输入契约：调用方已保存、关闭回放并不再写原件。source 必须是普通文件；拒绝目录、符号链接和源目标相同目录项。NOFOLLOW_LINKS 读源属性，记 fileKey/size/mtime；无法建立可比较文件身份时保守拒绝破坏性搬移。rawFolder 沿用现有 folders 服务，不改变目录设置；父目录应为可信受控目录，不把这些检查说成防恶意 junction/父路径置换的沙箱。
2. 同盘可选快速路径：同 provider 且 FileStore 比较提示同盘时，直接 `Files.createLink(candidate, source)`。这是同一完整文件的新名字，无大文件复制；成功后确认 isSameFile、源快照，再删除源。能力错误直接退出，不按错误字符串猜跨盘。FileStore 比较仅为优化提示，不是能力保证；也可本批先统一使用下面暂存流程，避免复杂优化。
3. 其他情况：在 rawFolder 内 createTempDirectory 创建独占 `.replaymod-move-...`，向其中不存在的 payload.part 流式 Files.copy 整个原件。不用系统 temp，不整文件读入内存，不覆盖文件。复制完成并关闭句柄后，复核源快照并要求 `Files.mismatch(source, staged) == -1L`；只比较长度不足。本批不解析/修复 ZIP 必要条目，“源本来有效完整”是输入前提；实际回放另验。
4. 用 `Files.createLink(candidate, staged)` 发布完整 raw，并确认 candidate/staged 是同一文件。名字竞争以创建动作裁决；仅明确 FileAlreadyExistsException 时按原规则换编号，复用同一完整暂存，不每次重复制。不用 exists 当并发保证；其他 IO 错误一律中止，编号溢出报错，不能无限重试或吞掉权限错误。
5. 发布确认后先移除 stage 的 payload 目录项与空暂存目录，再复核 source 身份/大小/mtime，最后 Files.delete(source)。**只有源删除成功才正常返回**，防止 MarkerProcessor 随后在原路径写输出覆盖未移除原件。同盘路径没有 stage，source/raw 两个硬链接只短暂共存，不能作为长期共享可写回放。
6. 从不先删源，从不删除已发布 raw“回滚”，不用 REPLACE_EXISTING/ATOMIC_MOVE。无需 COPY_ATTRIBUTES/隐藏属性作为成功前提；跨盘时间戳/ACL 完全复制不是本批承诺，保留周期策略本身不改。

独占目录令未完成 payload 不暴露成正式 .mcpr，名字不以 .mcpr.cache 结尾；现有 raw 清理的 Files.delete 不会递归清空非空遗留目录。这不是恢复协议，不新增遗留扫描器，不能据此宣称历史清理全都安全。正常成功不留 stage。

### 11.4 错误语义与边界

- 复制/校验/发布前出错：抛 IOException；UnsupportedOperationException 转为带 cause 的 IOException。源不删，调用方不继续剪辑。保守留下本次 stage，异常列阶段、source、stage、candidate，不盲目 finally 递归删除。冲突文件永不删除。
- 发布后 stage 清理/源删除失败：抛“raw 已发布，搬移未完成”的 IOException，包含 raw 与 source 实际位置，**保留已发布 raw 并中止剪辑**。两份副本可并存，不能让异常被误读为“raw 没保存”或自动重新剪辑。清理失败不能触发删除唯一完整副本；不假称整个底层 save 失败。
- 最后删源之后才返回，后面不再执行会把成功改判失败的清理。任何失败分支不能删唯一已确认完整文件。
- 文件已关闭/独占是调用契约，Java 属性快照、exists、isSameFile、FileStore 都不是锁。source 或 raw 在检查后被外部替换/改写仍有 TOCTOU 窗口；无法用两个文件绝对防恶意并发，本批不引入跨进程锁框架。mismatch 不是原子快照，属性检查也不能发现所有改写。
- createLink 是可选操作；Oracle 没有为所有 provider 承诺通用崩溃事务或目录持久化。默认 provider 的创建不覆盖行为交 D 实测；网络盘、自定义 provider、权限不足、无硬链接 RAMDisk 均可安全失败。FileAlreadyExistsException 是 optional specific exception，无法识别为该类型的失败就停止，不猜名字冲突。
- 不承诺 fsync/目录 flush/断电不丢；RAMDisk 消失仍可能丢数据。复制需要目标容量，空间预检不能替代真实 IO。源关闭状态也不能由工具可靠探知，不能宣传为适用于任意正在录制的文件。

### 11.5 Java API 核实（2026-11-10）

已搜索并读取 Oracle JDK 21 正文；没有新增 NeoForge/MC API 或 Maven 坐标。以下实际文件系统能力仍须验证，引用文档不等于本机已测。

| 已核实 API/事实 | 来源与用途 |
| --- | --- |
| Files.createLink(Path link, Path existing) throws IOException | 新目录项/硬链接，可选能力，同文件系统/权限限制；已有目标可抛 FileAlreadyExistsException。[Oracle createLink](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#createLink(java.nio.file.Path,java.nio.file.Path)>) |
| Files.createTempDirectory(Path dir, String prefix, FileAttribute<?>... attrs)；Files.copy(Path source, Path target, CopyOption... options) | 指定目标盘 stage；copy 非原子，异常可留部分文件。[Oracle Files](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html>) |
| Files.mismatch(Path path, Path path2) throws IOException | 无字节差异返回 -1L，但不是与其他活动原子隔离的校验。[Oracle mismatch](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#mismatch(java.nio.file.Path,java.nio.file.Path)>) |
| Files.readAttributes(Path, Class<A>, LinkOption...)；Files.isSameFile(Path, Path)；Files.getFileStore(Path)；Files.delete(Path) | 属性/身份复核与快速路径提示；打开句柄可令删除失败。[Oracle Files](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html>) |
| Files.move(..., ATOMIC_MOVE) | 其他选项被忽略；已有目标替换还是失败由实现决定；普通非原子 move 异常后状态可能未定义，不能拿来保证通用 no-clobber。[Oracle move](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)>) |

**UNVERIFIED 交 D**：本机/目标卷硬链接能力、文件身份可用性、实际并发不覆盖、错误留存、游戏内可播放结果。无需核实 ReplayStudio 新调用签名，因为本批不增加该类调用；第 5.2 节留后批。

### 11.6 D 验证顺序与停点（A 不执行）

1. 只读检查 diff/未跟踪文件，确保只在两份源码与本计划白名单内。禁止 Git 写、改配置/依赖/构建/wrapper/已有测试/.env、安装包、访问 whale-craft。现有栈优先于技能模板，不 scaffold。核对 MarkerProcessor 仅成功返回后继续剪辑，两个调用线程不变，GUI 未接入。
2. 优先在**仓库外获准的临时目录**写 Java 21 隔离 harness，只编译新增纯 JDK 类与 harness；.class/样本/报告全部放外部，不能在源码旁产生 .class。A 不写 harness。本批不新增仓库测试；如需新测试文件须先请求用户，不为可测试性加入生产策略框架/故障注入 API。
3. 外部样本至少覆盖：同盘中文/空格路径、已有同名目标内容不变及编号、两个独立源竞争同候选、随机大字节与零字节精确搬移、目录/符号链接/不存在源拒绝。零字节/random 只证明字节搬移，不冒充有效回放。外部可安全模拟时覆盖源变化、stage 清理/源删除失败、能力不支持；不能可靠制造的故障列为未测，不伪造通过。
4. 跨盘分支需两个**已存在且允许使用**的卷分别建隔离目录；不创建/卸载 RAMDisk、不灌满真实盘、不修改真实配置或仓库 ACL、不触及真实唯一录制。无第二卷或目标无硬链接时如实报告验证限制/安全失败，不能把同盘测试写成跨盘通过。
5. harness 后用专用 mc_gradle，projectDir=C:/Users/zhenz/Desktop/ReForgedPlay-1.21：可先 task=compileJava，args=["-Djava.io.tmpdir=C:\\Temp"]，timeoutMs=900000；最后必须 task=build，同一 args，timeoutMs=1200000（20 分钟）。保留用户指定构建 tmp 参数，不 clean、不 refresh-dependencies、不排除 test，不为错误改构建。基线 6m3s 仅作超时参考。已有测试可随 build 执行，但不能新增/修改测试。记录真实退出码、任务摘要、产物；专用工具若故障如实报告。
6. 在已有运行许可及可复制样本下，用隔离游戏数据验证带 cut/split 的已关闭回放进入 raw、raw 可播放、剪辑输出正常、无标记路径不变；否则明确游戏内验收未执行。harness/build 不能替代可播放验收，不启动会改真实配置的客户端、不拿唯一原件做破坏性测试。
7. D 回报已通过/未覆盖、硬链接能力限制与遗留位置，Lead 展示 diff/证据。**本批完成即停，等用户自行 commit，再谈下一批**，不 Git add/commit/push/reset/checkout，不自动进入路径/底层保存/清理改动。

本 A 仅更新现有小写计划，源码未改、构建未跑、harness 未写。核心风险：硬链接能力不足必须安全失败；源删除失败必须阻止后处理在原路径继续写；不能拿 ATOMIC_MOVE 冒充并发不覆盖。

### 11.7 B/C 交付与 D 验证记录

- B 已建立纯 JDK 工具类框架，入口占位明确抛错、未接入调用方；C 已完成实现并移除占位，不存在假成功分支。
- C 只修改两份白名单源码：新增搬移工具类，MarkerProcessor 增加必要 import、以 moveToRaw 返回值代替原 raw 选名与 Files.move；其余剪辑逻辑和 GUI 均未改。
- 已实现同卷硬链接快速路径，避免对同盘大回放重复复制；跨卷使用目标盘独占 stage、字节校验、硬链接发布。发布后清理或删源失败会明确报已发布但搬移未完成，保留 raw 并阻止后处理。
- Lead 已回读 C 实现并核对接入真实 diff；D 已完成首轮验证，结论为不可交付，详见第 11.8 节。
- 前序构建依赖核查：dependencyInsight 确认 compileClasspath 解析 ReplayStudio:master-SNAPSHOT-slim；重映射前 dev-shadow 产物中 ZipReplayFile.class 与本地 JAR 类指纹相同，SHA-256 为 698bb1fbdaeef8996b3a34c66d72092eab041bb67c2148a830f808c98a0e988c。正式 remap 后类指纹不同，不能直接以该差异推断依赖版本变化；本批仍不改 ReplayStudio 保存行为。
- 首轮 D 报告已返回；未达到可用提交停点，不得将构建通过当成批次完成。

### 11.8 D 首轮失败证据与待批准修订

- 专用工具完整构建：`BUILD SUCCESSFUL in 47s`，`6 actionable tasks: 4 executed, 2 up-to-date`；测试任务为 NO-SOURCE，不存在 JUnit 通过结论。构建未 clean、refresh 或排除测试，仍有前序 remap/弃用提示，未改依赖或构建。
- 外部 harness 返回退出 1，D 汇总 PASS=6、SKIP=1、FAIL=8；同盘随机字节、零字节、编号、并发、R→C、C→R、R 同卷均在源校验被拦截，没有成功搬移。部分拒绝输入项也因 fileKey 空提前拒绝，不能声称已覆盖其深层分支；符号链接创建因权限跳过。
- 根因：本机 Oracle JDK 21.0.12.1 的 C/R 普通源文件 BasicFileAttributes.fileKey() 都为 null；Lead 已回读 [字节码证据](<C:/Temp/reforgedplay-D-6pb7ksw9/windows-fileattributes-javap.txt#L683-L686>)，WindowsFileAttributes.fileKey() 直接返回 null。第 11.3 节非空 fileKey 前提与该平台不兼容，不是源文件损坏。
- D 的 [保源探测](<C:/Temp/reforgedplay-D-6pb7ksw9/retention-probe.log>) 确认 11 份源/既有目标逐字节未变；C/R 原语级硬链接创建及已存在目标不覆盖通过，但不等于 helper 发布通过。R 上 getFileStore 另报 FileSystemException，本轮 helper 尚未到此阶段。
- D 的 [隔离 harness](<C:/Temp/reforgedplay-D-6pb7ksw9/MoveHarness.java>) 位于仓库外，不新增/改仓库测试；R 隔离样本约 5.3MB，未触及真实录制。D 未修改源码或计划。
- 未覆盖：成功复制/字节校验/编号竞争/最终删源、源变化、发布后清理或删源失败；游戏内 cut/split、raw 播放未执行。首批不可交付；当前已生成的 JAR 不应作为功能可用版本安装。
- **拟议修订（尚未批准、未实现）**：不删除身份保护，而是评估在源卷创建任务私有硬链接身份锚点，用 Files.isSameFile(source, anchor) 与 size/mtime、跨盘完整字节校验联合确认；fileKey 可用时补充比较，不再强制 Windows 返回非空。锚点自身的生命周期、失败留存与清理顺序需先由 A 重新核实；能力不足仍保源报错，不承诺恶意并发防护。
- **拟议修订（尚未批准、未实现）**：FileStore 仅作为同盘优化提示；查询失败时走目标卷暂存复制验证，不让 R 的可选查询能力阻断整个流程。复制/发布所需能力失败仍明确报错，不能吞真实 IO 错误。
- 本轮 Lead 仅记录失败与候选修订，未改失败源码、未自行降低契约。批准后再顺序走修订/实现/验证；此前第 11.3 节算法仍代表被否决的首轮方案，不能误当已验证方案。

### 11.9 已批准兼容修订：优先级与 JDK 21 核实（2026-11-11）

用户现在明确批准：仍只修改 [ReplayFileOperations.java](<src/main/java/com/replaymod/core/files/ReplayFileOperations.java>)、[MarkerProcessor.java](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java>) 与本计划，采用“源卷任务私有硬链接身份锚点＋Files.isSameFile 校验”，FileStore 查询 IOException / UnsupportedOperationException 只放弃同卷优化。**本节至 11.12 优先于 11.3–11.6 的冲突要求；11.7–11.8 失败证据原样保留，绝不追改为通过。** 此处是批准的待实施方案，不是修订后验证记录。

A 本轮先确认既有 Loom/NeoForge/Java 21 年代，加载本机 minecraft-java-build 与 minecraft-neoforge-mod 技能，回读两份现有源码和 D 的保源报告、字节码报告；不 scaffold、不套用 ModDevGradle 模板。没有新增依赖、MC API 或 Maven 坐标。

| 核实事实 | 官方依据与证据边界 |
| --- | --- |
| public fileKey 为 null 不等于 isSameFile 无法比较身份 | 已直接读取 [OpenJDK jdk21u WindowsFileSystemProvider 官方源码](<https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileSystemProvider.java>)：不同路径进入 isSameFile 后分别 openForReadAttributeAccess(true)，以两个同时打开的句柄 readAttributes，再调用内部 WindowsFileAttributes.isSameFile；不调用 public fileKey()。相同 Path 的 equals 快返不能拿来验证锚点，因此 anchor 与 source 必须是不同路径。 |
| Windows 身份比较使用卷序号与文件索引，public fileKey 确实返回 null | 已读取 [OpenJDK jdk21u WindowsFileAttributes 官方源码](<https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileAttributes.java>)：readAttributes(handle) 使用 GetFileInformationByHandle；isSameFile 比较 volSerialNumber/fileIndexHigh/fileIndexLow，public fileKey() 直接 return null。这里只审查官方内部实现，生产代码不得 import/call sun.*、反射、native 或设置内部属性。 |
| 生产只调用 public JDK API | [Oracle Java 21 Files](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html>) 核实 isSameFile(Path, Path) throws IOException、createLink(Path, Path)、createTempDirectory(Path, String, FileAttribute<?>...)、readAttributes、copy、mismatch、delete、getFileStore。isSameFile 随 provider 实现、跟随符号链接；每次先 NOFOLLOW 检查普通文件，不把它当锁。 |
| fileKey 可缺失 | [Oracle Java 21 BasicFileAttributes.fileKey](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/attribute/BasicFileAttributes.html#fileKey()>) 的 API 契约允许无法取得 key 时为 null；不能将非空 key 作为 Windows 搬移前提。 |

上述 OpenJDK 链接是官方 **21u 分支**，不是系统默认 JDK 25 的 javap 证据，也不是 Oracle 本机 21.0.12.1 二进制逐字节等同证明。**UNVERIFIED 交 D**：实际 Oracle JDK 21.0.12.1 和 C/R 驱动是否正确实现不同硬链接为 true、不同文件为 false。D 须记录 harness 的 java.version/java.home；若补字节码，使用该 java.home 下 bin/javap.exe（并以对应 --system java.home 固定模块映像），不得裸调 PATH 上可能为 25 的 javap。A 未运行 javap、harness 或构建。

### 11.10 最小修订算法（本批唯一优先实施算法）

**决定始终创建源卷 anchor，不按 fileKey 是否为 null 分叉。** 同一套生命周期更短、更易审查；锚点只是目录项，不复制大回放。同卷 FileStore 提示可用时仍零整文件复制。代价是**所有源卷都必须允许在源父目录创建任务目录、创建并删除硬链接**，即使目标卷支持硬链接而源卷不支持，也安全失败。这是新增兼容限制，不能隐瞒；不做无锚点降级、不增配置开关。

1. **准备与快照**：调用方保证完整保存、关闭、任务期间无人再写源文件，源/目标父目录可信且不被置换。取得 source 的绝对路径和父目录（不擅自改变原路径语义）；保留既有普通文件/NOFOLLOW_LINKS、文件名与 rawFolder 非源父目录别名检查。第一次属性快照固定 size、lastModifiedTime 和可选 fileKey；不再因 null 拒绝。rawFolder 不由这里建立新布局。
2. **源卷身份锚定**：在 source 的父目录调用 createTempDirectory(parent, ".replaymod-anchor-") 得独占任务目录 anchorDir；在其中尚不存在的固定名称 `source.link` 调用 createLink(anchor, source)。不要先 createTempFile 再覆盖，不在系统 temp/rawFolder 建“源卷”锚点。目录和链接各有 Path 状态，创建任何一步失败都不删除 source。目录私有指任务独占命名/归属，不承诺 ACL 沙箱。
3. **统一校验**：读取 source、anchor 的 NOFOLLOW 普通文件属性，两者的 size/mtime 都必须与初始快照一致，且 Files.isSameFile(source, anchor) 必须 true；任何读取/比较错误均中止。fileKey **双方非空时才辅助比较**（对源/锚点与初始快照中可比较的非空 key 做 equals）；null 本身不报错，anchor 身份校验绝不能省。所有后续源校验复用这套逻辑。初读与建链接之间仍受独占契约约束，非空 key 可辅助识别替换，但 null 时不能声称锚定了初读瞬间的不可变身份。
4. **可选同盘提示**：仅相同 provider 时尝试比较 getFileStore(source) 与 getFileStore(rawFolder)。只在这段查询/比较的窄 try 中捕获 IOException / UnsupportedOperationException 并令 sameStore=false，转目标暂存流程；不捕获后续复制/发布错误为“优化失败”。provider 不同/返回不等也走暂存。保留外层 SecurityException 包装为 IOException 的失败语义；不解析错误字符串，不用盘符猜测同盘。sameStore=true 仍是提示，不保证链接一定成功；真实 createLink 错误直接失败。
5. **准备完整 payload**：同卷快速路径使用 anchor 作为 payload，发布前做统一校验，不复制。其余在 rawFolder 建原有 `.replaymod-move-...` stage，Files.copy(anchor, staged) 到不存在的 payload.part，复制完成关闭句柄；统一校验，要求 Files.mismatch(anchor, staged)==-1L，再统一校验。锚点固定被复制对象；同一个 inode 仍可被外界写，所以 size/mtime 和独占契约不可取消。无需 COPY_ATTRIBUTES、容器解析或整文件读内存。
6. **发布与冲突**：保留 name.mcpr/name.1.mcpr 等规则及编号溢出检查，以 createLink(candidate, payload) 决定不覆盖发布；仅 FileAlreadyExistsException 换编号并复用 payload。冲突项若 isSameFile(source, candidate) 或 isSameFile(anchor, candidate) 为 true，按源别名拒绝，不能删除冲突项；其他比较错误中止，不按失败字符串猜冲突。成功创建后立即标记 published=true，然后确认 candidate/payload 同一文件；确认失败仍不回滚删 candidate。发布前最后一次统一校验，发布确认后再统一校验，不能只验证 staged 身份而漏源变化。
7. **发布后的严格清理顺序**：确认完整 raw 后，先删除 staged 目录项（如有），再 delete(stage) 空目录；随后在 anchor 尚在时再次统一校验 source/anchor。接着 delete(anchor)，再 delete(anchorDir) 空目录。任何一步失败保留 raw，不删 source，不递归清理、不继续剪辑。最后再读 source 的 NOFOLLOW 普通文件属性，对初始 size/mtime 始终比较，可比较 fileKey 辅助比较；快速路径还可用 isSameFile(source, candidate) 作最后身份复核（候选是同 inode），但跨卷不可要求这一比较为 true。**最后一个有副作用的、可失败步骤是 Files.delete(source)，其成功后直接返回 candidate，不再 close/清理/校验任何可抛错资源。** 不把需清理的资源放在跨越删源返回的 try-with-resources/finally 中。
8. **明确最终窗口**：跨卷 anchor 删除后只剩最终属性校验，null key 时不能再证明最终 source 的身份；即使快路径 isSameFile 或非空 key 也仍有检查至 delete 的 TOCTOU。为满足成功无 stage/anchor、删源后不再有失败清理，本批明确依赖可信目录与已关闭源的独占契约，**不宣称身份检查与 delete 原子、不宣称绝对竞态或恶意替换防护**。不能改为先删源再清锚点，也不能虚构 Java public API 提供条件删除。

成功标准：完整 raw、不覆盖任何已有项、source 已删除、stage/anchor 的目录项与任务目录全无；MarkerProcessor 才可在原路径写剪辑输出。同卷路径只有临时多个硬链接，不长期共享可写回放；跨卷确实经过目标盘暂存与字节校验。保留不承诺断电持久化、文件系统驱动行为及恶意并发的边界。

### 11.11 错误留存与 B/C/D 精确交接

失败处理保持简单：**不加自动恢复或失败 finally 清扫**。发布前失败保留 source，保守留存本任务 anchor/stage（包括部分 payload）并在 IOException 报 phase/source/rawFolder/anchorDir/anchor/stage/staged/candidate；本 helper 从不主动删源“回滚”。anchor 只是源的另一个名字而不是不可变备份，出现源被外部替换等违约时不能把 anchor 当作普通垃圾删掉。发布后任何确认/清理/最终删源失败，报“Raw published; move incomplete”，保留 raw，source 若尚在也不再动它，异常列出可能已清掉或遗留的路径；不宣称所有路径仍存在。UnsupportedOperationException 带 cause 包装为 IOException，真实复制/发布异常不吞，既有目标永不删除。失败可留 anchor/stage 是明确诊断，不新增启动扫描器、复杂恢复、重试或生产测试钩子。

| 文件/产物 | B：只调整契约/辅助框架 | C：填完整实现 | D：只读审查与验证 |
| --- | --- | --- | --- |
| [ReplayFileOperations.java](<src/main/java/com/replaymod/core/files/ReplayFileOperations.java>) | 修改 Javadoc 契约：始终 anchor、可选 key、FileStore 降级、最终窗口；仅引入必要私有辅助声明/框架，未实现分支必须显式抛错，不删除现有安全检查制造可用假象。建议只拆“快照属性比较”“带锚点校验”“窄 FileStore 提示”几个私有方法，不新建类型/策略体系 | 完整落实 11.10、错误路径/阶段信息；去掉 readSource 强制非空 key；快照与身份校验职责分清；锚点创建/复制/发布/清理均在本类，保持单一公共入口，不为 harness 暴露入口 | 审查全部成功/失败分支和删源后的无清理性质；本轮 D 默认不改源码，发现问题回 Lead/C 重交，不自行扩大设计 |
| [MarkerProcessor.java](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java>) | 不改 | 原有 moveToRaw 接入已存在，通常无需改；只在本次必要时修局部 import/调用契约，不改其他剪辑行为或线程 | 核对 IOException 确实中止后处理，原源路径仅成功返回后重用；不改 GUI/其他调用方 |
| [plan.md](<plan.md>) | 只读 | 只读 | 只读，验证回报 Lead；当前仅 A 更新，Lead 可另记录批准后的真实结果 |
| 仓库外原 harness/新一轮隔离报告 | 不写 | 不写 | 复用或在新外部任务目录更新 harness，编译/样本/日志仍全在仓库外；不覆盖第一轮失败证据 |

白名单未扩大：不改依赖、构建/wrapper、配置、已有测试，不新增仓库测试，不 Git 写、不装包。B→C→D 顺序，B 的框架不能直接算功能交付；C 完成后 D 才构建。

### 11.12 修订后 D 验收清单与停点

1. 先记录真实 Java 21 的 java.home/java.version、javac/javap 的绝对路径/版本，外部编译运行 helper+harness 时使用同一 Java 21，不以默认 JDK25 的结果背书。按 11.9 可用官方 21u 源码解释实现，实际 C/R 功能仍必须实测。
2. 在 C 和 R 各自获准隔离目录实测 public fileKey=null 时：不同路径 source/anchor 的 isSameFile=true；同长度、同 mtime、不同字节的独立文件 isSameFile=false；删掉/替换源目录项后 anchor 仍指原对象且新 source/anchor 比较为 false。只用外部样本，不反射或调用 sun.*。这验证原语，不冒充 helper 故障路径通过。
3. 重跑首轮全部成功/拒绝样本：同盘中文空格、随机大文件、零字节、既有目标字节不变、编号、两独立源并发、目录/符号链接/不存在源/源目标父目录别名、已有候选是源硬链接的拒绝。每次成功查返回 raw 逐字节一致、source 不存在，**同时列举源父目录和 rawFolder，确认本次 anchor/stage 子目录与链接全部清除**；输入拒绝不得被 null key 提前拦截掩盖。
4. C→R、R→C、R→R 分开报告。R 上先记录 getFileStore 的真实异常、createLink 与 isSameFile 能力，再验证 helper 走暂存成功（R→R 若 FileStore 仍失败，也允许暂存复制，不能冒称零复制优化）。C 同盘 FileStore 正常时，以只读代码审查及可靠外部观察证据区分快速路径；不能单凭最后无 stage 推断从未复制。不修改真实盘或配置制造故障。
5. 对源变化/替换（含相同 size/mtime 的不同 inode）、字节不一致、复制/发布失败、发布后 stage 清理、anchor 链接/空目录清理、最终 source 删除失败做安全隔离验证；使用已有 harness 或外部观察/句柄占用等可重复方法，不增生产注入钩子。发布前断言 source 未被 helper 删除，发布后断言 raw 保留、异常含发布状态与 anchor/stage 路径、调用方不继续剪辑。难以确定性复现的分支标 UNTESTED，并给出只读控制流审查，不能伪造注入通过；需要扩大测试能力时先停问 Lead。能力不支持场景若无可用卷也如实未测，不能无许可改盘/ACL。
6. FileStore IOException 已有 R 实例可验证；UnsupportedOperationException 分支可只读审查加仓库外可行的 provider 样本验证，后者若实现过于复杂则列未测，不为这一项造策略框架。确保 catch 仅包优化查询，copy/createLink/mismatch/delete 的真实异常不走成功或静默重试。
7. 执行第 11.6 的专用 mc_gradle：可先 compileJava（900000ms），最后 build（1200000ms），projectDir 为当前绝对项目目录，args=["-Djava.io.tmpdir=C:\\Temp"]；不 clean/refresh、不跳已有测试。首轮 47s 成功仅是历史；本轮重新记录实际退出码与任务摘要，NO-SOURCE 不是测试通过。A 本轮不运行这些任务。
8. 游戏内 cut/split/raw 可播放仍按 11.6 获许可用可复制样本验收；没执行则明确缺口。D 新报告必须并列首轮 FAIL=8 与修订后真实 PASS/FAIL/SKIP，不覆盖旧报告、不先宣称修复。任何失败或身份/不覆盖能力不可靠均不可交付；满足可验证门槛后停下等用户自行 commit，不进入下一批目录设置或恢复重构。

本 A 本轮仅修订本计划，未改两份源码、未构建、未编译外部 harness。给 Lead 的一句话：这次兼容的是 Windows 的公开属性限制，不是取消身份保护；最后一刀仍靠可信目录独占契约，别把它说成没有竞态的删除。

### 11.13 兼容修订交付结果与提交停点（Lead 已复核）

- A2 完成批准修订；B2 仅调整契约；C2 实现源卷锚点及可选同卷查询，工具类最终 264 行；D2 首次代理异常退出未返回报告，未将它计作通过。重新委派的 D2 完成隔离验证和完整构建，未修改仓库文件。
- Lead 已回读 [实际 harness](<C:/Temp/reforgedplay-D2-20261111-1145/MoveHarnessD2.java>)、[完整运行日志](<C:/Temp/reforgedplay-D2-20261111-1145/harness.log>)、[工具版本](<C:/Temp/reforgedplay-D2-20261111-1145/tools.log>)、[构建转录](<C:/Temp/reforgedplay-D2-20261111-1145/build-tool-output.log>)，核对最终工具类及 MarkerProcessor 接入 diff，不只复述子代理摘要。
- 实际 Java/Javac 为 21.0.12.1，java.home=C:/Program Files/Java/jdk-21.0.12.1。harness javac/java 退出 0，汇总 **PASS=14 / SKIP=1 / FAIL=0**；首轮 PASS=6 / SKIP=1 / FAIL=8 保持为失败历史，不覆盖旧证据。
- 通过：C/R null-key 锚点身份原语；同 size/mtime 的不同文件和独立源替换可区分；C 同卷中文/空格 2MiB 随机字节快速链接、零字节、同名编号且已有目标不变、20 对独立源同名并发、目录候选编号；目录/缺失源/父目录别名/候选硬链接别名正确拒绝。
- **C→R、R→C、R→R** 均实测完整搬移、目标字节一致、源消失、成功任务无 anchor/stage 残留。R 的 getFileStore 确实抛“函数不正确”，helper 转暂存复制完成。外部 witness 显示 C→C 结果与原件同 inode、其余 R 相关结果为独立副本，不拿“结束后目录为空”冒充快速路径证据。
- 符号链接创建因系统权限跳过，未提权。独立失败样本的源与冲突项保留，硬链接别名拒绝时留存诊断 anchor，符合本批失败留存契约；不能把这些输入拒绝测试算作全部 IO 故障注入通过。
- 专用 mc_gradle：task=build，参数 `-Djava.io.tmpdir=C:\Temp --console=plain`，预算 1200000ms；**BUILD SUCCESSFUL in 25s；6 actionable tasks: 4 executed, 2 up-to-date**。未 clean、refresh、排除测试；返回工具文本未暴露单独 exitCode/timedOut 数值，不虚构。test NO-SOURCE，不是 JUnit 通过。
- 仍有前序 Mixin/JOML/WorldRenderer 类解析和重映射提示及 Gradle 弃用提示，未越界处理；构建通过不代表游戏内加载或全部兼容功能通过。产物为 [正式 JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>)，另有 dev-shadow；用户如做游戏验证应使用可复制样本和独立数据目录，不能直接用唯一回放冒险。
- **未测**：helper 执行期间确定性源变化/替换、mismatch、复制/发布失败、发布后 stage/anchor 清理失败、最终源删除失败、FileStore UnsupportedOperationException、无硬链接文件系统；这些只有控制流审查，不宣称实测通过。游戏内 cut/split、raw 可播放与剪辑输出尚未执行。
- 源卷和目标卷都需支持硬链接，源父目录需允许任务目录/链接创建删除；真实源必须已关闭且任务独占，父目录可信。锚点不是不可变备份，不防所有恶意竞态，不承诺原子条件删除、fsync 或断电保护。R 查询失败时即便 R→R 也会复制而非零复制；后续若要优化须另行设计，不能依赖盘符猜测。
- 仓库交付范围仍只有：本计划、[ReplayFileOperations.java](<src/main/java/com/replaymod/core/files/ReplayFileOperations.java>)、[MarkerProcessor.java](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java>)。工具类是未跟踪新增文件，普通 git diff --stat 不含它，用户提交时需留意；代理不执行 add/commit。
- 本批仅完成 raw 原始回放备份前的完整文件搬移基础；尚未添加 raw/temp/tmp/录制工作目录配置、即时缓存清理、GUI 异步发布或 ReplayStudio 保存窗口适配。**停在本检查点，等待用户自行 commit，不自动进入后批。** 临时测试证据保留在仓库外，不自动清理或装包。

D2 原话：这次 Windows 空 fileKey 的成功搬移已有真实证据，但没测到的故障和游戏结果，我不会替它们签通过。

## 12. 第二批：普通 tempPath 与图片中间文件（批准范围；A 方案，2026-11-11）

### 12.1 基线、目标、成功标准与严格排除项

A 已重读既有计划、四份目标源码、设置注册/重载、模组入口及 Image 调用方；只读核实 HEAD=9762f30，开工前工作区 clean。已读取本机 minecraft-java-build、minecraft-neoforge-mod 技能；未将技能的新项目模板应用到既有工程。保留 Minecraft 1.21.1、NeoForge 21.1.82、Java 21、Architectury Loom 1.7-SNAPSHOT、Gradle 8.8 和 layered Yarn 映射，元数据为 [neoforge.mods.toml](<src/main/resources/META-INF/neoforge.mods.toml>)；并存其他加载器元数据不构成迁移授权。[构建脚本](<build.gradle>)、[版本属性](<gradle.properties>)、[wrapper](<gradle/wrapper/gradle-wrapper.properties>)、[ReplayStudio JAR](<libs/ReplayStudio-master-SNAPSHOT-slim.jar>) 仅核查、不改。现代 Java 21 年代不等于须改用 ModDevGradle；无新依赖、无新 NeoForge API。

**仅实现** `advanced.tempPath` 字符串配置、一次图片操作一个独占任务目录与固定 payload、通用 Image 可注入 factory、ReplayMod 注册该 factory。成功标准是：实际 `writePNG(OutputStream)` 经过已注入 factory，正常/编码失败/写出失败均释放本任务中间文件，保留主异常与 cleanup 的 suppressed 诊断；不关闭/flush 调用者 OutputStream；默认空配置仍选择 JDK 默认临时位置，而不是游戏根目录。指定盘失败不悄悄改写其他盘。

**不实现/不触碰** rawPath、tmpPath、recordingWorkPath、cache 清理、其他 temp 使用者、启动/退出遗留扫描、全 JVM `java.io.tmpdir` 修改、恢复/事务保存、GUI 文案/GUI 设置页、依赖/构建/wrapper/元数据/README/实际游戏配置/仓库测试/.env。不新增任何源码文件；第一批 [ReplayFileOperations.java](<src/main/java/com/replaymod/core/files/ReplayFileOperations.java>)、[MarkerProcessor.java](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java>) 原样保留。不 Git 写、不装包、不访问 whale-craft、不启动真实游戏；仅外部 C:/Temp、R:/Temp 小样本验证。本批不承诺清理崩溃或断电残留，不承诺 RAMDisk 数据持久性。

### 12.2 B/C/D 精确白名单与所有权

下列四份既有源码是**完整源码白名单**，顺序 B→C→D，不并行写同文件。A 仅写本小写计划；B/C/D 对计划只读，结果交 Lead 记录。本批 D 默认不改源码，有问题回报 Lead/C，不擅自扩大范围。

| 文件 | B：最小框架/契约 | C：功能实现 | D：只读审查与验证 |
| --- | --- | --- | --- |
| [Setting.java](<src/main/java/com/replaymod/core/Setting.java>) | 增加 `public static final Setting<String> TEMP_PATH = advanced("tempPath", null, "")` | 核对注册能自动发现、默认兼容；不加其他键 | 检查只有该新键，真实配置未改 |
| [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>) | 声明唯一新实例入口 `public Image.TemporaryFile createImageTempFile() throws IOException` 及必要私有路径解析；未实现必须显式抛错 | 每次读取一次设置、按 mcDir 解析，调用 Image 中纯 JDK 句柄创建逻辑 | 实际生产服务用外部依赖 stub 隔离验证，旧方法不改 |
| [Image.java](<src/main/java/de/johni0702/minecraft/gui/versions/Image.java>) | 定义下节嵌套 factory/句柄 API、默认可用 factory；不先把实际写出切到占位逻辑 | 实现纯 JDK 任务生命周期与 TWR，实际 OutputStream 重载接入；不得 import 任何 com.replaymod 类型 | 编译/运行实际整份源码；区分 stub 控制流验证与真实 MC/native 验证 |
| [ReplayMod.java](<src/main/java/com/replaymod/core/ReplayMod.java>) | 只读核实注册位置，不提前接入未完成框架 | 在构造器最前适当位置注册 `Image.setTempFileFactory(folders::createImageTempFile)`，只加必要 import/一处注册 | 核对初始化无环、实际注入时序/字节码；不运行真实 Backend/配置加载 |
| [plan.md](<plan.md>) | 只读 | 只读 | 只读，报告交 Lead；A 本轮只更新顶部和第 12 节 |

不得增加新顶层类/策略框架/资源注册器/线程池/故障注入钩子。B 的占位不能算完成，C 全部实现后才交 D。若四文件内无法安全实现，或必要验证需要越界，明确 BLOCKED 并停下，不“顺便”改其他文件。

### 12.3 最小自有 API 与职责（新设计，不冒称现有外部 API）

所有新类型嵌在既有 Image 中，避免额外源码，也避免通用 GUI 依赖模组。建议固定为：

- `public interface TempFileFactory { TemporaryFile create() throws IOException; }`，可加 `@FunctionalInterface`。
- `public static void setTempFileFactory(TempFileFactory factory)`：拒绝 null；仅替换一个 `private static volatile` 引用，不做 IO、读配置或访问 NativeImage/ReplayMod。无需 getter/reset/监听器框架。
- `public static final class TemporaryFile implements AutoCloseable`：纯 JDK 字段和逻辑，私有构造；`public static TemporaryFile create(Path root) throws IOException`、`public Path path()`、`public void close() throws IOException`。root=null 仅表示调用 JDK 默认临时目录创建重载；非 null 必须已经是解析后的绝对路径。内部持有 final canonicalRoot/taskDirectory/payload；不持有服务、配置、factory 或 OutputStream。`path()` 返回固定 payload；已关闭后拒绝取新写入路径（IllegalStateException）。不返回任意可由调用者拼装的删除目标。
- 默认 factory 是延迟执行 `TemporaryFile.create(null)` 的 lambda，类初始化只建立对象引用，不创建磁盘文件、不访问 MC、不读取 ReplayMod 单例。共享生命周期核心在此嵌套静态类中，**不调用 Image 外层/NativeImage/设置 API**。前缀可用 `.jgui-image-`；名称只是辅助诊断，真正归属是 createTempDirectory 成功返回后句柄持有的精确任务路径，不通过名字认领旧目录。ReplayMod 注入后这些句柄就是模组此次任务专属资源，不必再建可扫描的全局命名空间。
- `ReplayFoldersService.createImageTempFile()`：只读取一次 `settings.get(Setting.TEMP_PATH)` 为局部 String，再解析 root，调用 `TemporaryFile.create(root)`。路径解析可以私有静态 helper，不为测试增加公共服务构造/依赖接口；旧 replay/raw/recording/cache 逻辑原样不动。
- `writePNG(OutputStream)` 在进入时快照 volatile factory 到局部变量，只调用一次 create；用 TWR 持有非 null 句柄（对不合法 factory 的 null 结果明确拒绝）。顺序为 `inner.writeTo(handle.path())` → `Files.copy(handle.path(), outputStream)` → TWR close。不将调用者流放进 TWR，不调用它的 close 或 flush，不关闭 Image/NativeImage，不改 `writePNG(File)` 或其他图片 API。

返回具体 final 句柄而非又添一个通用资源接口，足够满足当前唯一图片使用者；不增加“所有临时用途”的生命周期体系。可注入 factory 必须遵守 create 失败自行清理已分配资源的契约，Image 不可能替未返回句柄的任意外部 factory 猜路径收尾。本模组 factory 复用同一标准实现以满足此义务。

### 12.4 tempPath 的精确定义、创建和快照

1. 新键只用普通 advanced 字符串，默认 `""`，displayName=null，沿既有注册机制，不另建 GUI。null（防御性）、空串和全空白按未配置处理。**先判断空值再解析 Path**，不能 Path.of("") 后当游戏目录清理。非空白值不擅自 trim，不展开 `%TEMP%`/`${...}`/`~`，不猜“R 就是 RAMDisk”。
2. 非空白通过默认文件系统解析。绝对路径按原卷使用；普通相对路径相对于现有 `mcDir`（MinecraftClient.runDirectory），然后转绝对并 normalize。`..` 按用户路径语义归一，可位于游戏目录外；不能改用 shell cwd。Windows `R:Temp`、`R:`、`\\Temp` 这种有 root 但不完整绝对的路径必须拒绝，避免驱动器当前目录/当前卷隐式补全；以 `!isAbsolute && getRoot()!=null` 检出，不用字符串错误消息猜测。不支持 URL 或设备路径作为额外语法。
3. InvalidPathException、权限相关 SecurityException 及必要的 UnsupportedOperationException 包装成带 cause 的 IOException，消息包含 `tempPath` 原值、解析目标（若已知）和阶段；不把无权限误报为空配置。root 已是文件、父目录无权限、R 未挂载/中途消失、空间不足、编码或复制 IO 错误均向上报错，不改用默认位置、不自动建/卸载磁盘。
4. 显式 root 不存在时允许 `Files.createDirectories(root)` 建普通父目录；已挂载盘才可能成功。失败可能留下部分新父目录，这是用户指定位置，不向上删除“回滚”。空值走 `Files.createTempDirectory(prefix)`，保留 JDK 默认位置选择及系统策略，不调用 System.setProperty、不修改全 JVM 行为；JDK 默认 temp 本身不可用也明确失败。
5. 显式 root 建好后 `toRealPath()` 固定真实目录；允许用户选择的现有根目录别名在任务开始时解析到其真实目标，但不扫描它或清理任何兄弟项。再 `Files.createTempDirectory(canonicalRoot, prefix)` 原子分配独占任务目录。默认重载成功后也固定其父真实路径和本任务真实路径。每个任务固定 `image.png`（预先 `Files.createFile`，CREATE_NEW 语义）为唯一 payload；不用 createTempFile 再覆盖其他人的名字，不在用户 root 直接写随机散落文件。
6. 每任务从读取设置到句柄返回只使用这次 String/root 快照；已创建句柄 close 只用 final 路径，**不重新查设置、不读取全局 factory**。重载后新任务读新值，老任务仍清理老位置。切换到不可用新盘只让新任务失败，不能搬移/清除旧任务。volatile factory 的并发替换仅影响以后获取引用的 writePNG 调用；已快照 factory 或已创建句柄不改变。这里不修改现有 settings watcher 的线程模型或全项目配置一致性。

### 12.5 任务生命周期、异常优先级、幂等与链接边界

**创建阶段**：先准备用户 root（永不取得删除 root 的所有权），后独占 taskDirectory，后固定 payload，最后才交出句柄。每一步成功就记录路径/是否由本次创建；payload 创建或后续构造校验失败时，factory 自己按相同安全边界清理已创建 payload（仅确实创建成功时）再删空 taskDirectory。不得因为固定名字碰到既有项就认领它；CREATE_NEW 失败时不删该冲突项。清理失败 addSuppressed 到原创建异常；IOException 保留 cause、phase/root/task/payload，失败未到达的路径标为未创建。不要在普通 finally 抛 cleanup 覆盖创建异常。不能恢复的 VM 终止/断电不在此保证内。

**用完清理**：普通图片中间文件无恢复价值；无论编码/复制成功或失败，均由 TWR 尝试清理。NativeImage.writeTo 返回/抛出之前已经关闭自己文件 channel（下节实际 JAR 证据），Files.copy 的内部输入也在退出时释放，之后删除 payload，再删除空 taskDirectory。不递归、不枚举删除孩子、不删 canonicalRoot/其父，不跟随 payload 符号链接读写/清理目标。不因为名称像 tmp/png/replaymod 就处理任何历史文件。若 payload 删除失败，不继续假装目录清理成功；若 payload 已删而目录非空/占用，保留目录并报其精确位置，不删多余文件。

**链接/替换最小保护**：创建时记录真实 root 和真实 taskDirectory，并确认 task 是 root 的直接孩子；payload 必须是 task 的固定直接孩子。操作前/close 前检查 root/task 当前真实路径仍等于记录路径，task 的 NOFOLLOW BasicFileAttributes 是目录而非符号链接/other；payload 若存在，用 NOFOLLOW 属性确认普通文件，不是目录/符号链接/other。检测到链接/junction、路径重定向、无法确认类型或身份边界时停止向下访问/删除，抛带定位信息的 IOException，不进入其目标。不要用 `Files.isDirectory/exists` 的 false 混淆“不存在”与“无权限”，缺失仅在明确 NoSuchFileException 时按相应阶段处理。默认 provider 的 junction 与 toRealPath 实际行为交 D 验证；不得以 isSymbolicLink 单一 false 宣称无 junction。

本批不加 FileStore/DOS/fileKey/硬链接/磁盘容量或可选 ACL 属性要求；BasicFileAttributes 与 toRealPath 是边界检查而非 RAMDisk 身份认证。**可信根目录/任务期间父路径不被外界恶意置换是明确前提**：以上检查不是原子条件删除，不能消除检查至 write/delete 的 TOCTOU，纯路径式 JDK API 不是对恶意用户的安全沙箱。用户指定链接根在创建时显式解析一次不等于递归清理跟随链接；close 不遍历任何文件树。task 被换成指向外部的 junction 时，必须在访问 payload 前拒绝；若当前 provider 无法可靠确认该边界，报错保留，不降级递归删除。

**幂等选择**：句柄只有一次清理尝试；`close()` 开始先置终结状态（可 synchronized，避免两个线程同时清理），此后再次 close 为无副作用 no-op，包含首轮失败后亦不再尝试删除后来可能复用的路径。已删除/明确缺失的 payload 可继续删本任务空目录；task 已明确不存在时不寻找/重建。失败位置由异常保留供用户定位，不增后台重试器/退出钩子/deleteOnExit。不得将失败后的重复 close 说成自动重试通过。若 API 更名/状态策略必须变更，先回 Lead 更新契约。

**异常优先级**：TWR 使原编码/复制异常仍为主异常，cleanup IOException 成为 suppressed；不捕获后丢掉原异常，不重新 throw 一个无 cause 的错误。只有 cleanup 失败时，抛明确 `temporary image cleanup failed` 的 IOException，含 root/task/payload/阶段，提醒 output 可能已经写完（不为任意 OutputStream 提供事务回滚，不诱导盲目重试到原流）。创建失败中的 cleanup 同样 suppressed；难以确定的遗留状态写“可能存在”，不虚报文件一定保留。Error/RuntimeException 发生在 TWR 主体也应触发正常资源收尾，但不承诺 OOM/进程终止的完整清理。

### 12.6 初始化顺序与实际接入证据

- [ReplayMod.java](<src/main/java/com/replaymod/core/ReplayMod.java#L60-L109>)：实例字段先创建 settingsRegistry、注册 Setting.class、设置 instance，然后 folders/files，再进入构造器，构造器末才 `settingsRegistry.register()` 读实际配置；[ReplayModBackend.java](<src/main/java/com/replaymod/core/ReplayModBackend.java#L13-L21>) 构造 ReplayMod 后在 construct 事件调用 initModules；initModules 先所有 initCommon 再 initClient。不能只凭方法名把“register”误当在构造器之前完成。
- [Utils.java](<src/main/java/com/replaymod/core/utils/Utils.java#L84-L95>) 静态默认缩略图用 Image.read，模块构造/静态类引用可能提前触发 Image 初始化；不能假定 Image 到 initClient 才首次加载。Image 类初始化必须完全独立于 ReplayMod。
- **C 注册位置决定**：ReplayMod 构造器 `this.backend = backend` 后、I18n/协议检查/各模块构造之前，调用一次 setter，捕获已经构造好的 `folders` 实例方法引用。setter 不立即调用 factory，不 IO，不读设置，因此不会由此重入 Utils/ReplayMod。注册前早期 Image 写出仍能用默认 factory；注册后现有 Image 实例的每次 OutputStream 写出都从静态 factory 获取新任务，不把 factory 固定在 Image 构造器里。无须等 initClient，也不在静态初始化器使用 `ReplayMod.instance`。
- factory 在注册时不缓存 tempPath；设置加载前若真有早期任务，读注册的空默认值，加载/重载后的新任务才读新值；不为早期任务追溯迁移。实例字段初始化早于构造器注册的窗口不能承诺覆盖，但默认可用；当前只读搜索未见这个窗口的 `writePNG(OutputStream)` 调用，仍交 D 核实，不宣称全 mod 载入顺序已实机验证。
- [BlendMaterials.java](<src/main/java/com/replaymod/render/blend/BlendMaterials.java#L46>) 的 stream 写出与 Image.toBufferedImage 内部写出会经过 factory；[PNGWriter.java](<src/main/java/com/replaymod/render/PNGWriter.java>)、[ScreenshotWriter.java](<src/main/java/com/replaymod/extras/advancedscreenshots/ScreenshotWriter.java>) 使用 File 重载，不属于中间文件改写目标。不能以其他输出路径仍在原处认定注入失效，也不能为了“覆盖所有图片”改这些文件。

### 12.7 API/官方文档/实际 JAR 核实（2026-11-11；不是功能已测）

本次已 web_search 并读取下列正文；新公开方法/类型是 12.3 的项目自有设计，不冒充 Minecraft API。无新增 Maven 坐标或 NeoForge 调用，保持本地现有版本配对。

| 已核实事实 | 来源与用途 |
| --- | --- |
| Files.createTempDirectory(Path,String,FileAttribute<?>...) 与默认目录重载；createFile/createDirectories/deleteIfExists/readAttributes | [Oracle Java 21 Files](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html>)：独占创建、父目录创建可能部分成功；delete 空目录/链接项，不递归；所有实现支持 basic 属性，不把可选 DOS/fileKey 当必要能力 |
| Path.of / isAbsolute / getRoot / resolve / normalize / toRealPath | [Oracle Java 21 Path](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html>)：空路径代表默认目录；resolve 遇绝对目标返回目标；normalize 是词法操作、并非链接隔离；toRealPath 需要存在且默认解析链接 |
| AutoCloseable.close 可收窄为 IOException；幂等需要实现者自行保证 | [Oracle Java 21 AutoCloseable](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/AutoCloseable.html>)，不依赖 GC/finalize 做任务清理 |
| TWR 保留主体异常、close 失败 suppressed，逆序关闭资源 | [Oracle 官方 TWR 教程](<https://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html>)（页面是 JDK8 教程，语义沿用；不是 JDK21 新 API）；[JLS21 14.20.3](<https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-14.20.3>) 为规范索引，整页抓取被截断，未把截断部分当逐段核实证据 |
| NativeImage.writeTo(Path) throws IOException，File 重载与 close | [Fabric Yarn 1.21+build.1 NativeImage API](<https://maven.fabricmc.net/docs/yarn-1.21+build.1/net/minecraft/client/texture/NativeImage.html>)，与本项目配置的 Yarn 基线对应；不拿 1.21.4/26.x 文档代替实际类 |

A 还用 **C:/Program Files/Java/jdk-21.0.12.1/bin/javap.exe**（--system 同一 Java21）对实际 Loom 缓存 [named Minecraft JAR](<C:/Users/zhenz/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/neoforge-21.1.82-minecraft-merged/1.21.1-loom.mappings.1_21_1.layered+hash.426444933-v2-neoforge-21.1.82/neoforge-21.1.82-minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.426444933-v2-neoforge-21.1.82.jar>) 执行只读 `-p -c NativeImage`：writeTo(Path) 偏移 34–45 Files.newByteChannel，79–116 正常/异常均关闭 WritableByteChannel，异常关闭失败 addSuppressed；静态偏移 6–18 设置 WRITE/CREATE/TRUNCATE_EXISTING。故预先建空 payload 合法，返回后可以关闭后清理；此证据不等于 native 编码在实际客户端上已跑通。该方法还会抛 UnsupportedOperationException/分配状态错误，TWR 同样须清理，不仅考虑 IOException。

**UNVERIFIED 交 D**：当前实际 classpath 与该缓存类的一致性（build 核对，不改构建）；C/R 驱动对新目录/写出/清理、junction 与 toRealPath 边界的实际结果；真实 NativeImage/LWJGL 可在无游戏进程中加载到何种程度；整个 NeoForge 生命周期运行时的实际注入效果。所有“构建/实测通过”必须由 D 新结果支持。

### 12.8 仓库外 Java21 隔离验证策略（D 执行，A 不运行）

**先证据与边界**：只读 git status/diff、HEAD；确认只有上述四源码＋计划。记录 Java/Javac 绝对路径/java.home/java.version；优先复用已有 C:/Program Files/Java/jdk-21.0.12.1，不调用 PATH 上 JDK25 代测。新建唯一 C:/Temp 外部任务目录保存 harness/stub/classes/报告；R:/Temp 仅在卷已存在且用户允许时放少量样本。无真实配置加载/写入，无仓库测试文件/.class，不能安装 JUnit/Mockito/依赖。

**层 1：生产纯 JDK 生命周期**：核心虽嵌在 Image 中，但其方法体仅依赖 JDK。直接 javac `--release 21 -d 外部classes` 编译仓库**整份实际 Image 源码**＋外部最小 NativeImage/NativeImageBackedTexture stub；或者直接使用 build 编出的实际 Image 与其嵌套 class，先证明这些纯 JDK 方法可在不初始化 MC/native 的情况下加载。测试调用实际 `Image.TemporaryFile.create/close`，不手抄/抽取/替换核心源码逻辑。若 class 验证需要 MC 类型，则提供 stub 或真实编译 classpath，不把缺依赖冒充逻辑失败。stub 只为依赖边界服务，**不代表真实 MC/native 验证**。

**层 2：实际 Image.writePNG 控制流**：同样编译原样生产 Image，而 NativeImage stub 的 writeTo(Path) 用固定小字节落盘，可按测试用例抛 IOException/RuntimeException、制造 payload 占用或任务目录内额外哨兵；stub 不是复制 Image 实现。调用实际生产 writePNG(OutputStream)，用检测 close/flush 的 OutputStream 与半途抛错流验证。对“编码失败＋cleanup 失败”用 stub 在 task 内创建一个外来小文件，然后抛指定原异常，cleanup 删除自己的 payload 后删空目录失败；断言同一原异常仍为 primary、suppressed 包含 task/payload、外来文件不删。额外文件只在外部样本，最后由 harness 显式收拾其自有哨兵，不伪造真实驱动失败。这个方法验证真实生产 TWR/归属代码，**不是实际 PNG 编码、内存释放或 native 文件句柄占用通过**。

**层 3：实际配置解析和注入**：编译生产 [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>) 与 [Setting.java](<src/main/java/com/replaymod/core/Setting.java>)，外部 stub 提供 SettingsRegistry 的实际签名/内存 get、MinecraftClient 的临时 runDirectory、PercentEscaper 和 Utils.ensureDirectoryExists 的最小编译面；不调用真实 backend/真实配置路径，不替换这两份生产文件。以真实 service 方法提供 factory 并调用真实 Image 写出验证端到端路径；这些 stub 不是设置 watcher/游戏目录 API 实机验证。ReplayMod 构造器有大量真实副作用，不为跑一行 setter 启动 Backend；用源码/构建后 javap 核查一处实际注册与顺序，并单独用实际 Image+实际 service method reference 证明注入贯通。init 游戏实机仍未验收。

**层 4：真实 MC/native 小样本（只在现有环境可行时）**：用 build 产出的实际 Image、实际 named NativeImage、现有 MC/LWJGL JAR 和匹配 natives 在**外部 Java21 进程**尝试 1×1/2×2 像素 writePNG(ByteArrayOutputStream)，只在 C:/Temp 或 R:/Temp 任务目录，不启动游戏、不加载 ReplayModBackend、不补下载/解压安装新 native、不改真实运行参数/配置。NativeImage 构造、STB 编码、RenderSystem/MemoryUtil/日志/MC bootstrap 依赖都是真实门槛，不可仅把 JAR 放 classpath 就承诺可以无游戏运行。若失败，记录真实异常链与依赖/初始化缺口，标记真实 MC/native=UNTESTED/BLOCKED；可以交付层1–3+build 的范围性证据，但**不能报告图片功能已游戏验收或 native 实测通过**。不得用 stub 产生的固定 PNG 字节冒充真实 MC 图片验证。

### 12.9 D 最小用例矩阵、构建与停点

| 用例 | 必须观察的结果/可接受缺口 |
| --- | --- |
| 空值/空白/默认 factory、注入前使用 | JDK 默认 temp 内任务独占、完成无 payload/task，未改 java.io.tmpdir；仅外部进程可用启动 `-Djava.io.tmpdir=C:\Temp` 约束样本位置，不动态改系统属性 |
| 显式绝对 C/R、中文空格、根不存在 | 样本落到指定根下固定 payload；缺目录按约定创建，任务完成后 root 留存；R 没挂载则明确 SKIP 或安全失败，不模拟卸载真实 R |
| 相对游戏目录、`..`、点路径 | MC stub runDirectory 与进程 cwd 不同，证明解析相对游戏目录；不删除根、其父或兄弟哨兵 |
| 非法路径/Windows 盘相对/根相对、root 是文件 | IOException 含配置与阶段，无默认盘 fallback；不存在合法普通目录与不可用卷分别报告 |
| 写入失败/复制失败/RuntimeException/cleanup-only | 本任务普通中间文件尝试清理；原异常不覆盖，suppressed 可定位；调用者流没 close/flush，可能部分输出需说明 |
| factory 中途失败 | 代码审查每个分配边界；安全可模拟时验证 payload 创建后校验/清理出错。任务目录创建后、payload 创建前失败若无可靠外部注入法，标 UNTESTED，不为该项增加生产钩子或仅测手写复制版 |
| 两任务并发/热重载/factory 替换 | 独占 task 不互删；已建旧句柄仍清旧根，新任务用新根；快照 factory 后用 latch 阻塞其 create、切换新 factory，释放后旧调用仍由旧 factory 返回句柄 |
| 重复 close（含第一次失败） | 已终结句柄不再触盘，不删除该路径后来放入的样本；path() 拒绝关闭后再写 |
| 外来子项/相似命名兄弟 | 只删本句柄确实拥有的 payload，非空 task 保留并报错，用户 root/兄弟/外来子项不动 |
| symlink/junction 替换 | 在外部样本创建时只测试自己的目录，确认不进入目标、不删目标哨兵；权限不允许创建链接则 SKIP，不提权。纯符号链接通过不等于 junction 已测 |
| 无权限/盘不可用/锁定文件 | 不修改真实盘/仓库 ACL，不卸盘、不灌满磁盘；可用外部占用可靠重现则记录，无法重现列 UNTESTED，不以 exists=false 假造权限通过 |

1. 确认 `TemporaryFile` 无 MC/模组引用；Image 无 ReplayMod import/单例；旧四文件之外与第一批工具/caller 的 diff 为零。外部 harness 必须记录所编译生产源码路径（可加 SHA256），报告 actual-production + stub/real-native 的分层标签，不只给总 PASS 数。
2. 专用 mc_gradle 必须用项目自带 wrapper，projectDir=`C:/Users/zhenz/Desktop/ReForgedPlay-1.21`；可先 task=`compileJava`, args=["-Djava.io.tmpdir=C:\\Temp"], timeoutMs=900000；最后必须 task=`build`, args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"], timeoutMs=1200000。即用户要求的 **mc_gradle build -Djava.io.tmpdir=C:\Temp**。这是构建 JVM 参数，不是功能实现，不转为代码里的全 JVM设置。不要 clean、refresh-dependencies、-x test、改构建/依赖或安装包绕错。
3. 记录真实构建退出/任务摘要、产物和警告；NO-SOURCE 不当成测试通过，旧 build 成功不替代本批新 build。工具/环境阻塞则如实报告，不用换栈或新增源码解围。
4. 必须分别报告层1生命周期、层2生产写出+stub、层3生产服务+stub/注入静态核查、层4真实 native 的 PASS/FAIL/SKIP/UNTESTED；不能把 javap 和 build 等同游戏验收。清理失败位置与小样本遗留目录可保留定位，不自动清理历史任务。
5. 若发现安全/异常/实际路径问题，交 Lead/C 在四文件内修订后重验；若不可能在白名单内满足则明确 BLOCKED、停问用户。通过可执行检查后展示 diff/限制，**第二批到此停下，等用户自行 commit**。不进入其他 temp 使用者/路径键/缓存清理，不 Git 写、不游戏启动、不改真实配置。

A 本轮只修改本计划顶部与新第 12 节；未写源码、未构建、未编译 harness。当前可在四文件内实现，没有已证实的代码范围阻塞；真实 MC/native/游戏运行验证是未完成门槛，不把它藏在隔离 stub 的 PASS 后面。给 Lead 原样转告的一句话：这批省的是可丢弃的图片中间文件写入，失败时宁可留下可定位的小目录，也不扩大清理边界，更不拿 stub 的绿灯冒充游戏的绿灯。

### 12.10 第二批真实结果与提交停点（Lead 复核）

- 本批 A/B/C/D 顺序完成：B 增加框架但未接实际写出，C 完成四份源码功能，D 不改仓库文件，仅外部隔离验证和授权构建；第一批工具及 MarkerProcessor 无 diff。Lead 已回读实现、[D 完整报告](<C:/Temp/reforgedplay-D2-20261111-1219/REPORT.md>)、[主矩阵](<C:/Temp/reforgedplay-D2-20261111-1219/harness-rerun.log>)、[补充矩阵](<C:/Temp/reforgedplay-D2-20261111-1219/extra-harness.log>)、[实际 build 类探针](<C:/Temp/reforgedplay-D2-20261111-1219/pure-build.log>)、[构建回显](<C:/Temp/reforgedplay-D2-20261111-1219/build-mc_gradle-output.log>)及 [native 异常链](<C:/Temp/reforgedplay-D2-20261111-1219/native-probe-matching.log>)。
- 生效范围：`advanced.tempPath` 默认空字符串，空白选择 JDK 默认临时根；非空值按原值解析，绝对路径直接使用、相对路径相对游戏目录。非完整 Windows 盘路径及非法路径明确失败，不静默回退。不修改全 JVM java.io.tmpdir，不修改实际游戏配置或 GUI 设置页。
- 每次 `Image.writePNG(OutputStream)` 快照 factory 并创建独占 `.jgui-image-*` 任务及 `image.png`；写入/复制前检查边界，TWR 终结本任务。成功关闭后不留任务文件/目录；清理失败仅报告定位，不越过归属删除 root、哨兵、外来子项或链接目标。原异常保留，清理错误 suppressed；调用者流不 close、不 flush。File 重载与其他图片 API 不变。
- **层1：生产 TemporaryFile 生命周期 13 PASS / 1 SKIP**，另有实际 build class、无 MC/stub classpath 的探针 PASS。C/R/default、中文空格、独占/无残留、重复 close、失败后 terminal、缺失任务、外来子项、NOSHARE_DELETE、junction 原位置换拒绝及 root 别名 canonical 化均实测。符号链接创建因权限跳过，不提权。
- **层2：实际 Image 控制流＋外部 encoder stub 15 PASS**。验证字节复制、factory 单次 create/并发快照、默认注入前行为、两任务隔离、编码 IOException/RuntimeException/Error 原异常 identity、流失败、primary＋suppressed、cleanup-only 诊断及输出流生命周期、junction 编码前拒绝。stub 不代表真实 PNG/native 编码通过。
- **层3：实际 folders/Setting＋外部依赖 stub 20 PASS**，真实 ReplayMod 源码/构建字节码核查 PASS。覆盖 blank、相对 mcDir、中文空格、C/R、非法/盘相对路径、不 trim 非空原值、单次 get、设置变化只影响新任务、错误不回退、service method reference 实际贯通。只读确认构造器在 folders 初始化后、I18n/模块之前注册一次，不启动真实 Backend/watcher。
- 主矩阵最终 **44 PASS / 0 FAIL / 1 SKIP**，补充 **4 PASS / 0 FAIL**；以上分层统计已包含补充，不能再重复相加。首轮外部 harness 错把 Windows 目录段尾随空格当合法输入，生产正确拒绝；D 只修测试预期并保留旧记录，未改生产源码掩盖失败。主矩阵日志含非法路径样本 NUL，通用 read 会判二进制，Lead 用原生 UTF-8 文件检查工具逐行回读而未修改原始证据。
- **层4：真实 Minecraft/native PNG 未验收**。匹配现有缓存依赖、真实 build Image＋named NativeImage 的独立 Java21 探针在 NativeImage 构造时报 `UnsatisfiedLinkError: Failed to locate library: lwjgl.dll`，未进入 PNG 编码。没有补下载/解压安装 native、没有启动游戏。此结果是独立探针环境受阻，不是已证实的游戏加载失败，也绝不是 native 编码通过。
- 指定 Oracle Java/Javac/Javap 21.0.12.1；专用 mc_gradle task=build，参数 `-Djava.io.tmpdir=C:\Temp --console=plain`：**BUILD SUCCESSFUL in 53s；6 actionable tasks: 4 executed, 2 up-to-date**。未 clean、refresh 或跳测试；NO-SOURCE 不是 JUnit 通过。仅工具文本未提供 exitCode/timedOut 数值，不虚构；已核对真实 UTF-8 daemon 日志。保留既有旧 API/Loom/Gradle 弃用及 JOML/WorldRenderer Mixin 重映射提示，未越界修复。
- **仍未测**：task 已分配至 CREATE_NEW payload 之间的可靠冲突与该时点 allocation cleanup suppressed、ACL 拒绝、满盘、卷消失、真实 watcher/Backend/游戏生命周期及真实 native 编码。未拥有 payload 时不删外来文件已作源码/字节码审查，不能称动态故障覆盖。路径检查仍依赖可信父目录，不承诺排除全部 TOCTOU；断电/进程崩溃可能留下临时目录，本批不加扫描清理。
- 仓库交付仅本计划及 [Setting.java](<src/main/java/com/replaymod/core/Setting.java>)、[ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>)、[Image.java](<src/main/java/de/johni0702/minecraft/gui/versions/Image.java>)、[ReplayMod.java](<src/main/java/com/replaymod/core/ReplayMod.java>)，无新增源码文件，不改依赖/实际配置/仓库测试/第一批代码，不执行 Git 写操作。源码差异为 Setting +1、Folders +31、Image +225/-6、ReplayMod +2，计划统计随验证记录增加。
- 输出 [正式 JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>)；D 记录体积 17418352 字节、SHA-256 `410a30fde49536ce275ec1cc8992b9e4ff75ad5f8be1e05575679e3749fd7aea`。本批交付是源码/隔离控制流/构建检查点，不是全游戏可用性签字。
- 配置示意（不是实际文件修改）：在现有 advanced 对象合并 `"tempPath": "R:/ReForgedPlay/temp"`，或 `"tempPath": "./replay_temp"`；空值保留默认位置。不覆盖其他设置，不把它理解为所有模组/所有临时写入都被接管。本批仅图片流输出中间文件，其他 temp 使用者留后续独立批次。
- 仓库外证据约 0.7MiB，R 侧仅保留测试哨兵16字节及空根目录，无 `.jgui-image-*` 任务残留；测试 junction 已移除。证据保留，代理不擅自清理其他缓存。**第二批到此停止，等待用户自行 commit；不自动进入后批。**

D 原话：测试的绿灯只照到确实跑过的边界；这次没有用 stub、成功构建或被保留的小哨兵，替真实游戏和 native 编码签字。

## 13. 第三批重规划：资源包 ZIP 接 tempPath 与真实释放（仅规划，待用户确认）

### 13.1 授权、基线与结论

- **仅授权 A 规划，当前源码白名单为空；以下均为候选。** 本轮唯一文件写入是本计划顶部与本节。未实施、构建、测试、装包、改配置/依赖/.env、Git 写、游戏启动或接触 whale-craft。前次被停止、无 closing report 的第三批委派不视为完成。
- 核实记录：本轮按实际工作区与提交核查，不将代理推断的日期/时区当成用户提供的信息。只读 HEAD=`2903157`；规划前 git status --short 无输出。前两批已提交为用户确认；本轮不读实际设置内容，不改设置。
- 先识别时代：[build.gradle](<build.gradle#L1-L11>) 为 Architectury Loom `1.7-SNAPSHOT`、Java21；[gradle.properties](<gradle.properties#L6-L20>) 为 MC1.21.1、NeoForge21.1.82，Yarn1.21+build.1 加 Architectury layered patch；[metadata](<src/main/resources/META-INF/neoforge.mods.toml>) 为 neoforge.mods.toml，[wrapper](<gradle/wrapper/gradle-wrapper.properties#L3>) 为 Gradle8.8。现代 Java21 线，保持现有栈，不 scaffold、不改 ModDevGradle、不升级。实际 javap -version 为21.0.12.1，不用 PATH 上其他 JDK。
- 技能边界：A 本轮未加载技能正文，不声称加载成功。Lead 已在恢复任务时加载 minecraft-java-build 与 minecraft-neoforge-mod，并核对既有 Loom/Java21/NeoForge 栈；不把技能的新项目模板用于本项目。API 判断以本项目 named JAR 字节码为依据，Lead 也已复核 Loader 的方法签名与转发逻辑。
- **结论：双流 TWR、complete-only ZIP 发布、多 ZIP 与路径快照可以小范围设计；完整“接 tempPath＋播放保留＋真实释放后清理”仍 BLOCKED。不能证明两源码足够；申请 ReplayHandler 第三源码也不自动得到资源释放确认。** 不先写代码再用 stub 掩盖缺口。

### 13.2 聚焦源码事实（本轮只读）

| 源码证据 | 行为与影响 |
| --- | --- |
| [FullReplaySender 字段/构造器](<src/main/java/com/replaymod/replay/FullReplaySender.java#L270-L288>) | L274 字段初始化 Files.createTempDir 早于主体；L285 元数据读取、L287 事件注册可失败，任务分配不能在构造失败时失去归属。 |
| [资源包分支](<src/main/java/com/replaymod/replay/FullReplaySender.java#L605-L635>) | L623 拼 hash.zip，L624 仅 exists 判断，L625 IOUtils.copy 的输入和输出均未显式关闭，L628 排队 add。半写 ZIP 会被下次误当完成；hash 未校验是路径输入。这里抽取整个 ZIP，不解 ZIP entries，不新增通用 unzip。 |
| [调度](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1317-L1331>) | schedulePacketHandler 主线程直接执行，否则 mc.execute，返回 void，无撤销/完成句柄。必须在 runnable 真执行时再查 terminal/generation，不能只在入队时检查。 |
| [终止](<src/main/java/com/replaymod/replay/FullReplaySender.java#L350-L364>) | if terminate return → syncSender.shutdown → events.unregister → pipeline inactive/close → deleteDirectory。没有 worker 完成、MC 卸载或重载完成屏障；网络关闭不是文件释放。共用 try 前项失败还可能跳过后项。 |
| [模式切换](<src/main/java/com/replaymod/replay/FullReplaySender.java#L300-L330>) | terminate 同时控制 async 停机：切 sync 置 true，切 async 又置 false；不能当永久关闭幂等标记，否则切 sync 后终止可能直接返回，关闭后又可复活。 |
| [async](<src/main/java/com/replaymod/replay/FullReplaySender.java#L994-L1109>) / [sync](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1177-L1289>) | async 持 sender monitor，restart 使用 runSync；sync submit 后主线程 drain 队列。shutdown 不等待已排队任务；主线程 join/awaitTermination 或持 monitor 等 MC future 易死锁。autoRead 等待也需关闭退出边界。 |
| [ReplayHandler 构造/重启](<src/main/java/com/replaymod/replay/ReplayHandler.java#L195-L242>) | sender 构造后 quick sender/setup/overlay/opened callback 仍可失败；restartedReplay 是 seek 的同 sender 重建，close channel→disconnect→setup，不是最终退出，不可删除整个 sender ZIP。 |
| [endReplay](<src/main/java/com/replaymod/replay/ReplayHandler.java#L244-L293>) | closing callback→sender terminate→ReplayFile save/close→channel close await→world 非空才 disconnect→closed callback。当前删 ZIP 早于 disconnect；callback/save/close 失败可跳过卸载；world null 不等于无资源包。ReplayFile 所有权在 handler，sender/helper 不关它。 |
| [setup](<src/main/java/com/replaymod/replay/ReplayHandler.java#L405>) | provider.init(networkManager, ALLOWED) 在新连接建立时执行，旧卸载回调不得清除新连接的资源。 |
| [现有 tempPath](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java#L25-L52>) | 图片入口已有 default/绝对/游戏相对/失败不回退语义；资源包入口独立复用语义，不借固定 image.png 的 Image.TemporaryFile，不重构前两批。 |
| [hash 来源](<src/main/java/com/replaymod/recording/packet/ResourcePackRecorder.java#L83-L106>) | recorder 使用 SHA1 hex；读取端仍不信任回放 index。 |

### 13.3 实际 named MC JAR 与 Java21 API 证据

本轮使用 **C:/Program Files/Java/jdk-21.0.12.1/bin/javap.exe** 的 -p -c，指向 [实际 named MC JAR](<C:/Users/zhenz/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/neoforge-21.1.82-minecraft-merged/1.21.1-loom.mappings.1_21_1.layered+hash.426444933-v2-neoforge-21.1.82/neoforge-21.1.82-minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.426444933-v2-neoforge-21.1.82.jar>)，不是 intermediary/mojang JAR。先按 ServerResourcePackProvider 猜类名得到 class not found，随后同 JDK jar tf 核实：实际是 **net.minecraft.client.resource.server.ServerResourcePackLoader**，MinecraftClient.getServerResourcePackProvider() 返回它。保留该纠正，不能根据 getter 名猜类型。

| 方法 / 字节码 | 可证明与不能推论的内容 |
| --- | --- |
| Loader.addResourcePack(UUID, Path): **void**，偏移0–9 转发 manager | 仅提交路径，不返回加载/卸载 future，manager PackEntry 保留 path；不意味着 MC 已复制到自己的文件。 |
| Loader.remove(UUID)、removeAll(): **void** | 无释放确认；只移除 lease 登记的 UUID，不能为本任务调用全局 clear/removeAll 干扰别的包，UUID 重用须有代次归属。 |
| Loader.clear(): **void**，0–7 manager.removeAll，7–14 换 callback，14–21 resetAcceptanceStatus | 不是关闭 ZIP；还会替换 getPackLoadFuture 的观察 callback 链。 |
| Loader.close(): **void throws IOException**，0–7 仅 downloader.close | 是下载器关闭，不是 active packs 释放屏障；不能关闭 MC singleton loader 来清本任务。 |
| Loader.getPackLoadFuture(UUID): CompletableFuture<Void>；Loader$7.onFinish：22–38 仅 APPLIED 正常 complete，41–68 其他 finish exceptionally | **有加载 future，但不是卸载 future**。APPLIED 时正在使用，不能删除；不能拿 clear 后这个 future 推断释放。 |
| Loader.reload(ReloadContext): void，63–71 换 packProvider，71–79 调 mc.reloadResources 后 pop | MC 有重载 future，但 loader 丢弃返回值，没有直接给调用者的 per-lease 卸载句柄。 |
| Manager.update/applyDownloadedPacks；ReloadScheduler.scheduleReload(ReloadContext): void | remove 标记、manager update、安装排除旧包的 context、reload 是不同步骤；PENDING 可延迟。不能 remove 后随手 mc.reloadResources 就假定 provider 已排除旧路径。 |
| MC.reloadResources(): CompletableFuture<Void>；私有方法0–11 可直接返回已有 resourceReloadFuture；24–40 SplashOverlay 时排后续 | 任意重载 future 完成不等于 remove 之后正确一轮完成，必须有因果关联与旧/新代次屏障，不能裸 clear().thenRun，clear 本来就是 void。 |
| MC lambda$reloadResourcePacks$20：14–21 loader.onReloadSuccess，21–26 complete；Loader.onReloadSuccess：7–16 context.onSuccess | 有成功回调链，但公开 API 未返回本 lease 专属卸载确认。 |
| ReloadableResourceManagerImpl.reload：20–29 activeManager.close，再建新 manager；close():0–9 activeManager.close | 这才有实际资源管理器 close 调用。仍需证明 ZIP pack close 链、在途 reader 退出、旧 provider/待加载任务不能重开；仅看到 close 调用不能宣称全部句柄释放。 |
| MC.disconnect(Screen,boolean) 仅有 network handler 且非重配置时调用 onDisconnected；onDisconnected:0–7 loader.clear，7–11 runTasks，均 void | disconnect 返回不是资源重载/文件释放屏障；无 world、异常和 seek 独立覆盖。 |

可复查本轮 [Loader/Manager javap 回显](<R:/Temp/dsh-subprocess-e8qBMh/dsh-subprocess-21728-1-5e6fdff64365-stdout.log>) 与 [MC reload/disconnect javap 回显](<R:/Temp/dsh-subprocess-e8qBMh/dsh-subprocess-21728-2-35e2da8f57f4-stdout.log>)；Loader$7 与 ResourceManager 为本次另一只读工具调用的完整对话结果，未另存报告。spill 为工具临时证据，持久复查依据是上面精确 JDK/JAR/类/方法/偏移。本轮无手工仓库外写入。

Web 辅助证据：[Yarn1.21+build.1 Loader JavaDoc](<https://maven.fabricmc.net/docs/yarn-1.21+build.1/net/minecraft/client/resource/server/ServerResourcePackLoader.html>)，2026-11-12 搜索后抓取 HTTP200，签名与实际 void / getPackLoadFuture 一致；这是配置映射基线，不是另版本或 actual NeoForge 执行证明，实际 bytecode 优先。

**BLOCKED/UNVERIFIED 组合屏障：** 本 lease 全部 path/UUID 已由 manager/provider 排除，正确 reload 已关闭旧 packs，所有旧 reload/context/排队 add 不会重开，异步 reader 已释放。Windows delete 成功不能反证没有异步读者，Unix 可 unlink 更不是关闭证明。第三源码只修生命周期接线，不填补该 API 缺口。

### 13.4 最小可实现阶段与停点

**A3 本轮：出本计划后停下。** 不让 B/C 默认先动两文件；用户先选完整目标继续取证/白名单，或明确接受缩批。

**B3 未来授权后，先证据 gate 再 skeleton：** 定点复核本 JAR 的 manager remove/context 更新、正确重载关联和 packs/reader close 链；只用公开 API＋三源码能证明才提交具体时序。证明不了停问 Lead，提出精确 hook，不写“应该已释放”，不扩研究到其他版本/目录。获批后 skeleton 定义窄 lease/terminal/generation 契约，不能用 completedFuture 假装 MC release。

**C3 完整方案仅在 gate 和白名单均获批后：**

1. 新资源包任务入口一次读取 advanced.tempPath；blank/null 为 JDK 默认根，非空保留原字符串、不 trim/展开环境变量，绝对/游戏相对和错误诊断复用现有语义。每 sender 一次快照，建议必要元数据读取成功后再分配（可延迟实际建目录，但配置不可每 ZIP 重读）；seek 不换根。同 sender 多 ZIP 共一个独占任务，用户 root 不是 lease。
2. lease 为窄纯 JDK 嵌套类型候选，登记自身 staging/ZIP；不用 Image.TemporaryFile，不改全局 java.io.tmpdir，不依赖 FileStore/DOS/fileKey，不建泛化框架，不改图片 factory。
3. hash 严格40位 ASCII hex（大小写规范明确），先拒绝路径分隔符、冒号、点段/绝对/盘相对；resolve normalize 后必须为任务直接子文件。missing index/id/Optional absent 明确失败不加载，不信任回放输入。内容可按 SHA1 校验以拒绝 index/bytes 不匹配，但这不等于 MC metadata 验收。
4. 每 hash 独占 CREATE_NEW staging；输入、输出均 TWR，输出先关输入后关，绝不关 ReplayFile。copy/flush/close 任一失败都不可 READY/排队。双流成功关闭并验证后同目录 move 到完成名，不覆盖外来文件；不强制原子 move 或静默跨根回退，无发布前 MC 观察者。成功后才记 in-memory READY，exists 不算完成；同 hash 串行或共享单次结果，失败可重试不复用半文件。两 hash、多 UUID 保持独立。
5. 独立永久 terminal，不再复用 async terminate；设计 ACTIVE→STOPPING→WAIT_RELEASE→RELEASED→CLOSED/cleanup-failed，带 packet/session generation 和 in-flight producer/add 计数。stop 后不新增 path/MC add，排队 runnable 执行时查 terminal/generation/当前 sender；关闭后 setAsyncMode/sendPacketsTill 不可复活。锁/计数不跨 MC 等待，主线程不得 join/await 死锁。
6. seek 提升 packet generation 令旧排队 add 失效，但保留同 sender ZIP；旧代次已提交加载仍是 release 债务，最终屏障须全部覆盖。旧清理回调不能 remove 新包，两个 sender/回放不能互删。
7. handler 最小接线涵盖构造中 sender 创建后的失败、正常 end、early restriction terminate、world null、callback/save/close 失败与 seek。先停止新工作，再协调卸载，保留 ReplayFile 原有所有权，不重写保存体系。未向 MC 提交且无在途写的任务可受控清理；已提交的必须等释放 gate，catch/finally 不盲删。原异常保留，清理/卸载失败定位并 suppressed/日志，不让前项失败静默跳过终结请求。
8. **删除条件同时成立：** terminal、全部生产者退出、排队 add 失效且在途 add 完成、全部已提交路径/UUID 的正确卸载重载已确认且异步引用不再重开。只删登记普通 staging/ZIP，再删空 task；不递归扫用户 root/历史目录/兄弟/外来子项，不跟 links。触盘校验真实边界，symlink/junction 置换失败保留，不把可信父目录检查夸大为彻底 TOCTOU 防护。
9. release 异常/取消/悬挂/关联不明则 WAIT_RELEASE＋诊断，不删、不报完成；cleanup 失败留可定位本任务文件，重复终结不得删同路径后来放入的新文件。不增加启动扫描、deleteOnExit/GC/Cleaner/finalize 替代释放。失败保留是容错，不是偷偷取消清理承诺，缺 gate 则完整目标 BLOCKED。

**可选缩批 S（必须用户另行明确批准）：** 仅 [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>) 的读取 hash/path 校验、双流 TWR、complete-only ZIP 发布/复用；不接 tempPath，不新增/加快删除，明确保留原 terminate 的安全风险，不称“清理已安全”。更小可选仅双流 TWR，不改其他语义。缩批均不满足本次完整目标；若用户要求本批必须真实释放后清理，就不走缩批，保持阻塞。

### 13.5 精确候选源码与文件归属（全部待确认）

| 文件 / 范围 | B skeleton | C content | D review / 授权状态 |
| --- | --- | --- | --- |
| [plan.md](<plan.md>) 顶部与第13节 | 只读 | 只读，方案变更交 Lead | A 本轮唯一写者；后续 D 结果经 Lead 授权补录 |
| [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>) | 资源包 task factory/窄嵌套 lease 契约 | 快照、受控归属、非递归清理 | 完整方案候选；现有图片方法 diff 为零 |
| [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>) | terminal/generation/lease 契约 | 多 ZIP/TWR/发布、排队 add 防陈旧、生产者终止与释放协调 | 完整或缩批候选，范围不同，不默认授权 |
| [ReplayHandler.java](<src/main/java/com/replaymod/replay/ReplayHandler.java>) | 构造失败/seek/最终关闭接线 | 关闭与 MC 卸载时序、错误不跳终结 | **申请第三源码理由**：sender 看不到 setup 后失败、endReplay 中断/无 world 路径及 seek vs 最终关闭；必须用户批准，增加它不保证 gate 已解决 |
| 尚不存在的候选顶层 `src/main/java/com/replaymod/core/files/ReplayResourcePackLease.java` | 不创建 | 仅嵌套 lease 难以隔离验证时先申请 | 非白名单，优先三源码内窄嵌套；不为测试擅自加文件 |
| 尚不存在的候选 `src/main/java/com/replaymod/core/mixin/Mixin_ReplayResourcePackRelease.java` 及现有 [mixins.core.replaymod.json](<src/main/resources/mixins.core.replaymod.json>) | 不创建/不编辑，先给精确方法/注入点 | 公开 API 无法闭合 gate 时再申请 | **不属三源码提案，不保证此一个 hook 足够**；需证明观测“排除本路径后那一轮”，不是任意 onReloadSuccess，不申请无限权限 |
| 其他源码/图片/第一批工具、Setting、ReplayMod、构建/依赖/metadata/配置/.env/仓库测试 | 全只读 | 全只读 | D 不修源码，问题交 C/Lead 在获批范围修 |

后续严格 A→用户确认→B→C→D：A 已交证据与阻塞；B 先闭合 gate，再在选定白名单做 skeleton；C 实施已批准切片；D 另行获准后做外部验证与构建，只读审查仓库，不默认有修复权限。未选方案前不启动 B/C/D。

### 13.6 后续 Java21 外部隔离验证与游戏缺口（本轮未执行）

- 匹配 Oracle Java/Javac/Javap21.0.12.1 绝对路径；未来另经授权在唯一 C:/Temp 外部任务存 harness/classes/report，不写仓库测试、不装包；R 样本仅卷已存在且用户允许。编译/加载**真实生产 lease 代码**，MC 外部 stub 仅补依赖面，不复制手写生产实现代测。
- 纯 JDK：default/绝对/游戏相对/中文空格/热更新单次快照、不合法盘相对/不可用根无 fallback；两个 sender/同 sender 多 ZIP、同 hash 复用、缺 index/流、恶意 hash；copy/close/move 失败不可 READY，半写重试、输入输出 close 计数、ReplayFile 不关。
- 受控清理：构造前/后失败、重复终止、cleanup 失败后同路径新文件、外来哨兵/兄弟/未知历史目录、symlink/junction 置换、可可靠制造的 Windows NOSHARE_DELETE；root/外来文件不删。ACL/满盘/卷消失不能改真实环境强测，无法复现标 UNTESTED。
- 控制流：可控 executor/latch/future 验证写中终止、add 排队/执行中终止、seek 后旧 add、切 sync 后结束、关闭后再切 async/submit、多 UUID/旧 reload 晚完成、release 失败/取消/永不完成。gate 之前 ZIP 必须保留，生产者退出且正确 gate 后才清；stub future 只能证明本模组控制流，不能证明 MC 具有该 gate。
- 字节码：复核实际编译 classpath 与上述缓存 JAR，一致性未由本轮构建证明。javap/static 证据不等于实际关闭 reader，不能先用 stub 补 gate 再报完成。
- **真实 MC 验收另经授权**：至少两个不同 ZIP 的回放，async/sync/seek/quick/重配置、加载中退出、正常结束、构造/回调失败、无 world 退出、新 replay 紧跟旧 replay、同时手动重载及重载失败恢复。观察播放可用、全部旧 provider/reader/句柄释放后才清自有 ZIP/空 task，其他包/哨兵不动。不能用 stub、delete 成功、固定延时、网络关闭或 build 成功代验收。本轮禁止启动游戏，此层明确 **UNTESTED/BLOCKED**。
- **仅供未来用户授权 D 的构建步骤**：mc_gradle 使用本项目 wrapper，projectDir=`C:/Users/zhenz/Desktop/ReForgedPlay-1.21`；可先 task=compileJava timeoutMs=900000；最终 task=build args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"] timeoutMs=1200000。不 clean/refresh-dependencies、不改栈、不 -x test，NO-SOURCE 非测试通过。本轮规划授权不包括执行，旧批 build 不替第三批结果。

### 13.7 风险、用户选择与关闭报告

1. **首要阻塞是没有已验证的 per-lease 卸载/释放屏障。** 加载 future 不等于释放，loader clear/close 不等于 active pack close；三源码接线仍要先补证明，若须新 helper/mixin/配置必须再申请。
2. terminal 与 async terminate 混用、shutdown 不等完成、主线程 runSync/drain 死锁、旧 add/reload 穿越 seek/session 均必须处理，不能为维持“两文件”数字假装不存在。
3. 保留多 ZIP、单 sender 根快照、半写不复用、不关不归己 ReplayFile、无全局 tmp/回退、无 FileStore/DOS/fileKey 硬依赖、受控非递归清理是硬边界；没有释放证明只能保留诊断，不能宣称已实现即时清理。
4. 环境固定 Java21/Loom1.7/NeoForge21.1.82；未读真实设置、未新构建/测试。现有 [ReplayStudio JAR](<libs/ReplayStudio-master-SNAPSHOT-slim.jar>) 不改/不替换，不为本批扩展依赖或其他存储事务。
5. 请用户选择：**完整目标保持阻塞，授权定点释放证据及三源码候选审查**；或**明确接受缩批 S 的有限效果和遗留风险**。没有选择就停，不默认进入下一阶段。

A closing report：本计划已落盘，本轮仅规划；已准确区分 void / 加载 future / MC 重载 future，完整实现与游戏验收均未完成。给 Lead 原样转告：宁可把释放边界写成阻塞，也不把删得掉文件冒充 Minecraft 已经用完。

### 13.8 Lead 复核与本轮规划停点

- Lead 已回读第13节、复核真实 Loader javap 回显（包括 clear 的 removeAll/回调重设与 close 仅关闭 downloader），与 endReplay/发送器排队源码交叉核对。确认当前缺的是与本任务卸载因果关联的释放证明，不是声称 Minecraft 没有任何资源关闭机制。
- 修正了 A 未获用户提供却写成“用户提供”的日期/时区归因；技能加载由 Lead 完成，不冒称 A 已加载。
- 本轮交付仅计划；git status/diff 核实业务源码、IDE配置、依赖、测试均未改动。文档检查运行 git diff --check；没有新 Java 代码，因此不复用第二批 build 当第三批验证，也不声称运行过第三批测试。
- 建议下一步继续定点只读取证，先给出可证明的释放接入点和准确文件范围；若用户更重视立即交付，可明确选缩批 S（甚至仅双流 TWR），但必须承认它不实现 tempPath 和安全即时清理。当前不默认选缩批，不将候选源码列成已授权。
- 本轮 A 规划目标完成，业务实现未完成。停在方案选择/文件范围确认之前，不启动 B/C/D，不执行 Git 写操作。

### 13.9 用户授权的深入取证与证据等级（不是第三批实现）

本轮用户明确选择“继续查完整方案”，允许联网和充分取证。基线只读核实 HEAD=d491135，研究开始及四名审查者结束时工作区 clean。Lead 已加载 minecraft-java-build/minecraft-neoforge-mod，三名独立研究者分别核查 ZIP 关闭链、Loader/MC 重载因果时序、NeoForge 公开接口，再由第四名独立审查者反例审查。研究者均未改文件、创建临时Java、构建、安装依赖或启动游戏。**本轮实际交付仅本计划；正式源码仍在工作区 src，工具临时目录中的内容是字节码/命令证据，不是模组实现。**

所有下列偏移均是本项目 actual named MC1.21.1/NeoForge21.1.82 JAR 的字节码偏移，不是源码行号；工具固定 Oracle Java21.0.12.1。NeoForge 事件依据本机21.1.82 sources JAR和合并JAR；网上某些标为1.21.x的JavaDoc实际是21.0.30-beta，未拿它冒充本版证据。

| 已独立复核的新事实 | 对设计的直接约束 |
| --- | --- |
| ZipResourcePack$ZipFileWrapper.close()V @11 closeQuietly(zip)、@14–16 zip=null，不置closed；open() @16–28可new ZipFile，两方法均未同步 | close RETURN不是永久关闭证明，且并发open可能丢失一个rawZip引用；owned路径必须完整串行化open/close或使用显式许可计数，不能只挂TAIL |
| ZipBackedFactory.open与openWithOverlays各建fresh wrapper；base/overlays共享本次wrapper；metadata扫描和active使用不同wrapper | 按实际rawZip identity记账，不能按pack close次数计数；旧profile还可fresh创建wrapper |
| InputSupplier.create(ZipFile,ZipEntry)捕获rawZip；迟到supplier只调该raw.getInputStream；迟到Resource/pack.open则可能再次走wrapper.open | 成功关闭raw可封住既得supplier；仍需独立封禁现有及未来wrapper重开 |
| JDK21 ZipFile.close同步清理其getInputStream所得流并Source.release；Source可能被同路径多个ZipFile共享 | 不再要求join所有reader线程/计算任务；必须关闭全部实际rawZip，不只是一个pack |
| JDK21 close先置closeRequested；cleanup IOException后第二次close可直接return | 从ACTIVE期每个raw的第一次close就记录sticky失败；retire重试空返回不能洗成成功 |
| Loader.reload(C1)先换当前context/provider，MC因R0 SplashOverlay仅排队；R0成功却调用Loader当前C1.onSuccess | context.onSuccess、Loader.onReloadSuccess或随手取得的future都不能确认C1真正卸载；已找到明确反例，不是笼统担心异步 |
| MC私有reloadResources在@52取得最终packs、@92调用resourceManager.reload；$20先@23 F.complete，再@29 onFinishedLoading、@32 RETURN | 必须关联真正开始的cycle与实际packs；只挂F.thenRun甚至可能在成功回调末尾之前运行，不足以完成释放事务 |
| ResourcePackManager.addPackFinder(ResourcePackProvider)是本版public synchronized；required/hidden profile及PackFactory均公开 | 无Mixin替代路线真实存在，不再笼统声称只能用Mixin；但注册接口不等于替代server loader语义已经完成 |
| RegisterClientReloadListenersEvent才是本版事件；只在构造期注册listener，listener每轮运行；未找到本版ClientResourceLoadFinishedEvent | 不引用别版本不存在的事件；单listener不是全轮成功或ZIP关闭通知 |

主要可复查证据：[Loader/Manager及context字节码](<R:/Temp/dsh-subprocess-e8qBMh/dsh-subprocess-21728-3-124fa39947ee-stdout.log>)、[MC actual-cycle和回调](<R:/Temp/dsh-subprocess-e8qBMh/dsh-subprocess-21728-5-204185c05b87-stdout.log>)、[Resource/Profile/factory/InputSupplier](<R:/Temp/dsh-subprocess-e8qBMh/dsh-subprocess-21728-4-bd81234c4891-stdout.log>)、[JDK21 close/Source/streams](<R:/Temp/dsh-subprocess-e8qBMh/dsh-subprocess-21728-6-cbc23db2f48e-stdout.log>)、[wrapper精确open/close](<R:/Temp/dsh-subprocess-e8qBMh/dsh-subprocess-21728-7-5cdb2c9e5992-stdout.log#L1244-L1317>)。Lead 已独立复核 wrapper、MC $20及public addPackFinder；并fetch [Java21官方ZipFile.close契约](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/ZipFile.html#close()>)。这些自动spill不是持久源码；后续实施前须用本节固定JAR/类/descriptor重新核查，不能靠临时日志一直存在。

### 13.10 路线比较与首选P

| 路线 | 静态可行性与代价 | 本轮选择 |
| --- | --- | --- |
| P：保留server loader，窄观察ownership/context、actual cycle，接管本lease的ZIP open/close | 保留vanilla send/pop/排序/状态/overlay/metadata；不修写其通用重载行为。需要三类Mixin职责及窄桥接 | **首选实施候选**，独立反例审查同意；不是已编译/已注入/已游戏验证 |
| D：public addPackFinder＋长期dynamic provider＋自有PackFactory/托管archive | API确实存在；可只枚举自有required/hidden profiles，不写options。但需重新接管replay UUID/pop/顺序/status、失败恢复和overlay/metadata/NeoForge扩展语义 | 保留替代，不为少几个文件扩大语义变更；不称不可能 |
| F：仅替换Loader.toProfiles唯一NEW ZipBackedFactory为managed factory | 工厂公开非final，但wrapper.open package-private、zip私有；只包delegate.close仍吞错/可重开。若不用wrapper hook，需新访问机制或自写pack/ZIP实现 | 不是“一个NEW hook免费闭合”；不选作生命周期方案。P可用纯跟踪factory适配器标记实例身份，但不替代ZIP门 |

首选P的范围是本模组私有任务路径、已识别的本版MC ZIP链和协调的Replay调用；不承诺拦截任意第三方代码直接new ZipFile/绕过MC开本路径，也不防恶意父目录替换。保持可信父目录与NOFOLLOW/真实边界检查，不宣传操作系统沙箱。

### 13.11 P的完整释放事务与必要修订

以下是**拟实现契约，不是已经存在的API或运行结果**。资源包lease仍建议作为ReplayFoldersService内窄静态嵌套纯JDK类型，MC耦合留桥接；不借用图片image.png句柄，不修改前两批，不扩展为通用存储框架。

1. **路径/发布门：** 每sender一次tempPath快照；独占task、多ZIP、严格hash、双流TWR、同目录complete-only发布沿用13.4。任何copy/flush/close/move失败均不入READY、不排队加载，只有登记的完整ZIP对MC可见。任务源流由其TWR关闭，ReplayFile整体所有权仍在handler。
2. **停止门：** 新增不可逆terminal，区别async terminate和seek代次。terminal之后拒绝新的packet/复制/actual add；排队runnable真执行时再查owner/session/packet generation，finally释放许可。已进入的resource-pack copy按终止/失败收尾，不得在结束后发布或加载；记录真实生产者与actual-add在途，不拿executor.shutdown当退出确认。旧runSync restart/setup回调也须检查terminal，不能回头重建已结束回放。
3. **UUID归属门：** 在Loader实际add(Path与URL)/remove/removeAll/clear/init执行点观察，票据至少(loader实例、session/connectionEpoch、UUID、addSequence、leasePath)。非replay add也会替换ownership；旧release只CAS移除自己仍持有的票据，被新owner替换时不remove新owner，但旧路径的释放债务仍须结清。不得主动调用全局clear/removeAll、关闭singleton loader或改用户enabled列表。
4. **provider证据：** 冻结每次真实安装provider的不可变快照及revision，覆盖正常reload、metadata重试回退、onReloadFailure与forced failure。不在失败后重读已变ctx冒充原快照。provider必须排除lease所有路径及其有效旧代次。
5. **actual cycle证据：** 采集createResourcePacks最终返回的实际pack对象列表，而非仅profiles；现有Mixin_InjectDynamicResourcePacks会追加Lang/jGui，不能忽略。区分每轮本地F、cycleId、实际安装序号；resourceManager.reload确实执行并正常返回才标安装。只有该轮$20正常RETURN才标SUCCESS，异常/取消/失败/关联不明保留。仅历史排除成功不够，必须是当前安装manager，或此后所有安装manager也已证明排除。
6. **身份关联不能猜：** 拟在Loader的ZipBackedFactory构造点对owned路径使用桥接内薄跟踪子类，openWithOverlays调用super、记录返回的最终ResourcePack identity→lease/path；metadata仍走vanilla，archive生命周期由wrapper hook负责。不是自写pack，也不能用server/%08X/%s字符串解析代替身份。需在B证明该适配器覆盖实际owned pack列表、失败及overlay；若需额外Profile Mixin/源码，必须停下重新申请，不暗增第9个实现文件。
7. **ZIP门：** 所有owned wrapper的open/close与SEAL由同一lease协调。推荐短状态锁＋显式open/close许可：任何raw在返回MC前已登记，new/close若在锁外必须有opens/closesInFlight与OPEN/CLOSING/SUCCESS/FAILED，不能HEAD检查后锁外new而不计数。按wrapper与raw identity登记，ACTIVE普通close不封存，允许合法后续重载。非owned路径完整保留vanilla。
8. **首关收据：** owned close不得依赖closeQuietly；从ACTIVE期第一次raw.close起保存原始结果。失败sticky，后续空close返回不解除失败，不以GC/Cleaner/finalize或再次delete成功代证。JDK成功raw.close已关闭关联streams；不增加不必要的每InputStream引用计数，不等所有reader线程join。既得supplier持closed raw不能重开；迟到pack或fresh factory通过路径门被拒绝。
9. **终局事务：** terminal且producer/add许可归零→guarded ownUUID remove→provider确实排除→当前实际成功cycle排除且无有效晚到安装者→主线程验证后不可逆SEAL→异步协调关闭所有登记raw（包括metadata及未安装旧pack）→首次close全SUCCESS、无sticky poison、无在途open/close→只删登记普通ZIP/staging及空task。不存在MC等待时持lease锁，也不主线程join/await；关联不明、失败/挂起都WAIT/诊断，不设固定延时后强删。
10. **晚到任务的范围：** Lead另查getReloadScheduler的BootstrapMethods#2，实际为直接REF_invokeVirtual Loader.reload，不是把ctx另存到未知线程的闭包；排队的是Loader$5驱动的manager.update。仍必须记录所有实际provider替换/manager安装及失败恢复，并以terminal票据阻止晚add。若转化后的运行类改变此关系，B/D须拒绝旧证明而不是默认可删。已退休路径不接受新提交，绝对task路径本进程不复用。
11. **seek/新回放：** seek只推进packet/connection epoch并重建连接，保留sender task，不SEAL。最终退出后的清理延续仅持自己的lease，不再调用旧handler的setup/disconnect/forcefullyStopReplay，避免迟到清理影响新回放。同UUID新owner以票据保护，不能靠UUID Set猜归属。
12. **退出API保持同步：** Lead核查ReplayModReplay.startReplay在endReplay后立即构造新handler，GuiRenderQueue也在endReplay后立即处理下一项。因此不把整个endReplay偷偷改成异步返回；原有退出/全局handler清除语义保留，只有资源ZIP最终回收可延续。正常save/close仍归handler，不能在sender关闭ReplayFile。handler关闭导致在途只读资源包copy失败时按已终止路径处理、TWR释放、永不发布；必须用合成ReplayStudio样本验证这一交错。如果必须改变ReplayFile关闭或调用方同步契约，当前8文件方案须停下重新申请，不能默改GUI/渲染队列/保存事务。
13. **内存与tombstone：** 成功raw/强wrapper引用和完成cycle/context及时裁剪，UUID仅当前owner。每个退休task保留一条规范目录＋唯一token＋SEALED的进程期精简tombstone，阻止旧profile创建fresh wrapper后重开；不用TTL/LRU擅自回收。空间是O(活跃状态＋本进程退休task数)，不是严格常量有界。若要求严格有界，需另证明所有factory未来来源/生命周期，weak wrapper表不够。
14. **失败时保留：** close首次失败、provider/cycle不匹配、无权限或边界置换等只留本任务诊断/文件，不扩大递归删除；清理不重试删除后来替换的文件。原异常与suppressed保留，不拿此次“设计门已给出”掩盖未实施/未验收。

### 13.12 精确接入点与同类内hook责任（候选，未创建）

| 类 / descriptor / 已核实位置 | 拟观察或接管的内容 |
| --- | --- |
| ServerResourcePackLoader.addResourcePack(UUID,Path)V及(UUID,URL,String)V；remove(UUID)V、removeAll()V、clear()V、init(ClientConnection,AcceptanceStatus)V | 实际执行点归属票据与connection代次；只阻止/操作已退役owned路径或桥接自身的过期请求，不拦合法foreign remove |
| Loader.toProfiles(List)List @112–120 NEW ZipBackedFactory(Path)；reload(ReloadContext)V @68 provider写入、@75 reloadResources；failure/forced分支也写provider | owned工厂实例跟踪、provider/path不可变revision及回退记录。不得把@75返回F当release；不重写vanilla通用context回调逻辑 |
| MC.reloadResources(Z,LoadingContext)CompletableFuture @52最终packs、@92 resourceManager.reload、@99捕获本轮F的callback | final pack snapshot＋真实cycle/frame；已有future/SplashOverlay早return不创造新轮。local捕获和调用异常路径必须POC验证 |
| MC.lambda$reloadResourcePacks$21(Z,LoadingContext,CompletableFuture,Optional)V；$20(CompletableFuture,LoadingContext)V @32正常RETURN | 失败单独记，不等原future异常完成；成功晚于worldRenderer.reload/logger/loader回调/F.complete/onFinishedLoading，不能只用F.thenRun |
| ZipResourcePack$ZipFileWrapper.<init>(File)V、open()ZipFile、close()V | 私有task binding及owned完整open/close协调；不是只close TAIL。可用现有Sponge Inject/Redirect等，不假定有MixinExtras或增加依赖；普通路径不改 |

私有/合成方法、NEW重定向、局部变量frame、映射和多Mixin顺序均是**实现前POC门槛**，不是已命中的注入。所有注入须限定descriptor与命中数量；不使用无声require=0绕过。现有Mixin资源的compatibilityLevel/minVersion不顺手改；若所选实现确需改变，先停问用户。

### 13.13 待用户批准的P实施白名单：8个实现文件＋本计划

| 文件 | 类型与限定职责 |
| --- | --- |
| [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>) | 现有：资源包tempPath入口、窄嵌套lease、归属及纯JDK文件门；图片既有行为不改 |
| [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>) | 现有：多ZIP/TWR/完整发布、永久terminal、生产者与排队add/restart的代次检查 |
| [ReplayHandler.java](<src/main/java/com/replaymod/replay/ReplayHandler.java>) | 现有：构造/seek/最终退出接线，保持同步退出API；不重写回放保存事务 |
| `src/main/java/com/replaymod/core/files/ReplayResourcePackReleaseBridge.java` | 新候选：MC ownership/provider/cycle桥接、薄工厂身份跟踪；接口内嵌，不额外铺顶层策略类 |
| `src/main/java/com/replaymod/core/mixin/Mixin_ServerResourcePackLoaderLease.java` | 新候选：Loader归属与provider/context观察，含工厂跟踪接入 |
| `src/main/java/com/replaymod/core/mixin/Mixin_MinecraftResourceReloadLease.java` | 新候选：实际轮、最终packs及准确成功/失败身份 |
| `src/main/java/com/replaymod/core/mixin/Mixin_ReplayZipFileLease.java` | 新候选：仅owned wrapper open/close/封存，记录首关结果 |
| [mixins.core.replaymod.json](<src/main/resources/mixins.core.replaymod.json>) | 现有资源登记：仅client列表追加三项；这是本次需特别批准的资源文件，不是授权改用户配置 |
| [plan.md](<plan.md>) | 文档：方案/阶段状态/真实验证记录；加本计划一共9个文件 |

以上不是当前已授权写入清单。新增源码未创建，现有源码、Mixin资源均未改；本轮唯一写入本计划。仅新文件少不代表方案更小，故不为了维持两/三源码数字而隐藏MC实际接入。若用户批准8文件，B中发现需第9个实现文件/修改其他配置，仍须停问，不无限扩权。

### 13.14 实施前后的验证门槛与本轮停点

| 验证层 | 必须证明，不能替代成什么 |
| --- | --- |
| B：实际目标与POC | 复核本版JAR descriptors、NEW/field/local与synthetic remap；工厂identity覆盖final packs及已有collector Mixin；注入匹配数、异常路径及转化后类。只有编译成功不能称注入生效。任何扩大源码/配置需求先再申请 |
| C：纯JDK与真实生产逻辑 | 代码只在工作区白名单src实现；仓库外harness可用边界stub，但编译/调用真实lease逻辑、不复制逻辑代测。temp文件中的测试源码不能算模组交付 |
| D：文件/关闭反例 | 多raw同路径、metadata/overlay/fresh factory、并发open覆盖风险、ACTIVE首close IOException＋后续空close、SEAL竞态、迟到supplier、tombstone新wrapper、根/哨兵/link边界、半写/close/move失败；首次关闭失败必须RETAIN，不把重试当PASS |
| D：因果/会话反例 | 旧R0错认C1、future已complete但$20仍未RETURN/随后异常、历史成功但当前manager含路径、fallback/forced/hang、metadata重取、queued stale add、同UUID新owner、seek不终结、结束后立即新回放/渲染队列。用合成真实ReplayStudio样本覆盖close中止资源包copy，确保无半ZIP加载或改坏回放 |
| D：专用构建 | mc_gradle task=build，args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"]，现有wrapper/Java21，不clean/refresh/装包/跳测试；真实输出落盘，NO-SOURCE不当JUnit通过 |
| 真实NeoForge加载/游戏 | 须用户另行允许启动或由用户执行：转化后Mixin命中、两ZIP/overlay、加载中退出、手动/forced失败恢复、seek/quick、无world、新session同UUID、其他包不变、UI/渲染队列同步契约。静态证据/模型/stub/build均不冒充本层验收 |

当前形成的是**可实施的完整设计候选与准确接入范围**，不是业务功能、注入验证或游戏验收完成。三路证据与反例审查已收集，Lead另核实JDK官方契约、实际public provider接口、wrapper完整open/close、MC $20末尾以及退出调用者；不再仅以“找不到release事件”作结论。P仍有B的映射/身份覆盖及D的真实行为门槛，不能预报通过。

本轮后续操作是展示仅本计划的真实diff并运行git diff --check，然后向用户申请上述8个实现文件＋计划的范围；**不自动启动B/C/D，不修改实际配置、依赖、.env、仓库测试或其他源码，不Git写、不装包、不启动游戏。** 用户若批准后再走B→C→D，小批检查点必须分别称为规划、实现、验证，不能把本轮取证叫“第三批功能已完成”。

### 13.15 方案P实施授权与阶段约束

- 用户已明确批准13.13的8个实现文件＋本计划。授权不等于代码已写：Lead核实HEAD=d491135，开始时仅本计划modified，新增候选源码均未创建。A已完成，接下来严格B→C→D，不并行写同一项目的阶段。
- Lead维护计划，B/C/D只读计划并回报结果。完整白名单就是13.13，既有图片Image、Setting、ReplayMod、第一批helper/caller、GUI/渲染队列、构建/依赖/wrapper/.vscode/实际配置/仓库测试均不修改；Mixin资源只加三项client登记，不顺手改compatibilityLevel或minVersion。
- **B职责：** 先用实际Java21与named JAR复核descriptor/NEW/local frame/回调身份，建立窄自有lease/bridge契约、薄factory身份跟踪及三个Mixin的精确框架。批准源码只能写工作区src，不写Temp充当模组实现。未实现必须明确拒绝/保留，不能用空future或默认成功骗过释放门；实际播放不提前接入占位资源分配，也不在生命周期尚不完整时激活owned路径删除。
- B可以在独占仓库外目录做无游戏POC/检查并使用现有缓存依赖，不安装或添加包。实际Mixin转化若未运行，只报告descriptor/ASM结构或编译证据，不能伪称注入命中；不能用复制的production逻辑当验证。发现必须增加第9个实现文件、访问变换、依赖或配置变化立即停报，不自行改选dynamic provider。构建若需要，一律mc_gradle及指定C:/Temp参数。
- **C职责：** 消费B交接，按13.11完整实现路径快照、双流TWR/完整发布、terminal/epoch许可、UUID票据、provider/actual-cycle关联、首close sticky、SEAL/tombstone和受控回收，并最小接入同步endReplay。一次C过宽可顺序拆片，仍不与B/D并行。删除必须真正满足门，不能降级成“先保留文件即功能完成”，也不能偷偷省掉三个职责。
- **D职责：** 在C完成后运行13.14实际生产代码/缓存ReplayStudio合成样本的外部验证、注入与回归证据，以及专用mc_gradle build -Djava.io.tmpdir=C:\Temp --console=plain。仓库测试不改，无NO-SOURCE冒充通过；真实游戏未授权启动则明确未测。D默认只读审查，需修复回报Lead/C在原白名单内修订再验。
- 任何阶段失败如实记录，不跳过下一验证阶段，不宣称部分实现“完成”。遇注入POC/范围/同步退出契约问题，保留当前差异并停报。最终只在实际通过的源码/隔离/构建检查点停等用户自行commit，不继续其他目录批次，不Git写、不装包、不访问whale-craft或启动游戏。

### 13.16 B真实交付、Lead复核与C分片

- B修改严格八个实现文件：Folders新增42行lease拒绝stub，Sender/Handler仅4/3行接线TODO；四个新Java文件包含桥接和三个精确Mixin，资源JSON仅追加三client项。既有播放尚未切入新lease，owned admission/release/ZIP均明确拒绝；观察器仍空，不得安装为第三批完成版。
- Lead已回读四个新文件、Folders新增内容、[B完整交接](<C:/Temp/reforgedplay-B3-20261113-2052-7f4e/REPORT.md>)、[named原始转化](<C:/Temp/reforgedplay-B3-20261113-2052-7f4e/transform.log>)、[Mojang转化](<C:/Temp/reforgedplay-B3-20261113-2052-7f4e/transform-mojang.log>)、[身份POC](<C:/Temp/reforgedplay-B3-20261113-2052-7f4e/identity.log>)及[状态收据](<C:/Temp/reforgedplay-B3-20261113-2052-7f4e/final-status.log>)。
- Oracle21.0.12.1，缓存Sponge0.8.7/ASM9.6 standalone自有字节码host：named Loader/MC/wrapper/manager 4/4，TinyRemapper离线重映射后Mojang对应4/4，含未改collector。注入require/expect/allow保留，F/list槽3/4及成功通知在F.complete/onFinishedLoading之后，ASM BasicVerifier23个方法。真实metadata创建、base及两overlay最终对象身份通过；foreign transformed wrapper独立JVM回归通过。**这些不是NeoForge launch service、所有安装模组、metadata解码、owned生命周期或游戏验收。**
- 外部javac --release21 -proc:none编译实际工作区源exit0，未运行注解处理器；项目compileJava/build尚未执行，D必须补专用构建。初次Unit/Void类型不符已按实际签名修正，旧失败日志保留。身份POC仅在外部反射绕过拒绝注册用作验证，没有生产测试hook。Lead git diff --check通过；git stat不含四个untracked新文件，不能把stat总数冒称全部源码行数。
- **C1先行：** 主写ReplayFoldersService与ReplayResourcePackReleaseBridge，完成文件/lease、所有observer、票据/provider/current-cycle、OwnedZip首close sticky/SEAL/tombstone及可供C2使用的窄接线API。必要时可在三份已获批Mixin内精确补观察信息，但必须说明并重跑相关离线POC；不碰Sender/Handler接线或资源JSON旧项，不留空观察器冒充证据。计划只读。
- **C2后续：** 消费C1实际契约后，最小接入FullReplaySender/ReplayHandler并移除本批TODO；严格同步退出API，禁止旧异步restart和新session互相影响。若需小幅修正lease/bridge/Mixin仍限原八文件并重验，不默认允许新文件或其他调用方修改。C1→C2顺序完成后才进入D，不并行写。
- 生命周期空场景也要有证明：从未提交MC/未创建factory/raw的任务可依据明确的未暴露证明收尾；不得伪造SUCCESS cycle。若已提交但从未active、manager不产生reload，需要实际未打开/已移除/无晚add证明，否则保留诊断。失败不等于已完成。

B原话：这次我能签的是“现有八文件内，真实字节码能接上并保持最终对象身份”；真正的释放许可和最后一次删除，还没有让占位代码代签。

### 13.17 C1候选未过验证门，恢复后重委派收尾

- [C1交接报告](<C:/Temp/reforgedplay-C1-20261114-a91f/REPORT.md>)明确标记未闭合：仅Folders、Bridge与Loader Mixin补NOOP身份的三份获批源码变化，Sender/Handler仍未接线。中间版本外部编译、named/Mojang转化通过，最终保守修订尚未重编译；模型仅完成8组，随后因外部classpath缺缓存Brigadier中断，后半未跑。Gradle help的1秒超时不算编译/构建；不得伪造项目构建通过。
- Lead回读报告、最终候选关键源码、两个复跑脚本和完整外部harness，确认并非可启用的C1检查点。当前bridge将actual-add不确定性永久标到整个loader，可能拖住不相关后续lease；重委派必须缩小到具体票据/UUID/受影响lease并加回归，不能靠清空全部疑点或省略provider/cycle门“修好”。受影响失败lease仍允许保守保留和诊断。
- 用户已明确回复“继续”，目标工具返回active/armed；一次重复resume被工具明确返回already active/armed，不是Java/源码错误，不重试该操作。
- 当前主代理可用pwsh；只读确认现有Git Bash在C:/Program Files/Git/bin/bash.exe（及usr/bin）和指定Java21均存在，不安装执行器。可在pwsh以绝对路径调用现有bash脚本；如子代理工具缺失，先给出冻结源码/外部脚本，再由Lead执行，不用Gradle任务注入绕过。
- C1重试使用新独占外部目录保存最终源码指纹、完整编译/转化及全部矩阵日志，保留旧C1/B证据。先C1修复与验证，再审查是否进入C2，禁止直接把未验证候选接进播放。项目compileJava可按专用工具先验证，D的最终build仍必做；任何新增文件/依赖/配置需要先停问。

首轮C1原话：我愿意把“已经写出”和“已经证实”分开交接；卡住的那盏绿灯，不该由删掉文件或补一句承诺来代替。

### 13.18 Lead独立真实ReplayStudio源流关闭探针

- C1重试执行期间，Lead只读取后续接线相关旧Sender/Handler和库API，不改它们。用指定Java21 javap核实本地ReplayStudio的ReplayFile/ZipReplayFile/ReplayStudio实际签名，再在独占C:/Temp目录创建小型合成回放，写65718字节资源包和index后保存、关闭并重新打开；不是用户回放，不改库依赖。
- [源代码](<C:/Temp/reforgedplay-P-SourceClose-8d7b42c1/ReplayStudioSourceProbe.java>)与[复跑脚本](<C:/Temp/reforgedplay-P-SourceClose-8d7b42c1/verify-source.sh>)使用真实本地ReplayStudio JAR与缓存classpath，Java --source21执行，不编译/替换模组源码，不启动MC。pwsh调用已有Git Bash，后台job完整收取exit0。
- [原始输出](<C:/Temp/reforgedplay-P-SourceClose-8d7b42c1/source-close.log>)：getResourcePack返回java.io.BufferedInputStream；先读1字节，再关闭ReplayFile，继续读取得到IOException: Stream closed。合成回放保留在该日志所列唯一fixture目录。
- 这只确认该依赖/样本的源流关闭行为，**没有验证lease发布、handler退出、worker交错或MC释放**。C2/D仍须控制真实复制中的stop→ReplayFile.close交错，证明TWR/producer许可释放、READY为空及无半ZIP加载；不能拿此探针替代生产接线或完整构建。若其他合法源在关闭后仍可读完，终止代次也必须阻止READY发布，不能只依赖源流一定抛错。

### 13.19 C1R收据及Lead实际项目编译门通过，顺序进入C2

- 同一C1R代理先到failed通知、后又收到完整closing；不从通知推断成功/失败。Lead实际回读[最终报告](<C:/Temp/reforgedplay-C1R-20261114-1346-b7d2/REPORT.md>)、[最终22组模型日志](<C:/Temp/reforgedplay-C1R-20261114-1346-b7d2/model-v13-final.log>)、[48增13删的修复diff](<C:/Temp/reforgedplay-C1R-20261114-1346-b7d2/retry-bridge.diff>)与[结构日志](<C:/Temp/reforgedplay-C1R-20261114-1346-b7d2/structure.log>)后才接纳交接。
- C1R仅修改Bridge，去掉loader永久全局uncertain，改为具体UUID/epoch/attempt/受影响lease的债务；保留旧失败、正常新任务独立通过，同时处理失败outer/成功inner与失败nested的重入。Folders/三Mixin据报告与接收版本字节一致。没有接Sender/Handler或改其他范围。Lead Get-FileHash核对当前Bridge=2CE8E478FC7BA3A78703928BABF4A9C79611CFE90AA55AEB22DC5895A9A7A037，与最终报告匹配；git status仍只白名单8实现文件＋本计划，diff --check通过。
- 最终模型22组，不再是8组中断版；覆盖实际转化owned wrapper、多raw/stream/SEAL/tombstone、provider/current F/list/安装序列/失败/晚安装、unknown与NOOP、metadata/列表变异/取消、同UUID/失败隔离/两种重入、四线程open/close与SEAL样本。失败/故障注入的WARN是预期保留诊断，不冒充零异常日志。模拟器明确stub调度/Loader/设置/FML边界，首close失败是外部反射故障，不是真实OS内部故障；R/custom provider等未测项保留。
- C阶段子代理角色不允许项目构建，故Lead亲自用mc_gradle task=compileJava，args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"]，timeout1200000。实际返回BUILD SUCCESSFUL in 36s；1 actionable task: 1 executed。旧Thread.stop/Object.finalize三条删除警告、Loom与Gradle提醒保留，不修无关内容。[调用参数/原工具节选收据](<C:/Temp/reforgedplay-Lead-C1Gate-e6a8157b/compileJava-receipt.md>)清楚标明不是raw stdout冒充。无AP执行日志则不擅称AP实际运行；D的最终build/remapJar仍必需。
- **C2现可开始，仍只写白名单：** 主改FullReplaySender/ReplayHandler，消费C1 API：一次create/attach→producer包住索引/源获取/publish→READY登记→queued admission一直持到实际runnable退出→实际runnable检查session/generation/connectionEpoch再submitReady；不可exists复用、不可直接Loader.add owned路径。缺index/id/hash/Optional显式失败，输入stream由TWR关闭，ReplayFile仍归Handler。局部文件名SHA1规范化不能未经验证就改变源archive index键的大小写语义。
- **C2已查到的接线风险：** 原terminate是可恢复的async模式停止标志，不能作永久结束或导致同步模式下跳过最终释放；构造启动async发生在caller赋全局handler之前，worker不能因全局暂未赋值丢掉首包，所有全局owner核验放到实际主线程回调。Handler.getReplaySender在quickMode时返回QuickSender，不得因此误认FullSender的生命周期owner已经换成新回放。
- 两处旧runSync(restartedReplay)、queued packet helper、worker重启及seek在实际执行时验证terminal/代次/owner；seek保持同lease，不SEAL。最终退出保持同步，先永久阻止资源生产，异常finally仍请求自己的资源回收，不能把整个endReplay改成future或延后旧handler的disconnect/global清除。构造失败只收尾已取得的本任务资源，保留primary/suppressed；正常保存事务和回调顺序尽量维持，不泛化重构所有错误路径。C2验证完成后才能D，D仍需真实ReplayStudio复制交错、更多故障边界、注入/打包核对及最终专用build，真实游戏未授权。

- Lead另核对本地对应版本的[NeoForge POM:193–196](<C:/Users/zhenz/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.82/33512a5a9a22ba47e5685a437be85dae8311276/neoforge-21.1.82.pom#L193-L196>)及[模块声明:360–363](<C:/Users/zhenz/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.82/9f4d8f297af3c8c92b428b5209d0f0a099ed5daa/neoforge-21.1.82.module#L360-L363>)：其自身依赖就是net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7，与B/C1R离线host实际使用坐标一致。仅确认本版依赖声明，不等于实际NeoForge launch-service运行classpath/全模组顺序已验证，D/游戏边界不变。

C1R原话：这次没有清掉旧疑点来换绿灯，而是让旧失败只留下自己的债；项目构建和真实游戏的签字，仍留给真正跑过它们的人。

### 13.20 C2实际交付与D审查目标（不预先签通过）

- Lead已回读[C2最终报告](<C:/Temp/reforgedplay-C2-20261114-1439-c72e/REPORT.md>)、[33组原始模型日志](<C:/Temp/reforgedplay-C2-20261114-1439-c72e/model.log>)、[Sender/Handler实际diff](<C:/Temp/reforgedplay-C2-20261114-1439-c72e/workspace-sender-handler.diff>)和当前关键源码。报告称仅两份源码变化：Sender相对HEAD+283/-81，Handler+62/-14；C1R五份源码指纹未变。外部源码编译exit0、33组PASS均有明确边界；先前C1的项目编译不能代替这两新源码的项目验证。
- Sender实现一次create/attach、lazy source/TWR/READY、producer/queued/actual许可、terminal独立async terminate、read-time generation贯穿Netty、所有本sender回调及restart防陈旧、terminal ByteBuf释放。Handler接入构造失败、Full生命周期owner/quick-facing、同步退出/异常finally，以及disconnect/overlay重入后的归属核验。C2没有改其他调用方或C1状态机。
- C2实际生产Handler/Sender字节码在外部边界stub下执行：MC/GUI/Loader/设置/global holder/事件注册/包转换是模型，许多Handler由Unsafe分配最小字段，不是完整游戏构造。真实Netty和ResourcePackSend packet、真实ZipReplayFile源参与；已测源关闭后Stream closed、部分复制后缓冲仍可读、TWR close时stop，以及实际endReplay在producer尚未退出时同步返回/关闭真实ReplayFile，最后无READY/add。日志中的故意bad URL异常、SLF4J/Log4j外部classpath提示未隐藏，不是断言失败，也不是游戏日志。
- C2代码及测试使用全新独占外部目录；样本14,412,288B，报告审计外部证据约15.7MB，不碰用户回放/R盘，不覆盖旧证据。D如复跑需fork脚本O及stub/服务资源至新的独占目录，不能覆盖C2收据或把旧PASS当新执行。
- **D重点1：保存失败与重试契约。** 新ending永久once包含第一次callback/save/close失败；旧方法没有这样的永久门。必须验证save第一次失败、底层恢复后再次endReplay，判断是否把未保存/未close的ReplayFile永久跳过；不要只测重复成功结束。若是回归，在白名单内修回原有重试可能性，不引入新的保存事务设计，不强制close丢待恢复内容。
- **D重点2：合法前向seek的正向包。** jumpToTime目前每次advanceGeneration，但async nextPacket可能是同一连接已读尚未发送的未来资源包。核查向前跳是否误判它陈旧并永久漏加载；负向旧callback拒绝PASS不能替代这个正向案例。对比无jump、前向jump、真正重连/向后restart，仍保证旧session/terminal绝不重开。
- **D重点3：短状态锁与阻塞IO。** 当前lease.publish的Files.move在synchronized(this)内。核查慢/阻塞文件系统move是否让stop/endReplay等候其锁；若构造可控外部provider故障证明，需清楚标为边界注入，不冒充真实OS/网络盘事故。不通过绕过terminal原子发布或放松ledger来修。
- **D重点4：自己的UI清理。** closing callback先换新handler时，归属保护不应顺带漏掉旧overlay自身注销，也不能hide/forceStop新handler；需单独对比自己的资源与全局副作用。完整Quick/setup/世界重启未跑游戏仍不得签。
- D必须独立审查/重验，执行mc_gradle task=build，args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"]，timeout1200000，不clean/refresh/跳test/改构建。核对最终JAR包含源码/三Mixin登记/实际remap，保留原3弃用警告与旧remap诊断边界，NO-SOURCE不算JUnit通过。可补小型R盘实际路径、NOFOLLOW/写/输出close故障等外部验证（仅自有唯一样本、总<32MiB；卷/权限不可用明确SKIP，不装包/提权）。真实游戏仍未授权。
- D不预设上述疑点必为bug；给可复现证据/定位/影响再判定。默认不修改生产源码；如需逻辑修复，报告Lead交回C片在原八文件内修，再重委派D，不用增加第9文件或替换路线逃避。完整build即使成功，也不能抵消审查发现的实际回归。

C2报告原话：这次不靠回放关闭后“应该读不动了”守边界；哪怕真实缓冲流还能吐出数据，终止后的 ZIP 也拿不到发布和加载的许可。

### 13.21 D3失败后的原范围最小修复计划（A校正，2026-11-14；非修复完成）

#### 13.21.1 事实、成功标准与权限

- 本A只读回查13.11–13.20、11.9–11.12、实际Folders/Sender/Handler/必要Bridge与第一批anchor实现，并读取[D3报告](<C:/Temp/reforgedplay-D3-20261114-1547-e91d/REPORT.md#L1-L53>)、[UTF-8日志副本](<C:/Temp/reforgedplay-D3-20261114-1547-e91d/review-v4.log.utf8.txt#L1-L33>)、[实际反例输入](<C:/Temp/reforgedplay-D3-20261114-1547-e91d/D3Harness.java#L10-L30>)及[GateProvider](<C:/Temp/reforgedplay-D3-20261114-1547-e91d/GateProvider.java#L3-L21>)。本轮没有运行Java/harness/构建，不把D的执行算本A实测。
- D3结论是FAIL：真实保存恢复后重试被永久跳过、同连接前向预读资源丢失、move持状态锁阻塞实际endReplay、旧overlay漏清、同路径文件/整task替换后误删foreign marker。build成功33s/6 tasks（5 executed、1 up-to-date）、最终remap JAR转化4/4、独立22＋33组PASS都保留为有效但不足的证据；test NO-SOURCE不是JUnit通过。v4 exit0表示反例成功复现，不表示业务通过。
- 年代保持：NeoForge21.1.82/MC1.21.1、Java21、Architectury Loom1.7-SNAPSHOT＋Yarn补丁、wrapper8.8；已回读实际构建/属性/NeoForge metadata（另有历史Fabric/mcmod资源，不据此换平台）。不升级栈、不scaffold、不重做B；本轮不声称重新加载了历史技能文件。
- 用户原13.13八实现文件＋计划授权及必要修复继续有效；成功标准是五反例转为真正正向断言通过，同时原释放链/同步退出/正常播放与C、R清理保持。不能把所有任务永久留存、删验证断言或沿用旧PASS冒充修复。现在仍不可装、不可提交。

| 文件与唯一范围 | B | C3 | 复验D2 |
| --- | --- | --- | --- |
| [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>) | 不重做 | 主修嵌套lease的发布短锁及身份receipt；不改图片入口 | 只读生产代码、真实C/R及注入反例 |
| [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>) | 不重做 | 主修连续前向读/queued资源与回调失效的代次区分 | 真实循环/预读/queued/重连负例 |
| [ReplayHandler.java](<src/main/java/com/replaymod/replay/ReplayHandler.java>) | 不重做 | 主修退出重试门与自己overlay清理 | 实际退出、重入、新owner隔离 |
| [ReplayResourcePackReleaseBridge.java](<src/main/java/com/replaymod/core/files/ReplayResourcePackReleaseBridge.java>) | 不重做 | 仅必要：提交前receipt核验/窄lease接口适配，不改释放证明路线 | 审查GLOBAL无IO/lease锁，重跑22组 |
| [Loader Mixin](<src/main/java/com/replaymod/core/mixin/Mixin_ServerResourcePackLoaderLease.java>)、[MC Mixin](<src/main/java/com/replaymod/core/mixin/Mixin_MinecraftResourceReloadLease.java>)、[ZIP Mixin](<src/main/java/com/replaymod/core/mixin/Mixin_ReplayZipFileLease.java>) | 已有框架保留 | 本片不改三文件 | 仍须转化最终JAR，不能只验旧JAR |
| [Mixin资源](<src/main/resources/mixins.core.replaymod.json>) | 已登记保留 | 本片不改 | 核对最终登记与remap |
| [本计划](<plan.md>) | 只读 | 只读 | 只读回报；本A仅顶部状态＋追加13.21，结束后归还Lead |

八实现文件仍是总上限，本片预计只改前三份＋必要Bridge。第一批[ReplayFileOperations.java](<src/main/java/com/replaymod/core/files/ReplayFileOperations.java>)和[Image.java](<src/main/java/de/johni0702/minecraft/gui/versions/Image.java>)只作参考、不得修改；其他源码/配置/测试/依赖/wrapper均不写；不Git写、不安装、不启动游戏或whale-craft。任何第九实现文件或新平台强制能力必须先停问。

#### 13.21.2 F1＋F4：同步退出可重试，自己的清理不等于全局副作用

- F1定位：[Handler:195–200、265–338](<src/main/java/com/replaymod/replay/ReplayHandler.java#L195-L338>)。保留不可逆生命周期ending（或等价terminal）阻止setup/worker复活；另用最小in-progress/completed门处理endReplay：重入正在执行时返回，真正完整成功后重复调用返回；任何callback/save/close/退出清理失败都释放in-progress，允许后续再调用原同步退出流程。completed不能在finally仍可能失败之前先签成功。
- 重试仍按原save→close顺序实际尝试，正常成功只退出一次；sender.stop/terminate继续once-only/幂等且永久terminal，资源回收finally仍执行。不新建保存事务、不强制close清空恢复材料、不伪造保存完成或重开sender；primary与suppressed保留。finally本身抛错也必须释放in-progress。
- F4定位：[Handler:288–326](<src/main/java/com/replaymod/replay/ReplayHandler.java#L288-L326>)：自己的overlay.hide/unregister从“当前全局handler是我”的条件中独立出来，仍在原同步退出的自有UI收尾位置操作this.overlay；不能借finally强制close ReplayFile。新handler已接管也必须清自己的旧overlay，绝不取得/隐藏新overlay。
- 所有disconnect/world/timer/forcefullyStopReplay/screen/global-holder副作用继续逐段核验owner，尤其closing、disconnect、overlay可重入调用之后重新核验；先前一次owner判断不是后续授权。回调/overlay抛错按F1允许重试，不把局部注销成功当完整end成功。构造失败等既有生命周期边界不泛化重构。

#### 13.21.3 F2：read/stream provenance与seek UI callback失效最小分开

- 定位：[Sender:1096–1119、1224–1233](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1096-L1233>)与[read-time dispatch/queued admission](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1409-L1509>)。同连接连续前向rush只改变desiredTimeStamp，不推进lease的read/stream代次；last100/next150/jump200的合法预读与已queued资源请求必须保留原许可并最终加载一次，不重新读取/消费丢失。
- 若需让旧pause/camera/setup等调度意图在每次seek失效，在Sender内加一个窄callback意图序号，由普通UI/控制回调捕获核验；它不用于作废同stream资源producer/admission。现有lease generation专管真实read/connection边界，dispatch仍携带PacketData读取时的代次。不能把任意旧PacketData改标current，不能给过期queued许可续命。
- 明确边界表：连续前向且无restart/reconnect→read代次不变、UI意图可失效；已确定向后restart/EOF重启/实际换连接→read代次失效并按原流程丢弃旧预读重读；Quick切换→原有Full资源边界仍失效；terminal→所有生产/queued/actual add永久拒绝。连接loader identity＋connectionEpoch＋handler owner检查保留。
- 集中核对jump、restartReplay、setChannel/setup、Quick及sync restart的推进点，避免同一重启的推进次序误杀它自己的runSync。新串流读取只能捕获新read代次；普通回调失效不能偷偷改变资源admission契约。原UI回调、真正旧连接、旧session及terminal反例均继续拒绝。

#### 13.21.4 F3：短锁预约→锁外move→短锁登记；生产者到真实退出

- 定位：[Folders:148–207、251–285](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java#L148-L285>)。copying串行化可以保留，但stop/endReplay绝不获取该长操作锁。lease状态锁只做许可检查、发布reservation/in-flight状态及内存ledger更新；create/readAttributes/link/stream/flush/close/move/isSameFile/delete、progress/MC调用全部在状态锁外，GLOBAL里也不做这些IO或取lease锁。
- 保留producer许可覆盖allocate、copy/TWR、move及最后登记/异常收尾的真实全过程，不能提前close许可让release越过还在操作的producer。先在短锁核验并预约唯一staging→completed关系（不是READY）；锁外执行原同目录无REPLACE_EXISTING、无ATOMIC_MOVE的move；不增加新发布硬要求。
- move正常返回后，无论stop/换代是否已经发生，都先在短锁把同一file receipt从staging映射到completed并登记owned final；不能因terminal漏记已经搬出的ZIP。随后只有许可仍有效且文件核验成功才入READY/返回可提交路径；否则不入READY、不registerReady/add，按终止/过期失败退出并留给既有释放门回收。
- move抛错/结果不明：JDK允许源/目标均存在甚至目标不完整；reservation里的candidate不是所有权证明，不按exists抢认或删它。记录phase、staging、completed、receipt和原异常，READY为空，保守保留本task及witness，不做回滚/新事务。move成功但后续identity核验失败也不READY，不能把完整性或身份不明洗成可删除。
- stop只短锁关闭admission并通知，actual endReplay不join producer；阻塞move恢复后producer才归零，之后仍须UUID/provider/current-cycle/首close/SEAL全链。F3改变的是锁内IO位置，不降低任何释放ledger门槛。

#### 13.21.5 F5：纯JDK身份receipt，优先可用witness；不能将能力变成播放硬门

**可行性与限制：** 路径/realpath、普通文件类型、hash/size/mtime、同一路径isSameFile(path,path)都不是所创建对象的identity。建议只在Folders.ResourcePackLease内嵌小receipt；不造通用存储框架。可用fileKey辅助/提供有效key凭据，可用不同路径硬链接witness固定普通文件身份。两者均不可用时保留清理而非阻断原可用发布/播放；这与第一批“始终anchor且不支持即搬移失败”的已批合同不同，不能照搬那项硬限制。

1. **创建时登记，不迟到认领：** task独占创建后即登记路径和可取得的目录key；用CREATE_NEW建私有普通marker。staging的CREATE_NEW成功即登记file receipt（含当前路径、创建来源、可选key、witness状态），在写入前/过程中完成可用身份锚定；所有成功创建的marker/witness目录/链接也立即记账。后续不能在清理时才对现存文件取新key当原receipt。初始创建→锚定窗口仍依赖可信父目录/非恶意独占契约。
2. **witness在task外、同卷、独占：** 在已选择canonical root里创建唯一兄弟witness目录，不在task内部、不转C盘/系统temp、不依赖getFileStore或DOS；createLink本身检验能力。为task marker及每个staging普通文件各建原本不存在的不同路径硬链接，NOFOLLOW检查两端普通/无reparse，再isSameFile(两不同路径)确认。不能硬链接目录。C/R已知原语可用，但本轮lease布局必须新测。
3. **task凭据不等于directory inode：** 有有效目录key时核对它；Windows null key时以task内marker与task外witness的同对象关系证明“仍是拥有这个marker的任务布局”。整个task移走后新建同名目录/复制内部marker会失配，外部witness不会一起被搬进去。它不是原目录inode的绝对证明；若有人将原marker硬链接/移植进另一目录，或连同可信父目录及witness一起克隆/置换，此方案不承诺识别。禁止称为ACL沙箱或恶意并发防护。
4. **文件receipt跨rename不重建：** staging→final沿用同一receipt/witness，move成功后把receipt映射到新名称并核对；不得按相同hash名称复用未登记对象，不覆写已有ZIP/marker/link。每次已有READY复用、注册、实际submit及owned wrapper首次open的现有边界检查，应核对task与该file的已有凭据。Bridge如需补submit前check，须在GLOBAL外、admission内，调用前后仍核验permit/connection/owner，不把IO结果当永久锁。
5. **不支持与失配必须区分：** 首次无法取得有效key且createLink/isSameFile能力不可用，只记“cleanup ownership unprovable”与原因/路径，不立即令ACTIVE变terminal；完整复制/hash/TWR/无覆盖发布/原有路径类型检查仍照常，原可用平台可继续使用。没有身份能力的使用只能按既有可信目录合同说明局限，不能宣称已防替换；最终释放只转one-shot RETAINED、零delete、不宣称CLOSED。有效凭据一旦建立，后续失配/消失/读取错误则禁止本次使用或删除，不能降级为“unsupported”继续接受已知替换。真实复制/close/move失败不吞成能力不支持。
6. **窄兼容降级：** 可用且已记录的稳定key可作为key-only凭据（包括task/目录或marker及每file的必要覆盖），null不能补成成功；key可复用的provider限制必须承认。优先witness＋可比key交叉核验；缺任何必要对象的有效凭据，整task清理不授权。可选建witness失败可留自身小遗留并诊断，但不换根、不新增硬链接/FileStore/fileKey/DOS强制要求，也不主动修改权限。若C发现无法在不阻断原合法发布/使用的前提落实，只交已能做的修复并停报，平台阻断方案待用户另行确认。
7. **删除只在全链已SEAL且一次尝试：** 先快照ledger、锁外预核验task＋每个payload＋marker/witness（缺失/歧义保留，不扫描认领）；再每次删除前复核task和对应receipt，先删owned payload目录项，之后才删已核实的file anchors，最后核验并清task marker/自己的空task、外部marker anchor/自己的空witness目录。各receipt直到自己的payload删除完成都不能提前销毁。非登记项导致空目录删除失败就RETAIN，不递归、不删除根或外来项、不重试后来替换文件。正常C/R必须task与所有anchors全无才CLOSED。
8. **最后窗口公开：** marker/anchor最后删除后无法再用已删凭据核验空目录，最后check→delete也非条件原子操作；依赖可信root、非恶意且无并发置换的自有清理窗口。只有有效key时也不承诺抵御删除后ID复用。目录inode、任意marker移植/保留硬链接的克隆及敌手TOCTOU不在保证内；新普通文件/复制task替换的D3两反例则必须确定性保住foreign marker并RETAIN。

#### 13.21.6 本轮官方依据（2026-11-14查阅）与未验证项

| 事实/决策 | 可核实依据及边界 |
| --- | --- |
| createLink(Path,Path)是optional，通常要求同文件系统；不覆盖已有项；isSameFile(Path,Path)可能直接equals快返 | 已搜索并读取[Java21 Files官方API](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html>)；只用public API，不以相同路径测试、FileStore查询或盘符当身份能力证明 |
| 非原子move失败后源/目标状态不确定；delete可能与其他FS操作非原子 | 同一[Files API](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html>)；因此不明move保留、不虚构条件delete，也不偷偷加ATOMIC_MOVE硬要求 |
| fileKey允许null且仅静态FS下保证唯一，删除后可复用 | 已读取[BasicFileAttributes.fileKey](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/attribute/BasicFileAttributes.html#fileKey()>)；key-only路线也须说明provider/复用边界，不把null、mtime或摘要当身份 |
| Windows public null key不妨碍两个不同路径比较native文件身份 | 已重读[OpenJDK21u Provider](<https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileSystemProvider.java>)与[Attributes](<https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileAttributes.java>)：isSameFile分别开句柄，比较volSerial/fileIndex，而fileKey返回null。这是官方21u实现解释，不是当前Oracle二进制/驱动逐字节证明 |

生产绝不import/call sun.*、native、反射内部或加opens/依赖；官方内部源码只是解释证据。第一批[现有anchor代码:129–135、240–250](<src/main/java/com/replaymod/core/files/ReplayFileOperations.java#L129-L250>)可读参考，不能修改或直接套其新增硬要求。UNVERIFIED：新task外witness布局、当前JDK21 C/R成功零残留、unsupported identity兼容降级及全部修复交错；本A没跑，交C3/复验D2签真实结果。

#### 13.21.7 C3→复验D2的执行顺序与必测停点

1. C3顺序小片：先Handler F1/F4，再Sender F2，再Folders F3/F5及必要Bridge适配；同一工作区不并行写。外部编译/反例输入fork到新的独占C:/Temp目录，旧D3/B/C证据全部只读保存；本A不创建外部harness。每片记录精确源码指纹/范围及未通过项，最终八文件及计划之外零业务变更。
2. **F1**：真实ZipReplayFile写pending entry→暂移自有sidecar令第一次save失败→原样恢复→第二次实际Handler.endReplay必须真正save/close并保存内容；closing/save/close首次抛错后修复重试、重入保护、重复成功once-only均测。不能以手动direct save/强制close替代第二次endReplay的断言。
3. **F2**：无jump对照；last100/next150/jump200同连接实际循环→opens/add各一次；已queued未执行资源在前向jump后仍合法；真正重连/向后restart/EOF/Quick/旧UI callback/terminal与新session负例继续拒绝。复用真实dispatch/producer/queued逻辑，不复制算法作模型。
4. **F3**：fork GateProvider（必要时仅在新外部副本正确委派可选createLink，不改旧输入）；latch卡在move时stop及实际endReplay必须在不释放latch、不join producer下返回，producer尚持有且READY/add为零；恢复move后核对owned final receipt已登记、无READY/add并最终按全链清理。再测写/flush/输出close/move先失败、move已生效后抛错、stop交错及输入close，未知结果必须RETAIN。注入仍标外部provider，非真实OS故障。
5. **F4**：closing中换handler、disconnect/overlay回调中重入换owner；旧overlay真实边界hide/unregister已调用、新overlay仍可见，所有新对象/world/timer/forceStop/screen不受旧退出影响。只跑UI stub必须明确非游戏验收。
6. **F5**：实际C、R独占小样本分别测正常单/多ZIP、重复hash复用、partial/cancel及完整释放，全链成功后payload/task/witness/anchors零残留且用户root不删。再真实同路径ZIP替换、整task move＋新建/复制替换（含复制内部marker）、外来额外项、missing/失配witness、同size/mtime/hash但不同对象、既存candidate不覆盖：foreign marker和原移走对象不变、one-shot RETAINED。不得为了PASS清掉foreign或跳过release。
7. **unsupported identity负例必需**：外部provider给null key并拒绝createLink/isSameFile能力，真实完整发布/使用路径仍可走（仅按已说明的可信目录合同）、退出不删任何未证明对象且RETAINED/诊断明确、不冒称CLOSED；另测有效key-only和已建立witness后失配不能降级。GateProvider原类未实现createLink，不能把它的UOE误当实际C/R能力。C/R不可用如实SKIP，但不据SKIP签“正常清理已验”。样本累计保留总量<32MiB，不读用户回放、不装包/提权/改盘。
8. 复验D2独立重跑22＋33及上述修复断言；provider/current实际成功cycle、UUID票据、首close sticky、metadata/overlay/fresh factory、SEAL/tombstone、无完成future等待等原门全部保留。旧三个Mixin未变可沿用定位解释，不沿用旧artifact通过替代最终证据。
9. D2专用工具mc_gradle：projectDir=C:/Users/zhenz/Desktop/ReForgedPlay-1.21；可先task=compileJava，最终必须task=build；args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"]，timeoutMs=1200000。不clean/refresh/跳test/注入Gradle任务/装依赖；保留原警告及真实任务摘要，NO-SOURCE不当测试通过。最终non-dev-shadow remap JAR重新取指纹，实际最终JAR优先classpath、独立JVM重跑4/4目标转化及Mixin登记/descriptor/合成回调顺序检查，不只验开发classes或旧JAR。
10. 全部通过也仅签源码/外部反例/构建/独立转化的实际层次；NeoForge launch-service/全模组顺序/完整Quick世界与渲染队列/真实游戏UI仍未授权未测。若五项尚一FAIL、正常C/R不能清理或需扩大白名单/强制平台能力，停报Lead，不安装、不提交，不用成功build掩盖。

**是否可直接C3：可以，在既有授权的前三主修＋必要Bridge窄适配内开始，不再等B；这不是声称修复已完成。待确认项仅是若实现证明无法维持原发布/使用兼容、必须新增平台阻断能力/目录inode或敌手级保证/第九文件时的扩大方案，当前没有自行批准这些扩大项。本A结束归还计划写权Lead。**

A的一句话：让失败还能重试，让合法的包仍能到达，让最后一次删除真的拿得出属于自己的凭据。

### 13.22 C3a交接、旧证据覆盖事故与顺序C3b

- Lead回读[C3a报告](<C:/Temp/reforgedplay-C3a-20261114-6bd2cda2/REPORT.md>)、[实际增量diff](<C:/Temp/reforgedplay-C3a-20261114-6bd2cda2/C3a-only-source.diff>)、[F1/F2/F4修复正向日志](<C:/Temp/reforgedplay-C3a-20261114-6bd2cda2/review-v10.log>)，并用原生ops_bash亲跑sha256sum核对当前Sender=a726713fb13c113a4441aa7a336bd72b077c9f89372a0f6b902dc85b3fc3de05、Handler=b31b453944344801223ba264c7c1a8b44cc6600eb12afbebe348197347a32686。git diff --check通过，仅LF/CRLF提醒。
- C3a仅写上述两源码。F1保留ending/terminal不可逆，分离in-progress/completed及constructionFailed，完整body和finally成功才完成；真实sidecar恢复后第二次实际save/close，成功再次调用跳过。F2分离controlEpoch与stream provenance，前向预读和queued资源保持，真正边界仍失效，协议GameJoin不被普通控制意图取消。F4独立hide自己overlay，仍逐段保护全局归属。源码已写不等于所有模式游戏行为通过。
- [新33组日志](<C:/Temp/reforgedplay-C3a-20261114-6bd2cda2/model-v5.log>)、[新增20组日志](<C:/Temp/reforgedplay-C3a-20261114-6bd2cda2/extra-v4.log>)和F1/F2/F4实际修复断言通过；真实workspace四源Java21外部编译成功，MC/GUI/Loader/转换/Unsafe边界明确。没有项目build、22组重跑、最终JAR或游戏验收；F3/F5有意未跑未修。报告的本轮保留合成样本25,359,531B，仅说明该次审计，不能冒称整个历史任务总量。
- **不可撤销的程序失误：** 首次fork把斜杠格式脚本复制后，用带反斜杠的Windows Path字符串替换，替换未命中且未先停验，首次compile/model仍写到旧D3。旧D3的原始33组model.log、classpath/version/javac日志、生成classes/stubs/model source list遭覆盖。当前该位置内容不再是D3原收据，无法真实恢复；不能把新执行或覆盖后快照冒充原始证据，也不追改历史失败/成功文字来掩盖。详见C3a报告3–6行及其incident-overwritten-D3快照。
- D3的REPORT、D3Harness/GateProvider及指纹、review-v4原始/UTF-8反例、C1的22组及转化证据未被该次调用写入；B/C1/C1R/C2原证据未写。五反例事实仍有保存的独立复现证据。原D3独立33组结果只能按历史交接记载，已失去原model.log这条可独立复核链；后续D2必须全部新执行，不将旧D3 classes/stubs当不可变基线。
- **C3b输出保护先于运行：** 不直接运行任何旧fixed-O脚本，也不以“复制后已替换”当足够保护；建立明确的新独占输出根/统一参数驱动，执行前列出并断言compile -d、重定向、生成stub、设置/fixture/cache/样本/变换输出等所有写入目标属于新根或原批准生产范围。旧路径只允许明确只读classpath/源码输入；检查实际配置未命中、旧写根残留、非空/已有输出覆盖即拒绝运行。记录原目录前后指纹；若再次发现意外写入先停并列清损失，不伪恢复。不为预算删旧证据，小样本仍须实际审计计划限额。
- C3b顺序只修Folders嵌套lease的F3/F5及必要Bridge，保持本次Sender/Handler指纹，三Mixin/资源不动，plan由Lead维护。严格13.21.4–.5：锁外IO、producer覆盖move/登记；成功move后即使terminal也记owned final但不READY；失败/歧义保留；创建时receipt、task外witness、有效凭据失配禁止降级、无身份仅RETAIN且不新增播放硬门。正常C/R零task/anchor残留，unsupported负例零delete，不用全部永久保留换PASS。
- D2额外正向关注：Full已打开reader/read-time代次被Quick或换channel推进失效时，后续必须能重建合法读取而非一直丢包；尤其Full.currentTimeStamp=0、仍有open reader/prefetch、从Quick切回后sendPacketsTill(0)可能no-op这一边界。当前只是主代理源码审查目标，尚未执行，不当已发现或已修；完整Quick/world仍未验收。
- C3b交接后再专用D2完整build/最终JAR转化与全部必要正负例，旧Jar不因C3a通过自动合格。原八文件白名单/不改配置依赖测试/不Git写不装包不游戏不whale-craft/不带权限升级字段继续适用。

C3a原话：让修复的绿灯只覆盖真正跑到的边界，也把我这次误写旧证据的责任完整留在交接里。

### 13.23 C3b候选交付及D2待启动（目标自动续作未激活）

- Lead回读[C3b报告](<C:/Temp/reforgedplay-C3b-20261114-1718-9d82e6f4/REPORT.md>)、[实际增量diff](<C:/Temp/reforgedplay-C3b-20261114-1718-9d82e6f4/C3b-only-source.diff>)关键发布段、[新增33组UTF-8日志副本](<C:/Temp/reforgedplay-C3b-20261114-1718-9d82e6f4/model-06/new.log.utf8.txt>)及全部PASS摘要，亲跑原生ops_bash sha256sum/git status/diff --check。四源指纹匹配收据：Folders3404daa49b8c68706c74bb0b8aca0c97592b7072725bdca057d3fc267e67e024；Bridgebed802d7c16410b962dcd145b8403db574194ba2a69659aa252886d7eda3a3f1；Sender仍a726713fb13c113a4441aa7a336bd72b077c9f89372a0f6b902dc85b3fc3de05；Handler仍b31b453944344801223ba264c7c1a8b44cc6600eb12afbebe348197347a32686。白名单仍八实现文件＋计划，diff检查通过。不是Lead独立重跑全部模型。
- 本片仅Folders嵌套lease+259/-33与Bridge+9/-1。锁外move，producer持到rename/登记退出；正常move后即使终止也沿用同一receipt记final、不READY，真正不明move整task留证零delete。可选task外witness/双anchor加可用key，已建立凭据失配不能洗成unsupported；无有效能力仍可按可信目录合同发布使用，但one-shot RETAINED、零delete。注册/实际submit检查在GLOBAL外且复核permit/connection。Sender/Handler和三Mixin/JSON未改。
- C3b新33组的真实C/R均public fileKey=null，正常多ZIP/READY复用最终CLOSED、task/witness/anchors零残留，根保留；受控move latch下实际stop0ms、实际endReplay4ms返回且producer仍1，之后final记账/正常回收；这是受控provider IO而不是真OS挂起事故。真实同内容ZIP/whole-task复制替换、第二个payload替换均保全foreign并RETAIN；unsupported分支能publish/register/submit/open但零delete，已知key/witness变化拒绝使用。实际symlink权限不足SKIP，不伪称通过。
- 报告另列当前四源外部Java21编译成功、22＋原33＋20及F1/F2/F4正向断言通过；C1R22仍用只读历史wrapper输入，不替代最终JAR转化。MC/GUI/Loader/FML/注册/包转换及Unsafe/受控provider、key-only合成key/定制Path反射OwnedZip绑定均明确分层，不冒充真实NeoForge/Quick世界/渲染队列或GUI游戏验收。
- [旧目录handoff审计](<C:/Temp/reforgedplay-C3b-20261114-1718-9d82e6f4/old-audit-handoff.json>)：1771文件changed/added/removed全空，只说明C3b未再改变开始时的旧目录，不恢复13.22已经损坏的原D3收据。本片新样本2,967,413B＋C3a给定25,359,531B=28,326,944B＜32MiB；这不是全部历史B/C1/C2/D3文件总量。C2小型化fork仅65536→12288随机字节，仍跨8192真实buffer，原部分复制/关闭断言保留；D2新样本要继续核验预算，不删旧证据。
- **新的程序性违规仍须保留：** C3b五次外部新文件write误带被禁止的sandbox_permissions字段及无效justification；一次source edit携workspace-write被拒，未修改源，后续用原生ops_replace成功。没有获批准/扩大权限，但参数行为不合规，不能以没有提权效果代签。其角色禁止技能/联网，报告未虚构技能加载；实际JDK/API源码核查是另一证据层。Lead在本轮仅使用不含这些字段的read/grep/ops操作。
- 凭据不等于null-key目录inode或原子条件delete，marker移植/恶意克隆/父路径并发/最终check-delete窗口和key复用限制照旧。正常支持能力的成功任务不能全部改成RETAIN来凑绿；不明/缺证据任务不删也是明确降级，不能宣传任意RAMDisk完全自动清理。
- 本次get_goal实际返回phase=active、activation=disarmed。Lead只核对交接并记录状态，未resume目标、未启动D2/新构建或游戏，等用户明确继续。之后D2必须独立检查当前最终源码，重跑全部必要正负例、保留13.22 Quick零时间戳/open reader审查目标，专用mc_gradle build -Djava.io.tmpdir=C:\Temp --console=plain及新最终remap JAR优先classpath转化4/4、登记/descriptor/回调顺序；NO-SOURCE不当JUnit。原范围/不Git写/不装包/不动配置依赖测试/不游戏规则不变。

C3b原话：终止不该等待磁盘，也不该让已经搬出的文件丢失归属；没有拿到凭据可以留存，但拿错凭据不能当作没支持过。

### 13.24 用户明确继续D2与验收输入冻结

- 用户本轮直接说“继续D2”。Lead加载当前java-build/neoforge技能，get_goal读rev6/disarmed后恢复到rev7/armed；首次多余edit专属参数的resume被拒且未变更目标，改正参数后取得实际恢复收据。不是把后台完成通知当用户重新激活。
- 重新只读回查wrapper=8.8、gradle.properties目标1.21.1/21.1.82、build.gradle仍Loom1.7-SNAPSHOT/sourceCompatibility21，不采技能新模板升级既有项目。亲跑四源sha256sum，仍与13.23完全相同；git status只原八实现文件＋计划，diff --check无错误，仅LF/CRLF提醒。D2期间Lead不修改生产源码，计划也在本次冻结后等交接再更新。
- D2按13.21–.23独立审查并新执行22/33/20/新33与五缺陷正负例，不把C3b的外部绿灯当独立签字。优先检验13.22已列的Quick/零时间戳/open reader边界：初始无reader的sync0 no-op与已有失效reader/prefetch的0不是同一情况，不能只测拒绝旧包，必须保证合法新stream能重建继续。若确认回归，附真实最小生产代码复现先交Lead/C，不为通过测试改生产或断言。
- 新独占D2输出根、写目标预检、旧目录前后hash保护；禁止直接运行旧fixed-O脚本或复用旧D3生成classes/stubs作为不变基线。所有新文件用不携权限字段的原生ops生成/修改，工具若无法不带字段使用则停报，不以当前danger值或没有实际提权为借口。不得覆盖、重建或伪恢复13.22丢失原始收据。
- D2新保留合成样本限制4MiB内，继续使用C3b小型化但跨8192真实buffer的测试，不删除任何旧证据。C3a＋C3b已28,326,944B，加本次4MiB仍低于32MiB；类/JAR/日志与合成样本分开审计，不宣称全部历史文件总量。C/R只唯一私有根；卷/权限不可用如实SKIP；不改ACL/卸载盘/装包/接触用户回放。
- 完整项目构建必须专用mc_gradle projectDir=C:/Users/zhenz/Desktop/ReForgedPlay-1.21、task=build、args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"]、timeout1200000；不clean/refresh/跳test/注入Gradle任务，不改依赖或实际配置。核对新non-dev-shadow JAR内容/指纹及优先classpath真实转化4/4、Mixins登记/实际descriptor/F-list和正常RETURN顺序。NO-SOURCE不是JUnit通过，工具输出节选不是raw stdout。
- 通过构建仍不能抵消review失败。D2没有生产修复权限；大/小逻辑缺陷均先报告定位、预期/实际与层次，由原范围C处理后再D复验。真实NeoForge launch-service/全模组顺序/完整Quick世界/GUI/渲染队列仍不启动、不预签游戏验收。所有门通过才由Lead复核实际diff并停等用户自行commit。

### 13.25 D2实际FAIL/F6及C3c最小修复（原白名单内）

- Lead回读[D2完整报告](<C:/Temp/reforgedplay-D2-20261115-1651-8e73c9a1/REPORT.md>)、[warmzero可读失败日志](<C:/Temp/reforgedplay-D2-20261115-1651-8e73c9a1/quick-03/warmzero.log.utf8-second.txt>)和当前Full的1333–1415。亲跑Sender SHA256仍a726713fb13c113a4441aa7a336bd72b077c9f89372a0f6b902dc85b3fc3de05，status仅原八实现文件＋计划、diff检查通过。D2审计生产/计划554文件与旧证据3229文件全无变化，未带权限字段，旧损坏D3收据未伪恢复。
- HIGH F6已由生产路径实际复现，不再只是待查：实际Full sendPacketsTill(1)→(0)自行形成zero/open/prefetch，未注入reader/generation/timestamp/prefetch；实际Handler进Quick、模型Quick时间300、退出。旧epoch1→current3，旧reader读3/close0，freshOpens仍2、restart仍1、decoded/sourceOpen/loaderAdds全0，100/200合法包消耗后被正确旧代次门拒绝。原日志exit1未改；MC/Quick/world/setup/decoder边界明确，不称游戏验证。之后真正reset/EOF仍可能恢复，不夸成永远不可恢复。
- D2独立当前源码22＋33＋20＋新33及F1/F2/F4正向通过；F3实际stop0ms/end7ms先于move latch返回，F5真实C/R正常CLOSED/全部anchor清光，unsupported可用但不删，已知替换拒绝。这些只签各自实际层次，不包含失败F6。
- 专用完整build真实成功53s、6 tasks(4 executed/2 up-to-date)、test NO-SOURCE不是JUnit；新non-dev-shadow JAR17,481,741B，SHA2566a5f3eb44e38a64dce4ba0e2abbb0f897460a891f96ce7093f95c8840bb74e3b。D2快照/原daemon字节与工具节选区别保留，最终JAR优先classpath真实Sponge Mojang4/4及ASM23方法/descriptor/F-list/成功正常RETURN检查通过；这不是F6合格或实际NeoForge launch签字。
- **C3c只写FullReplaySender.java**，不写Handler/Folders/Bridge/三Mixin/JSON及任何其他源码，plan只读。修复前先核对同步发送的timestamp-only no-op：若已打开reader或prefetch携带已失效的stream generation，应在这个早退判定前安排已有真实restart路径，真正close旧reader、丢旧prefetch、恢复registry/世界已有重启边界并重新getPacketData，让新读取自然捕获current generation。不得直接改replayInGeneration给旧stream续命，不得修改PacketData.generation，不放宽dispatch/permit/connection/owner/terminal门。
- 保留无reader/无prefetch的初始sync0 no-op，不为单纯控制意图变更或同stream前向rush制造restart；正常>0 Quick返回/倒退/实际换channel仍正确重建。优先一个窄同步路径检查，避免把修复扩成模式/保存事务/协议框架重构；如果发现必须跨白名单或新强制能力先停报。异步/重入可检验，但没有实证不顺手改未涉路径。
- C3c新独占外部目录，统一OUTPUT_ROOT/新输出名，旧固定路径脚本禁止直接重跑，旧source/classpath只读，before/after hash保护，禁止权限字段/提权。角色若禁止skills不虚构加载，使用实际源码/JAR接口。外部编译当前四生产源码，真实D2 warmzero正例应旧reader关闭/newreader+1、restart+1、合法100/200 decoded2/source1/add2；保留失败输入/强断言不放宽。补Quick目标0再后来正向目标、同timestamp换channel、warm/cold0、同stream前向、terminal/owner、真实restart与重复Quick往返。源新增只一文件，其他指纹必须保持。
- D2保留样本1,188,315B，使C3a/C3b/D2累计29,515,259B；C3c预算≤1MiB，后续D-final预算≤2MiB，合计仍低于32MiB，不删除旧证据换预算。继续小型真实ReplayStudio跨8192 buffer样本，不一律ByteArray替代，类/JAR/日志与样本分别审计。C3c不跑项目build，交D-final专用完整build与新最终JAR4/4及必要全回归；任何程序/模型/映射失败原日志保留，不伪造成功。
- 本轮目标尚未达到，维持active/armed，不标完成/不删除活跃目标，不交付/安装/commit。所有门通过再按用户要求结束活跃目标并停等人自行提交，游戏未授权仍不启动。

D2原话：旧包被正确拒绝，只守住了一半；合法的新串流也必须真的有路回来。

### 13.26 C3c八行候选交接及D-final冻结

- Lead回读[C3c报告](<C:/Temp/reforgedplay-C3c-20261115-1735-c4f8219b/REPORT.md>)、[最强warmzero正向衍生日志](<C:/Temp/reforgedplay-C3c-20261115-1735-c4f8219b/quick-02/warmzero.log.utf8.txt>)和实际Full1333–1365，亲跑四源sha256sum/status/diffcheck。仅Full+8/-0：同步同timestamp早退前，现存reader/read-generation或prefetch-generation失效才设已有restartPending；复用真实close/reset/restart/reopen，不重标旧包，也不在无reader/无prefetch初始0制造重启。同stream前向/仅control变更保持。
- 当前Sender=f9ed797110d81adb7958c1762ce02218ef04c5d3947cbfb02a6e9dba5790348f；Folders3404daa49b8c68706c74bb0b8aca0c97592b7072725bdca057d3fc267e67e024、Bridgebed802d7c16410b962dcd145b8403db574194ba2a69659aa252886d7eda3a3f1、Handlerb31b453944344801223ba264c7c1a8b44cc6600eb12afbebe348197347a32686均不变。其他七实现文件/plan在C3c未动；Lead本次仅写计划，D-final开始后不再改候选或计划直到交接。
- 最强D2实际public sendPacketsTill(1)→(0)原输入与强断言字节相等，新child exit0：oldEpoch1→4、oldClosed1、read-source opens2→3、restart1→2，decoded2/source1/add2。不是Injected reader/time/prefetch用例。原D2exit1留原处。Quick目标0后继续、同timestamp换channel、cold0、真实warm>0、reader-only/prefetch-only、重复Quick、restart自身换channel等分别通过，注入/MC/Quick/decoder/world边界照实说明。
- C3c四源外部编译成功；22＋原33＋F1/F2/F4通过，F3/F5只32组，真实R因本片单C输出根约束SKIP；原20仅执行3个相关未改断言，**不是完整20**。D-final必须补全20/真实R正常清理，不能把前一版的证据悄悄算本版新执行。实际symlink权限不足仍SKIP；完整NeoForge launch/世界/UI/render/game不预签、不启动。
- C3c旧证据4036文件零变化，生产/计划554与非生成workspace1347仅Full变化；当前四源与编译snapshot一致。没有权限字段调用/旧证据覆盖/伪恢复。外部Quick stub缺方法、非Sharable decoder/Quick夹具等初次失败均留各自日志，修的是新外部夹具、不改业务断言；字节序/编码衍生标签不混成原stdout。
- C3c全部保留样本1,030,911B，累计30,546,170B。D-final全部新迭代样本≤2MiB，合计≤32,643,322B＜32MiB；不删旧证据腾预算。真实R可额外用R:/Temp内全新唯一私有测试根，只允许该根的合成样本与测试自有空root善后；这项明确例外解除C3c只能C输出根导致的R跳过。C日志/类/stub/cache/fixture等仍新D-final OUTPUT_ROOT，R例外同样预检/审计，不扫/删R:/Temp根或其他任务。
- D-final只读生产和计划、独立检查8行F6＋六缺陷正负例/22/33/完整20/新33与Quick矩阵，R不可用则如实SKIP并说明未签门；不为“最后一次”降低断言。专用mc_gradle build -Djava.io.tmpdir=C:\Temp --console=plain/timeout1200000，不clean/refresh/跳test/改构建，最终non-dev-shadow JAR重指纹并优先CP真实Sponge4/4/登记/descriptor/回调顺序。NO-SOURCE不当JUnit通过，外部probe不当实际游戏。
- 全部新输出明确参数/新名字，不直接跑旧fixed-O，不碰损坏D3生成基线；旧证据before/after hashes，禁止权限字段/提权，合规ops工具不可用就停报。新增逻辑问题先报告，原范围C处理后再验，不自行修代码或扩文件。本轮仍未达目标，不标complete；全部门通过后再结束活跃目标并停等用户commit，实际游戏仍未授权。

C3c原话：旧包继续被拒绝，但现在合法的新串流也真正重新打开，并把该到的包送到了边界。

### 13.27 D-final PASS与Lead最终源码提交检查点（只签授权层）

**结论：第三批P的实现、可执行外部回归、真实C/R小样本、完整项目build及独立最终JAR转化门已通过。可以交用户审查并自行提交本批源码；不是实际客户端/全模组/游戏验收，不代安装/commit，不进入下一批。**

#### 最终实际范围与完整差异

Lead回读[D-final完整报告](<C:/Temp/reforgedplay-Dfinal-20261115-1800-52f1c8ab/REPORT.md>)、[真实构建工具收据](<C:/Temp/reforgedplay-Dfinal-20261115-1800-52f1c8ab/BUILD-TOOL-RECEIPT.md>)、[最终指令结构](<C:/Temp/reforgedplay-Dfinal-20261115-1800-52f1c8ab/artifact-structure-02/structure.log>)、4/4真实转化日志，并核对当前Mixin三份源码、client登记、bridge释放关键链、lease发布/清理、Sender许可与Handler同步退出/Quick重建关键段。没有在验收后修改生产实现。最终差异相对HEAD=d4911356b918c8e34d32784ca6436ff8b64c5c43：

| 实现文件 | 实际+/- | 本批职责 |
| --- | --- | --- |
| [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>) | +466/-0 | 仅新增一次设置快照与嵌套资源ZIP租约；流/完整发布/身份凭据/精确清理 |
| [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>) | +367/-95 | producer/queued/actual许可、永久terminal、stream/control代次、合法前向与Quick零时间戳真实重建 |
| [ReplayHandler.java](<src/main/java/com/replaymod/replay/ReplayHandler.java>) | +75/-15 | 构造失败/Full生命周期归属、同步退出与失败重试、旧overlay自己清理/新owner隔离 |
| [ReplayResourcePackReleaseBridge.java](<src/main/java/com/replaymod/core/files/ReplayResourcePackReleaseBridge.java>) | 新增662行 | UUID票据/provider/current真实cycle、多raw首close/SEAL/tombstone与异步精确回收 |
| [Mixin_ServerResourcePackLoaderLease.java](<src/main/java/com/replaymod/core/mixin/Mixin_ServerResourcePackLoaderLease.java>) | 新增110行 | loader实际add/remove/init/provider观察与精确factory |
| [Mixin_MinecraftResourceReloadLease.java](<src/main/java/com/replaymod/core/mixin/Mixin_MinecraftResourceReloadLease.java>) | 新增62行 | 原F/list identity、实际install/失败/成功正常RETURN关联 |
| [Mixin_ReplayZipFileLease.java](<src/main/java/com/replaymod/core/mixin/Mixin_ReplayZipFileLease.java>) | 新增43行 | owned-only完整同步open/close接管，foreign仍vanilla |
| [mixins.core.replaymod.json](<src/main/resources/mixins.core.replaymod.json>) | +3/-0 | 仅三个client登记，旧compatibility/minVersion不变 |

实现合计+1788/-110；本计划另计，不能把普通git diff stat漏掉的四个untracked新Java文件排除。git status正好这八实现文件＋计划，无第九实现；第一批/Image/Setting/依赖/构建/wrapper/实际配置/仓库测试/.env/.vscode没写。当前HEAD未变，无Git写/安装/游戏/whale-craft。

Lead用只读git diff及四份新增文件的no-index diff生成[完整八实现文件真实patch](<C:/Temp/reforgedplay-Lead-final-9031df02/implementation.patch>)，128836B，SHA256=e3c24364419a012037dca17e6a89a516b7bb775636e233ae05adc536ae57e32e；不是手写或只有tracked部分。该patch不含本计划，当前计划以本文件为准；[八文件最终指纹](<C:/Temp/reforgedplay-Lead-final-9031df02/implementation.sha256>)与本次亲跑四源sha256sum一致。保存patch无Git index/commit写操作，no-index差异exit1属正常“有差异”，大于1才是命令错误。

#### 独立执行与最终产物

- 当前实际四生产源码Java21外部编译exit0；C1R22、原33、**完整extra20**、新F3/F5 33全部新执行通过；F1/F2/F4真实正向通过。最强D2原F6输入/断言字节不改，实际sendPacketsTill(1)→(0)生成warmzero、真实旧reader close1、新source opens2→3、restart1→2、decoded2/source1/add2。另全部18个Quick/连接正负例通过，包含control-only/forward/cold0/repeated和restart内部setChannel，不仅是旧包拒绝。
- 真实C和R都独立执行，public fileKey=null、different-path witnesses生效；正常任务CLOSED、task/anchors零残留，selected root先断言保留再由测试自己的空root善后。已知替换和未知move保留，unsupported能力可使用但零delete；不把“全留存”当正常成功。实际OS symlink因权限不足SKIP，不计通过。
- 专用mc_gradle task=build、args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"]、timeout1200000：**BUILD SUCCESSFUL in 23s，6 actionable tasks：4 executed、2 up-to-date**；compileJava/jar/shadowJar/remapJar实际执行。test/compileTestJava等NO-SOURCE不当JUnit成功，remapSourcesJar SKIPPED不假签。实际Loom1.7.435/Gradle8.8/Oracle21.0.12.1，原1.21.1/21.1.82/Yarn+patch不变。
- 构建tool未返回独立exitCode/timedOut字段，未编造；真实daemon buildID=99231680-6a8c-4f57-acae-0f10b91502dd及字节区间/hash有收据。工具渲染节选不是raw wrapper stdout。旧Loom/Gradle/remap诊断仍在；历史Thread.stop/Object.finalize三移除警告本次incremental未重发，未假说新输出/已修。
- **最终non-dev-shadow [JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>)**：17,481,807B，SHA256=2a2bc5a1fa4f6a4530edfc904a317197c49240fe51309203d75d09317f4da070。Lead亲跑sha256sum与wc -c再次相符。Jar优先classpath/fresh JVM真实Sponge Mojang四目标4/4转化、三Mixin与receipt嵌套类登记、实际fields/descriptors/F-list槽、成功正常RETURN及ASM23方法＋额外F6方法结构都通过。不是只验旧wrapper/dev-shadow，也不是完整NeoForge launch/JVM linkage证明。

#### 保护、局限与人工停点

- D-final旧证据5024、源码/计划554、非生成workspace1347项，changed/added/removed全空；44个内部进程exit0。外部两次辅助审计因分隔符/重名fingerprint exit1均保留、最终seal audit exit0，没有通过改业务断言消错。D-final无权限字段，未洗掉13.22丢失D3原日志和13.23历史违规；用户可据这些记录独立决定是否接受流程偏差。
- D-final新保留synthetic samples1,214,545B，C3a以来修复/复验累计31,760,715B＜32MiB，不宣称B/C1/C2/D3所有历史文件都在此数内。未删除旧证据腾预算，类/JAR/logs分列。Lead之后只增加diff/验收记录，不增加合成样本。
- 身份凭据不足时能使用而保留任务是明确安全降级，不能承诺任意RAMDisk都可自动清完。有效凭据失配不能降级。null-key目录marker布局不是目录inode、权限沙箱或条件原子delete，marker移植/恶意父路径克隆、ID复用和最后check-delete窗口限制完整保留。
- **实际NeoForge launch-service、全安装模组顺序、完整Quick world/setup、GUI/渲染队列/实际游戏未启动、未验收**；反射首close故障/key-only synthetic provider/Unsafe/MC边界stub不是OS或游戏证明。JNI/Native/Image第二批旧未测问题不顺手修改。后续人工可用合成/可丢弃回放先验pack正常/元数据fallback、Forward/Back/EOF、Quick0往返、退出后立即新回放/渲染队列切换、实际路径/R能力；OS ACL/full disk/卸载卷/共享锁场景不得假签。
- 本批到此停下：用户先审查这八实现文件＋计划并自行commit，AI不Git add/commit/push/reset/checkout，不安装或复制到模组目录，不自动进入下一批。所有检查完成后Lead将活跃目标标complete使其不再自动继续；目标物理历史记录删除没有公开工具，不能假称已物理删除。

D-final原话：现在不仅旧包进不来，合法的新串流也真实重开并继续到达；绿灯只签已经跑过的那一层。

## 14. 第四批候选：视频元数据 tempPath 与失败保源（仅 A 规划，2026-11-16，待批准）

### 14.1 当前权限、目标与时代基线

- 用户已自行提交第三批。本轮 A 亲读 `git status --short` 为空、完整 HEAD=e9e89195a25096ad17c753ba003ea3da3dbe5a3b、message=`temp support update`；前一 HEAD=d4911356b918c8e34d32784ca6436ff8b64c5c43。更新后唯一预期差异是本小写 [plan.md](<plan.md>)；13.27 和全部历史失败/权限偏差原文保持。
- 当前只批准规划：A 只改本计划，不启动 B/C/D、不写源码/harness/样本、不构建、不 Git 写、不装包、不碰实际配置、依赖、wrapper、.env/.vscode、游戏、whale-craft 或旧证据。不请求权限字段/升级；合规工具不可用即停报。本轮未重新加载技能，不把 Lead 已读技能冒称 A 本次加载。
- 已重读 [build.gradle](<build.gradle#L1-L11>)、[gradle.properties](<gradle.properties#L6-L20>)、[wrapper](<gradle/wrapper/gradle-wrapper.properties>)、[NeoForge 元数据](<src/main/resources/META-INF/neoforge.mods.toml>)、libs 布局。保持 MC1.21.1 / NeoForge21.1.82 / Java21 / Gradle8.8 / Architectury Loom1.7-SNAPSHOT（13.27 实际1.7.435）/ Yarn1.21+build.1＋patch1.21+build.4；这是现代 Java21 既有 Loom 工程，不迁移 ModDev、不升级版本。
- 只做视频球面元数据这一片：一次任务快照 tempPath；候选视频在所选 temp 创建；正式输出在安全提交前从不 truncate/删除。失败保留原视频或明确的恢复材料，元数据成功有实际验证而非日志猜测。
- 排除 rawPath/recordingWorkPath/tmp/ReplayStudio 保存恢复、cache identity/ephemeral、native、图片及资源包 lease/Full/bridge/Mixins 重构。现有 [Setting.java](<src/main/java/com/replaymod/core/Setting.java#L5-L7>) 三个路径键不增改；不宣称完成全部 RAMDisk 需求。

### 14.2 实际依赖与调用链证据（只读核查，不是新测试 PASS）

- [构建脚本](<build.gradle#L66-L108>) 为 `include(implementation('com.googlecode.mp4parser:isoparser:1.1.7'))`；实际缓存 [二进制 JAR](<C:/Users/zhenz/.gradle/caches/modules-2/files-2.1/com.googlecode.mp4parser/isoparser/1.1.7/bb17c600cf992fdfd8d5ba58f3f424b34ee46ea2/isoparser-1.1.7.jar>) 为899506B，SHA256=ddbbf392f8e61f770a509a49aed5f32ead19768283b4b512c626e09e1ba5cc46。没有运行 Gradle 解析/依赖测试或下载。
- 当前 [正式 JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>) 内确有 `META-INF/jars/isoparser-1.1.7.jar`，919579B，SHA256=da927bb9fde212f3f19cbd9dfb773710bd5b6ed5341550328c9033440e89e042。整 JAR 字节不等，但逐项比对全部478个 class 与缓存完全相同，新增 nesting metadata；不能拿 ZIP 包整体 hash 不同误判 API 不同，也不把嵌套存在当 NeoForge 实际加载通过。
- 用绝对 Java21 `javap.exe -p -c` 核对实际二进制 IsoFile/FileDataSourceImpl；并只读打开同版本 [sources JAR](<C:/Users/zhenz/.gradle/caches/modules-2/files-2.1/com.googlecode.mp4parser/isoparser/1.1.7/56d02a39a8e0bfa82bf6b9c7cb9470fba3ce6927/isoparser-1.1.7-sources.jar>) 的容器、UUID、free、mdat、MemoryDataSourceImpl。首次查错两个归档条目 exit11，已纠正到实际路径；不是编译/业务失败。
- [MetadataInjector.java](<src/main/java/com/replaymod/render/metadata/MetadataInjector.java#L95-L140>) 当前复制整视频到系统 temp，再 `FileOutputStream(videoFile)` 截断原件，catch Exception 仅记录，finally quiet-close/delete 副本。这里是源码风险，不是用户已发生视频丢失事故。
- 另一个本片必须处理的格式风险：现有 `new UserBox(new byte[0])`＋把 UUID 拼入 data，与1.1.7 `AbstractBox` 固定计16字节 userType 的尺寸计算不一致；必须改成16字节 UUID 作 userType、仅 UTF-8 XML 作 data，不能继续原写法后只测 exists。
- [VideoRenderer.java](<src/main/java/com/replaymod/render/rendering/VideoRenderer.java#L233-L253>) 在 pipeline.run 后注入、随后 finish；[完成 UI](<src/main/java/com/replaymod/render/rendering/VideoRenderer.java#L418-L434>) 只看 hasFailed，不知道 void 吞错。[RenderSettings](<src/main/java/com/replaymod/render/RenderSettings.java#L137-L169>) 保存的是请求开关，不是注入成功凭证；[GuiRenderingDone](<src/main/java/com/replaymod/render/gui/GuiRenderingDone.java#L73-L85>) 无元数据状态。
- [Pipeline](<src/main/java/com/replaymod/render/rendering/Pipeline.java#L75-L90>) 正常路径关闭 consumer，但 [FFmpegWriter.close](<src/main/java/com/replaymod/render/FFmpegWriter.java#L82-L105>) 等至多30秒后 destroy，未保证进程已退出/exit0。不能凭 run 返回就写仍活跃视频；候选白名单因此包含一个只读完成门，不重做 FFmpeg 生命周期。
- 第二调用方 [YoutubeUploader.preUpload](<src/main/java/com/replaymod/extras/youtube/YoutubeUploader.java#L130-L198>) 转码 waitFor/exit0 后调用；它还用 settings 开关推断“已经注入”。新结果必须贯通这里，不能渲染降级后上传仍跳过检查。当前 `deleteOnExit` 转码文件/Thread.stop 等旧问题不顺手修改。

### 14.3 外部 API 来源与已知限制（核查日2026-11-16）

| API/事实 | 已读来源与决策 |
| --- | --- |
| `IsoFile(DataSource)`、`getBox(WritableByteChannel)`、`close()` | [官方1.1.7 IsoFile](<https://raw.githubusercontent.com/sannies/mp4parser/mp4parser-project-1.1.7/isoparser/src/main/java/com/coremedia/iso/IsoFile.java>)＋本机二进制；getBox遍历懒加载盒，close仅关闭 DataSource，不关闭调用者输出，也不保证解除所有映射。 |
| 默认 String 构造使用 FileDataSourceImpl.map→FileChannel.map | 本机二进制/同版源码；[JDK21 MappedByteBuffer](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/MappedByteBuffer.html>) 明确映射持续到 buffer 被 GC。不能用 quiet-close、System.gc、反射 unmap/native 或删除重试冒充 Windows 映射已解除。 |
| `MemoryDataSourceImpl(byte[])` / `(ByteBuffer)`，map只切内存buffer，close为noop | [官方1.1.7实现](<https://raw.githubusercontent.com/sannies/mp4parser/mp4parser-project-1.1.7/isoparser/src/main/java/com/googlecode/mp4parser/MemoryDataSourceImpl.java>)＋缓存源码；只用于有上限的 moov，不读整视频到内存。 |
| 懒解析可能将 IOException 当迭代结束；部分 getBox 只调用一次 write/transfer | [官方1.1.7 BasicContainer](<https://raw.githubusercontent.com/sannies/mp4parser/mp4parser-project-1.1.7/isoparser/src/main/java/com/googlecode/mp4parser/BasicContainer.java>)＋实际 AbstractBox/mdat 源码。因此需独立边界/长度验证、完整写入适配；不把“能打开 IsoFile”当完整文件证据。 |
| UserBox userType 与 data 分离 | [官方1.1.7 UserBox](<https://raw.githubusercontent.com/sannies/mp4parser/mp4parser-project-1.1.7/isoparser/src/main/java/com/coremedia/iso/boxes/UserBox.java>)＋实际 AbstractBox；UUID16＋XML UTF-8，验证实际序列化尺寸/内容。 |
| `Files.move(..., ATOMIC_MOVE)` 与目标存在 | [JDK21 Files.move](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)>) 正文已读：其他选项被忽略，已有目标是替换还是失败由实现决定；跨store/provider可能不支持。非原子move异常时状态未定义。 |
| Windows 原子分支与 null-key | [OpenJDK21u WindowsFileCopy](<https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileCopy.java>) 原子分支为 MoveFileEx(REPLACE_EXISTING)，普通替换分支可先删目标；[WindowsFileAttributes](<https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileAttributes.java>) fileKey返回null。官方21u不是本机驱动实测或条件替换保证，生产不调用内部API。 |

### 14.4 推荐路线 A：tempPath 放新输出，尾部 moov 的保守子集

1. **只处理普通非fragmented MP4、单一 moov且为最后一个顶层盒、单一 trak、至少一个完整 mdat；moov上限16MiB（待用户接受的兼容边界）**。拒绝 moof/外部媒体引用、畸形/截断/溢出长度、size0等未支持布局、无moov/无trak/多轨/前置moov；原视频不动并明确元数据未应用。不扩为通用 remux 或改 stco/co64 的框架。
2. 理由：当前 [默认MP4参数](<src/main/java/com/replaymod/render/RenderSettings.java#L63-L70>) 没有 faststart/moov_size，且默认无音轨；典型结果是尾部moov，但**实际ffmpeg输出布局仍未测，不保证所有自定义参数命中此子集**。不能只保持总文件长度就断言media offsets安全。
3. 用纯JDK长整型顶层header扫描与溢出/EOF检查记录extent，只把有界moov读入 heap；用 `IsoFile(new MemoryDataSourceImpl(...))` 解析这一个盒，完整round-trip先证原moov无无关变化，再加UUID/XML。其他所有字节（包括 free/mdat、chunk offsets指向的媒体）原样流式复制，moov保持原起点，仅在文件尾增长。无需缩减free，**无free/小free在合格尾moov布局可成功**；前置moov即使有free也先保守跳过。
4. heap预算须连输入moov、解析/序列化临时副本一起估算（目标≤64MiB，不冒称旧解析器有严格总heap配额）；前置检查子盒边界/数量及声明条目数与盒字节范围，遇不确定/过量即跳过，不让畸形count触发大分配。媒体用固定≤1MiB缓冲；moov生成/校验均有大小上限和完整写入循环，不 byte[]整视频、不对原视频调用默认映射 IsoFile、不解析mdat内容为内存盒。
5. 开始快照一次 tempPath。所选root下独占 `.replaymod-video-...` 任务目录，以 CREATE_NEW 创建 `candidate.mp4`，直接将“原视频前缀＋新moov”流式写进去；**不额外复制整份输入到RAMDisk**。关闭源channel、输出stream/channel、IsoFile及DataSource后才进入校验/提交；构造IsoFile失败也要关闭已取得的source，主异常保留suppressed。
6. 候选关闭后验证顶层extent/新moov/trak/UUID/XML、预期长度与moov增量；流式比较原前缀至moov起点完全一致，特别是所有mdat的原始起点/长度/媒体字节；stco/co64原条目保持且指向原媒体范围。独立盒边界检查补足懒解析吞IO，失败不发布。
7. 在**原视频真实父目录所在目标卷**独占建stage目录/CREATE_NEW payload，流式复制candidate，关闭后 `Files.mismatch(candidate, stage)==-1` 并复核结构；提交只从该stage到原目标，绝不跨盘直接替换。没有可靠同卷信息也不猜盘符；容量查询可选、失败不阻断安全检查，实际IO错误必处理。
8. RAMDisk候选约需视频大小N，目标卷额外stage约N；原N一直在。所选temp在实体盘时会有两份新输出写入，这是保源/跨盘提交代价，**不能宣传默认也省全部写盘**。本片不加同卷move/硬链接快路径；若此容量/写入代价不接受，选路线B延期，不反加一份无收益的RAM输入副本。

### 14.5 快照、发布、身份与失败合同

- 新服务入口（项目自有拟议API）`snapshotVideoMetadataTempRoot() throws IOException`：只读一次现有TEMP_PATH，空/null/全空白返回默认选择标记null；绝对原卷、普通相对固定mcDir、非空不trim、不展开变量。拒绝 `R:Temp`/`R:`/`\Temp` 等半绝对语法及invalid，诊断原值/阶段/解析root；不静默回退、不复用 Image.TemporaryFile 或资源包lease，不改两者现有语义。
- helper在任务内固定实际root、真实target parent、所有路径与快照；默认调用JDK默认temp目录重载，显式root可createDirectories/toRealPath但不取得root删除权。配置热更新仅影响后续任务；失败不得转系统temp/原视频目录伪装配置已生效。不设全局java.io.tmpdir。
- 所有目录、payload、可选witness一创建成功立即记本次ledger（阶段/归属/完整性/close状态）；CREATE_NEW失败不认领冲突项。可信root/父路径任务期间不遭恶意置换、输入已关闭且独占是明确前提；NOFOLLOW类型/真实路径/可用fileKey辅助检查，不把marker或exists当沙箱/原子身份。
- 记录原普通文件size/mtime/可用key及流式SHA-256，提交前重新检查属性和完整digest、拒绝已知替换/同长度内容变化；进程内注入入口串行防自家同目标并发。Windows null-key不报不可用；可选hardlink witness可加强identity，失败不把原可用卷变成新硬要求。没有witness时同内容替换/恶意父路径与check→move窗口仍依赖独占契约，**不宣称跨进程CAS/无竞态替换**。
- 唯一提交动作 `Files.move(stagePayload, original, ATOMIC_MOVE)`，是显式授权替换这个已完成的视频，不是新文件的不覆盖发布；REPLACE_EXISTING不能加强ATOMIC的可移植语义。先证明target仍为输入、stage完整且全部句柄已close。原件在此动作成功前一直不由本功能truncate/delete；不先删原、不备份rename后腾空原路径、不退回非原子REPLACE/move/copy。
- AtomicMoveNotSupported、已有目标不可替换、Windows占用/映射锁等：保守降级为“不应用元数据、原视频保留”，保留已验证candidate/stage诊断路径，不盲重试。只有复核原完整digest/状态后才能说UNCHANGED；若提交异常/后验读取异常导致无法确认，标INDETERMINATE，保留全部完整材料，不假称原件仍在或视频丢失。
- 提交正常返回后标APPLIED，再只读验证目标与stage预先digest/结构一致；失败标INDETERMINATE，不做自动反向覆盖。清理失败是已应用但有遗留的警告，不把成功视频改说丢失，也不重做提交；不承诺fsync目录、跨provider强持久化、断电恢复事务或崩溃自动清理。
- 正常确认后才精确删本次已可丢弃的candidate/可选witness和空任务目录，永不递归/删root/删陌生项；删除前身份边界无法确认则保留并诊断。parse/write失败的部分candidate在源已证未变且所有句柄close成功时才可清；close失败保留。完整新候选/stage是恢复材料，任何失败路径不得finally删掉唯一完整新版本；不可把视频helper套成图片“一律TWR清空”。
- 主要结果设计为helper嵌套 `APPLIED / ALREADY_PRESENT / UNCHANGED / INDETERMINATE`＋cause/phase/实际路径/cleanup诊断（项目自有设计，非外部API）。正确UUID/XML已在合格原视频中实际验证才ALREADY_PRESENT，不能仅用settings开关判断；重复调用不重复插入。非本支持布局但无法验证既有元数据，不假签ALREADY_PRESENT。
- 保留现有void入口为兼容facade，新增显式结果入口供两个实际调用方使用；void仅best-effort有明确日志，不能作为成功凭证。渲染遇UNCHANGED可保留“视频渲染完成”但日志明确“球面元数据未应用、原视频未改”，此**仅日志提示、无新增GUI**需用户接受；INDETERMINATE经setFailure后仍走finish拆除，不显示成功窗口，不提前throw跳过finish。
- 上传球面视频不再以请求开关跳过：始终验证/幂等注入，只有APPLIED/ALREADY_PRESENT才继续；UNCHANGED/INDETERMINATE用已有IOException流程停止上传，消息明确原视频/材料位置，不称视频渲染失败或丢失。不联网/登录/实际上传验证；旧非MP4转码deleteOnExit文件不冒称永久恢复材料，其原videoFile仍是保留源。
- 渲染仅在非cancelled/hasFailed、ffmpegWriter存在、其进程已退出且exit0、实际 `getVideoFile()` 与预期目标一致时注入；否则跳过并诊断，不碰可能仍写的文件。给FFmpegWriter加只读ready查询，不能改close等待/kill策略或伪造进程已关闭。

### 14.6 待批准的精确源码白名单与责任（当前一个也未获写权限）

| 候选文件 | B最小框架 → C实现 → D只读审查 |
| --- | --- |
| [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>) | 仅新增视频root快照入口/必要局部解析；旧image、资源lease及底部raw/work/cache方法逐字不动。 |
| [MetadataInjector.java](<src/main/java/com/replaymod/render/metadata/MetadataInjector.java>) | 新显式结果入口、原void兼容facade；保留mono/ODS XML/FOV语义，改UUID/UTF-8构造并调用专用helper。 |
| 拟新增 `src/main/java/com/replaymod/render/metadata/VideoMetadataTask.java` | 专用有界moov处理/流式candidate与stage/验证/原子提交/ledger/嵌套结果；不建通用事务框架、不增生产测试钩子。 |
| [FFmpegWriter.java](<src/main/java/com/replaymod/render/FFmpegWriter.java>) | 仅新增进程已退出exit0的只读ready查询，名称/契约B固定；不修改现有close/abort/export流。 |
| [VideoRenderer.java](<src/main/java/com/replaymod/render/rendering/VideoRenderer.java>) | 仅元数据调用段的ready/输出路径门、结果分流与必要局部字段/import；保留finish/队列/Post生命周期。 |
| [YoutubeUploader.java](<src/main/java/com/replaymod/extras/youtube/YoutubeUploader.java>) | 仅球面元数据调用条件及结果→IOException；不改网络/auth/转码命令/文件清理/取消。 |
| [plan.md](<plan.md>) | A本轮唯一写入；未来B/C/D只读，收据交Lead，不擅改历史。 |

B/C/D未来须串行；用户一次明确批准本批方案、精确白名单及验证范围后，按该授权顺序执行，不逐阶段重复索取相同授权。当前未获实施批准，任何阶段都不启动；范围或能力限制变化另行确认。D默认不修源码，失败回Lead/C。若要GUI可见警告、支持前置moov/多轨/fragmented、改FFmpeg终止保证或新增其他文件，先停下申请新白名单；不能视此候选表为已批准。

### 14.7 未来验证门与停点（只计划，现阶段不执行）

1. 新批测试预算单列待确认：建议全批累计合成样本≤16MiB、单样本≤1MiB（含candidate/stage/保留失败副本），代码/classes/log另记；仅新独占 `C:\Temp` 目录，批准后才可创建，不继承第三批32MiB额度，不删除旧证据腾预算。跨真实卷/R另授权；没有授权时用外部provider验证边界并标真实跨盘UNTESTED。
2. 使用实际1.1.7 JAR生成小MP4盒夹具，不安装包、不跑真实ffmpeg：正确ftyp/moov/trak/mdia/minf/stbl、stco或co64/stsc/stsz等及真实mdat extent、mono/ODS XML/FOV字段。夹具自身边界/样本offset先独立检查，不把空moov或仅exists当播放器可用样本。
3. 正例：有/无/不足free但尾moov、Unicode/空格/default/absolute/mcDir-relative、设置任务中切换、幂等重复、原前缀/媒体逐字节和全部offset不变；新UUID16/XML UTF-8、盒实际长度/增量、资源真实close及完成后可重开/rename/delete均需断言。
4. 拒绝例：无moov/no-trak/多轨/前置moov/fragmented/坏长度或EOF/超moov预算/非法路径/文件作root。全视频不进byte[]；不能只测helper输入拒绝绕过后面的发布门。支持子集和真实ffmpeg/播放器/MC/native未测明确分开。
5. 故障：候选创建/读源/parse/写出/输入输出close/结构校验/复制stage/容量不足/发布错误/cleanup错误；短读短写零进度需有界退出。只用隔离夹具/新外部harness，不能灌满盘、卸载卷、改ACL/真实配置或加入生产钩子。确定性覆盖不了的分支标UNTESTED，不能以控制流审查签实测。
6. 发布：跨卷边界、ATOMIC unsupported/已存在目标替换能力、Windows占用、发布前已知同长同mtime替换、同目标进程内并发与外部违约；每个非成功样本验证原完整digest不变或如实INDETERMINATE且恢复材料保留，绝不先truncate。无hardlink/null-key仍能按可信目录契约使用；别把不带REPLACE的move/先exists当并发不覆盖证明。
7. 清理：root/外来sentinel始终不动；未知替换/链接/junction/无法确认身份保留；创建对象立即ledger、构造失败也可定位；主异常与suppressed、完整候选保留、close/Windows删除失败不盲重试。实际OS权限不足/驱动不支持如实SKIP，不借synthetic provider冒签OS门。
8. 集成只读检查：两个实际调用方消费结果、settings不冒充receipt、INDETERMINATE不成功UI、渲染finish必走、上传失败不继续、FFmpeg未退出不注入；原voidfacade兼容。对外部stub层/真实mp4parser层/最终JAR结构层分开报告，游戏和网络仍无授权。
9. 获批准且C完成后，D最后专用 `mc_gradle(projectDir="C:/Users/zhenz/Desktop/ReForgedPlay-1.21", task="build", args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"], timeoutMs=1200000)`；不clean/refresh/`-x test`/改依赖。必要compileJava另问Lead；记录真实工具返回，NO-SOURCE不当JUnit通过，重读最终non-dev-shadow与嵌套isoparser，不沿用旧构建PASS。
10. 最终diff精确白名单、旧证据保护、实际测试/未测收据交Lead，停等用户审查commit，不自动进入native/cache/事务恢复；真实ffmpeg/播放器/NeoForge游戏验收另行授权。A本轮结束仅运行只读git diff --check与实际plan diff，不新建报告或测试。

### 14.8 用户确认项与阻塞

- **推荐A（待选）**：批准上述六源码（其中一个新增helper），尾moov/单轨/16MiB moov上限；tempPath用于新输出＋目标卷stage，接受两处约N容量及temp在SSD时两份写入；atomic替换不支持则保原并报告元数据未应用，不做非原子降级。
- 接受渲染元数据可选失败仅日志提示、视频仍可完成；球面上传必须实际验证/成功注入，否则停止上传。若要求UI直接显示元数据warning，先单独扩GUI白名单，不把当前完成界面称“元数据已注入”。
- **路线B（安全延期）**：不接受受限容器/容量/仅日志提示时，本批先不实施，另议有界重定位/offset修正或更大GUI方案；只用target sibling、tempPath不生效的方案不能算完成当前需求。
- 需另批上述新C:\Temp预算与harness创建/执行、D构建权限；真实跨卷/R、实际ffmpeg/播放器/MC/native仍UNVERIFIED/UNTESTED，不偷用旧授权。当前阻塞是用户尚未选择/批准，不是已发现的用户事故，也不是缺依赖需升级。

A想说：这次先让元数据失败只能少一项附加信息，不能顺手带走已经渲染好的视频。

### 14.9 用户实施批准、顺序交接与预算（当前优先级高于A的待批表述）

- 用户承接Lead汇总方案A/六源码＋计划/16MiB外部验证/最终build，以及“真实R盘私有小样本是否允许”的整批提问，明确答“嗯，批准”。Lead按整批批准记录：六源码为14.6列出的Folders、MetadataInjector、新VideoMetadataTask、FFmpegWriter、VideoRenderer、YoutubeUploader；计划仅Lead维护；C/R测试限定各卷唯一自有根。不是其他源码/配置/依赖/仓库测试/真实游戏/FFmpeg/实际网络上传授权，不扩大尾moov单轨子集。若需改变限制、加GUI/改FFmpeg退出策略/加第七源码，先停问用户。
- 创建本批独立目标goal-07730e7e-3aaa-42cd-a9d4-83b1ea88fa93，初始rev1/active/armed。基线完整HEAD=e9e89195a25096ad17c753ba003ea3da3dbe5a3b，实施前只有本plan差异，第三批八实现sha256sum --check全OK；wrapper仍8.8。不是恢复已经完成的第三批目标。
- 严格接续A→B窄框架/外部stub→C实际内容/外部验证→D只读独立验收与专用build，阶段不并行写同一项目，不scaffold既有工程。一次本批批准涵盖原范围必要修复与重验，不重复问相同授权。各child只读plan，Lead阶段间回读收据/真实diff后更新，不把异步工具返回当阶段完成。
- B固定自有API/结果和只读writer-ready门、最小调用方接线及helper框架；未实现的关键路径必须明确fail-closed/UNCHANGED或抛未实现异常，绝不能假APPLIED/无效空成功；不运行旧截断原文件路径来伪过回归，不把B候选当可安装。C必须补全并移除交付中的框架占位，D严查实际生产执行。
- C可按helper核心与调用方两片顺序分工，但仍总六源码内，不改第三批resource lease/Full/Bridge/Mixins/JSON、Image或第一批搬移。Folders仅新增视频root快照入口，原各方法逐字保持；FFmpegWriter只读进程exit0/完成就绪查询，不改close/abort/命令/管道。新helper是专用视频任务，不扩通用事务框架。
- 结果只在完整验证后对外返回APPLIED/ALREADY_PRESENT；move动作正常返回但后验不明只能INDETERMINATE，不能先对调用方报告成功。UNCHANGED必须有源完整性证据；失败保留已验证新候选/stage、close不明不删；渲染保留完成视频的可选失败只日志、未知提交失败不成功UI且finish照走，球面上传必须实际receipt才能继续。请求开关不能作receipt，未支持布局也不能虚称已验证元数据。
- 全批保留合成样本≤16MiB、单个文件≤1MiB（独立moov上限/超限可用受控provider或稀疏声明边界，不为测试生成16MiB真实payload）；目录所有硬链接按保守逻辑大小审计，helper候选/stage/失败材料都计入。建议B≤1MiB、C≤8MiB、D预留≤7MiB，阶段间可由Lead重分配但不突破16MiB；代码/classes/JAR/log单列。不得删除旧第三批或本批失败证据腾预算，不继承第三批32MiB额度，不拿空盒当有效媒体样本。
- 新独占C:/Temp/reforgedplay-B4/C4/D4-随机目录，明确OUTPUT_ROOT/参数驱动，所有生成stub、-d、日志、tmp/cache/fixture/sidecar/设置输出先验证属于新根，旧证据只读不可直接跑旧fixed-O脚本。R:/Temp只唯一private测试根及自有样本；root/sentinel保留断言前不得善后，不能扫描/删R:/Temp或其他任务。R不可用/OS权限不足如实SKIP，不提权/装驱动/改ACL/卸载卷/灌满真盘。
- **严禁sandbox_permissions/justification字段或权限升级请求**，不因当前danger值或无实际提权效果而携带。使用read/glob/grep/原生ops_bash/ops_replace/ops_inspect等；需要写新文件可先短ops_bash创建唯一空文件/复制已读小模板，再用精确路径literal ops_replace（glob=[]），长脚本先合规落盘再script_path执行。无合规工具就停报，不通过Gradle注入任务/重注册插件绕过。历史旧证据覆盖及违规只作警示，不重演/伪恢复。
- MC1.21.1/NeoForge21.1.82/Java21/Loom1.7/Gradle8.8/实际isoparser1.1.7保持。最终D必须mc_gradle task=build args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"] timeout1200000；不clean/refresh/跳test、不写构建，不用shell gradlew。NO-SOURCE不是JUnit通过，实际mp4parser/受控FS/MC-stub/最终Jar层分别报告。未启动NeoForge/播放器/FFmpeg/上传，不预签视频真实可播或游戏接受。
- 全部门通过后Lead真实diff/白名单/指纹复核、更新计划并关闭活跃目标，停等用户commit，不Git写、不安装、不自动进入native/cache/恢复下一批。当前只是授权和B起点，没有本批实现/编译/业务验证成功收据。

### 14.10 B窄框架真实交付、Lead复核与顺序C分片

- [B交接报告](<C:/Temp/reforgedplay-B4-20261117-0046-96e31d2a/REPORT.md>)、[真实六源码patch](<C:/Temp/reforgedplay-B4-20261117-0046-96e31d2a/six-source.patch>)、[前后指纹](<C:/Temp/reforgedplay-B4-20261117-0046-96e31d2a/source-fingerprints.tsv>)。Lead回读报告、实际helper全72行、MetadataInjector结果入口、Renderer metadata段、Uploader球面段及stub35条日志，亲跑六源sha256sum/status/diffcheck；仅五既有源码＋新增helper＋Lead计划，无第七source。
- B固定自有签名：Folders.snapshotVideoMetadataTempRoot():Path；FFmpegWriter.isVideoMetadataReady():boolean；MetadataInjector.injectMetadataWithResult(...):VideoMetadataTask.Result throws IOException，旧void facade保持。helper.apply(Path source, Path selectedTempRoot, byte[] userType, byte[] xmlUtf8)静态同步；内嵌四状态Status与Result(status,phase,cause,source,tempRoot,candidate,stage,cleanupDiagnostics)，metadataPresent仅前两成功状态，asIOException带原cause及完整路径诊断。候选/stage为payload路径，未建可null，孤立目录/witness须诊断可查；不是外部API。
- MetadataInjector已移除旧复制/映射/截断原件/finally删副本路径；root单快照、UUID16/XML-only UTF8分离。Folders仅插入视频快照入口、FFmpegWriter仅插入只读ready方法；其余旧代码有可重构字节相等证明。Renderer仍finish/Post，未知结果setFailure；Uploader球面实际成功才继续，非球面及网络/transcode/cancel不变。
- **helper仍显式UNIMPLEMENTED IOException，在任何文件IO前失败，没有APPLIED或UNCHANGED伪receipt。** 当前请求元数据的渲染会记录失败但仍走finish/Post，球面上传停止；这是未交付的B框架状态，不能因编译过就装包。C必须移除最终交付的placeholder并实现真实事务。
- B实际六源外部Java21 --release21/-proc:none编译exit0；实际root/ready/facade/helper-stub共35断言通过，另提取源码literal调用段＋**假helper receipt**的37断言通过（仅分流层）。实际isoparser验证UUID16/XML小盒，不是媒体fixture或视频事务。第一次Windows argv超长、路径空格预期错误、logger-stub缺方法与编码失败均保留，修的是外部工具/错误测试预期，未伪造业务通过。
- 冻结B六源：Folders7ab091e869f37a8add2d69d094f82c31dfec94d942af5926a473e958c55b4c62；Injector9324af5bcf8b36bacf7506054faaf1a31fa63405a75775e20958718113e6d2c8；Task e740fea1efd8e6195d4105028a42278de41fa5b17eebcd8b3900e6477e4cb6ba；FFmpegWriter d864713396c24554451a6d9c7ac72d72660aab7e3fe2a243fc2f40b763e993f9；Renderer1a8352c5d20c882669ec198636326eb7c05844a5ec1ad06d93f242d1b31be605；Uploader15aafae687d46794e1ba40c2881b35f69f11f4bc7a53e3df5427255c55afd0bd。C1仅改Task，其余五指纹保持，必要API问题先交Lead。
- B旧证据6110文件changed/removed/added全空，未带权限字段、未覆盖原证据；plan在B内字节不变。仅保留两sentinel共54B，无MP4/candidate/stage，没有触R/真实FFmpeg/游戏/上传，没有project build，旧第三批结果不被复用为本批验证。
- **C1**专修helper核心：有界完整extent/子盒计数与roundtrip，UUID幂等/支持子集、流式candidate、目标卷stage、close/全验证/原子替换、未知结果保留、创建ledger与精确清理；使用真实依赖和小合成MP4，预算所有迭代≤6MiB，单file≤1MiB。原五API/调用方冻结，不加生产test hook或新source。失败回报不扩大格式限制/改原子合同。
- **C2**顺序完成剩余真集成验证：不靠B假receipt签字，将实际helper与实际facade/ready/调用边界串起，完整单任务root/实际结果路径/同目标并发、渲染finish与失败UI门、上传无网络gate、预算≤2MiB，必要修复仍仅原6source。实际整段VideoRenderer/game无法执行明确分层，外部提取/Unsafe边界不能冒充游戏。
- D在C1/C2后独立全部必测正负例/真实C/R/专用完整build＋新最终Jar/classes/实际嵌套isoparser重核。B54B＋C最多8MiB＋D最多7MiB合计低于16MiB（余量保留），阶段总byte准确审计，不删旧失败日志/样本腾预算。真实播放器/FFmpeg/NeoForge/网络仍未授权，最终源无占位才可进入D门。

B原话：I would rather hand C an unmistakable exception than a plausible-looking success that still truncates the video.

### 14.11 C1核心候选收据与C2真实集成门

- Lead回读[C1完整报告](<C:/Temp/reforgedplay-C4a-20261116-180250-2958/REPORT.md>)、实际helper主流程1–235及ledger948–1125、[run06原日志](<C:/Temp/reforgedplay-C4a-20261116-180250-2958/run06/java.raw.log>)关键正负例与TOTAL PASS243；亲跑六source SHA/status/diffcheck，唯一C1源码改动为Task，其他五source仍14.10指纹。Task现453d4b236990fbdf986ef9c23fc03c3a3ea7cfb874cdddd2df24bf15bec624a0，1125行/60253B，B API不变且UNIMPLEMENTED已移除；[单源真实patch](<C:/Temp/reforgedplay-C4a-20261116-180250-2958/audit-final01/one-source.patch>)包含untracked helper，普通git diff仍不含它。
- 实现独立extent/child/count/sample/chunk/media预检，≤16MiB有界moov/实际MemoryDataSourceImpl roundtrip，UUID16+XML-only增量及仅祖先size/追加UUID字节变化证明；尾moov之外prefix流式不改。candidate→目标parent stage完整复制/验证，fresh源size/mtime/可用key/SHA后唯一ATOMIC_MOVE，postverify后才APPLIED；异常move/后验不明保留候选而不回滚或假签。ledger即时登记、可选witness、精确非递归清理，cleanup失败不撤销已验证成功。
- 当前真实helper与1.1.7独立classpath执行243断言、六生产source外部Java21编译exit0。AVC小夹具先由独立parser验表/mdat/NAL/timing，非实际codec解码。C↔R真实双向APPLIED、幂等/根sentinel/正常残留清理/实际reopen-rename，OS NOSHARE_DELETE真实占用使UNCHANGED且保留candidate/stage；关闭该测试句柄后能rename。注入provider故障和真实OS收据分开，不拿其中之一替另一层。
- 新布局/表计数/offset拒绝、同目标序列化、短/零IO（17次零进度停止）、create/setup/constructor-close/双close suppressed、candidate/stage腐坏、unsupported atomic/未知move/postread、源替换、已知candidate替换/cleanup错误/foreign sentinel、null-key无link可commit但不明清理保留等都有断言。受控provider仅逻辑>16MiB、物理<100B验证早拒，不是实际大视频/near-limit heap测试。
- C1未测非AVC正例、近16MiB heap、真实FFmpeg/播放器/MC/UI/上传、OS容量/权限/卸载盘/不支持原子驱动、实际memory-only解析器内部close/constructor故障；null-key源完整digest不等于对象identity，同内容同时间替换不可观察、恶意ancestor/CAS/最终check-delete窗口仍是可信独占合同。未知盒/元数据item/codec config保守拒绝，不宣传任意单轨MP4普适可用。C2/D应明确实际支持表，并可用小合法config补非AVC结构正例，不假签真实解码。
- C1仅helper/无plan写、无权限字段/旧证据覆盖。旧6293文件全无变化，source/plan/build493项仅helper变化；全部迭代C+R样本630文件1,406,880B，最大301638B，hardlink按路径全逻辑大小计。B54B后累计1,406,934B；C2仍≤2MiB、D预留≤7MiB，全批不超16MiB、单file≤1MiB。正常candidate删除是生产cleanup实测，不删历史失败证据腾预算。
- C2必须使用真实helper+MetadataInjector+Folders+ready闭环，测试实际FOV/XML/pano/ODS/UTF8、一次snapshot/热更与actual root、真实APPLIED/ALREADY/UNCHANGED/INDETERMINATE影响Renderer finish/失败门与球面上传stop；不再拿B假receipt的37断言当事务集成。MC/FFmpeg/YouTube/GUI/events若边界stub或提取literal段则明确标注，尽量实际bytecode，无真实网络/进程/游戏。必要接线修复限原六source，helper默认冻结，发现核心逻辑反例先交Lead，不默默重做1125行核心或放宽支持/发布门。
- C2与D保护原B/C1脚本/日志/fixtures及其R根，不能直接执行旧fixed-O；fresh OUTPUT_ROOT/新名/-d/tmp/sidecar全部预检。原fixture若读可能旁写必须复制到新根或生成自有小夹具；保持原错误日志。最终project build与新最终JAR/嵌套isoparser验证仍留D，不把外部javac或243断言当NeoForge/播放器接受。本批尚未完成，目标保持active/armed。

C1原话：I would rather leave a clearly located verified candidate than turn an uncertain move into a reassuring lie.

### 14.12 C2交接、UUID停点定性与D验收输入冻结

- C2曾收到stopped通知，Lead未把它当完成；用户要求“你别急，我来恢复c2”后目标实际pause到rev2。用户恢复原C2并交付后，Lead保持暂停；收到“继续”时尝试resume被控制面明确拒绝（模型不能恢复paused目标，需用户操作），未另建目标绕过。现用户控制面已恢复并触发round1，get_goal实收rev3/active/armed；可以按既有授权进入D，不是后台完成通知自行恢复。
- [C2报告](<C:/Temp/reforgedplay-C4b-20261116-714dc6b236b5/REPORT.md>)说明生产修改NONE、422断言和full-six external javac exit0。Lead回读报告、[原run04日志](<C:/Temp/reforgedplay-C4b-20261116-714dc6b236b5/run04/java.raw.log>)末尾TOTAL PASS422、[UUID观察receipt](<C:/Temp/reforgedplay-C4b-20261116-714dc6b236b5/observation01/actual-result.receipt.txt>)与核心624–632实际代码；本轮再次sha256sum六源均14.10/14.11指纹，HEAD仍e9e89195a25096ad17c753ba003ea3da3dbe5a3b，status只原六source＋plan，diffcheck无错。
- **UUID停点定性：不是本候选须修复的同一球面UUID冲突。** Lead实际读取[Google Spherical Video V1规范MP4段](<https://github.com/google/spatial-media/blob/master/docs/spherical-video-rfc.md#mp4>)（[raw](<https://raw.githubusercontent.com/google/spatial-media/master/docs/spherical-video-rfc.md>)）：用于避免metadata碰撞的唯一标识必须为ffcc8263-f855-4a93-8814-587a02521fdd，UTF8 XML在此uuid leaf内。外来不同UUID即使payload恰为球面XML，仍不是这个规范标识，不应按内容猜它的类型、覆盖或删除。保持foreign字节并添加正确UUID是当前预期APPLIED；同一正确UUID且XML不同/重复/错父的UNCHANGED已有真实测试。
- 原获批14.4/.5只以正确UUID/XML为receipt、保留无关字节，没有要求跨UUID扫描XML并将foreign内容视同同一协议。**过宽的“不同UUID/XML冲突须UNCHANGED”来自Lead给C2分片的措辞，不是用户新增要求**；C2报告称字面不满足并交Lead是正确做法。现按已批协议标识澄清，不修改生产核心、不覆写observation01、不删改已运行断言、不把其exit0的观察冒充原错误预期通过。D须独立复验foreign保持＋proper UUID注入和same-UUID冲突两相反例，可据规范复核本定性，不能凭422数目掩盖它。
- 422实际闭环包括实际Folders单快照/热更、actual MetadataInjector mono/ODS full/crop/非整FOV XML、真实helper与1.1.7媒体前缀/offset/幂等；actualFFmpegWriter只readonly fakeProcess exit，不造真实进程；整个实际YoutubeUploader.preUpload字节码通过Unsafe避构造/反射执行，仅MP4分支，无auth/转码/网络。Renderer精确literal metadata＋邻接finish/Post＋actualsetFailure等提取边界，不是完整renderVideo/游戏/GUI或真实events。B假receipt不在C2 runtimeclasspath，不能混称其37断言为事务证据。
- 真APPLIED/ALREADY、unsupported布局/源已证未变UNCHANGED、unknown-move/postverify INDETERMINATE、cleanup-warning/no-link保留、source/candidate/stage替换等actualprovider结果贯通render分流和sphereupload两种request flag；失败必finish/Post且不成功UI。所有providerFileChannel关闭；注入非真实OS认证。实际facade C↔R双向成功和root/sentinel/正常清理新执行；C1Windows NOSHARE_DELETE只是明确历史引用，未重跑冒称C2。非sphere不碰metadata设置或网络。
- C2旧7392文件与workspace1348文件零变化，未带权限字段/旧脚本执行或覆盖旧证据。初次driver NUL/GBK读取/错误cwd断言/provider-root错误/javap argfile不支持等失败均保留，修的是外部夹具/调用，不改业务源码/强条件。C2全部585 synthetic路径741579B、max14357B，B54+C1 1406880+C2 741579=2,148,513B；D预算仍≤7MiB、single≤1MiB，加起来9,488,545B＜16MiB，留余量不删旧证据。code/classes/log另记。
- C2表明实际接受/拒绝集：前置/碎片/多轨/音频/未知结构表/未知metadata key可能拒绝，codec条目虽含AVC/HEVC/VP9/AV1/MP4V，当前正例只有AVC；后四非AVC合法正例/解码、近16MiB physical-moov heap、真实FFmpeg产物/播放器/MC/UI/网络、memory-only parser内部constructor/close故障/真实容量ACL/卸载盘/恶意ancestor/CAS/断电持久化均未测，不隐瞒为“所有尾moov单轨都能成功”。D须检查安全性与声明边界，不能合成AVC载荷套另一config就假签nonAVC合法媒体。
- **D只读所有生产source与plan**，独立审查/新执行核心243＋真实集成422及UUID两方向、已有弱处/故障/容量边界/精确清理/ready和finish门；独占新C输出与唯一R私有根、全部写目标预检/old-before-after audit，不直接跑旧fixed-O或覆写旧失败。Source/default fixture/classes来自fresh编译，不依赖B占位/旧receipt。发现真实bug给证据交Lead/C，不“边验边修”或偷偷扩大格式/白名单。
- D最终专用mc_gradle task=build args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"] timeout1200000，不clean/refresh/跳test/任务注入/改构建依赖，NO-SOURCE不是JUnit通过。新最终non-dev-shadow JAR核对当前helper/六类/结果与ready方法/移除旧截断路径及实际isoparser嵌套classes；可snapshot旧artifact对未改第三批Mixins/Full/Bridge来源做回归核对，但别拿旧Jar替代本次构建。验证层次与未测层分列；独立PASS后Lead才完整六source真实diff收尾、停等人commit，当前未完成目标。

C2原话：I would rather leave one clearly described semantic gate unresolved than silently call a different UUID the same promise.

### 14.13 首轮D无报告中断的可核实停点与原阶段重试

- 首D代理9572be0a-44f2-4473-bbf1-7a1a75d64013收到failed-before-finished通知，无closing message或最终REPORT；不推断它已完成独立review/build/产物验证，也不以通知“失败”反推生产编译坏。Lead随后job_list为空，六source SHA仍14.10/.11，status仍仅原六source＋计划、diffcheck通过，未启动并行writer。
- 只读发现其唯一C根[C:/Temp/reforgedplay-D4-20261116-1909-bc591a7e](<C:/Temp/reforgedplay-D4-20261116-1909-bc591a7e/>)；[preflight](<C:/Temp/reforgedplay-D4-20261116-1909-bc591a7e/preflight.json>)的R例外为R:/Temp/reforgedplay-D4-bc591a7e-529a-4642-8992-62946782b173，core和integration的R记录指同一根。Lead未执行其fixed-root driver、未改其文件；读driver只用于判定已跑/未证层和恢复保护边界。
- [core01原java日志](<C:/Temp/reforgedplay-D4-20261116-1909-bc591a7e/core01/java.raw.log>)TOTAL PASS243、[java.exit](<C:/Temp/reforgedplay-D4-20261116-1909-bc591a7e/core01/java.exit.txt>)=0；[integration01原java日志](<C:/Temp/reforgedplay-D4-20261116-1909-bc591a7e/integration01/java.raw.log>)TOTAL PASS422、[java.exit](<C:/Temp/reforgedplay-D4-20261116-1909-bc591a7e/integration01/java.exit.txt>)=0；[full-six01 javac.exit](<C:/Temp/reforgedplay-D4-20261116-1909-bc591a7e/full-six01/javac.exit.txt>)=0。这些是可核实的本轮D部分执行收据，尚需后继核源snapshot/argv/CP/边界，不伪装成最终签名。
- 未找到最终REPORT、完整sealed保护/预算审计或最终构建/新artifact收据；不把未找到直接说“绝未运行过”，后继须核实。恢复D必须先审计首D全部C+R retainedsamples并计入D累计7MiB，旧B/C累计2,148,513B仍分别保留，不拿新根重新清零预算。新D独占新输出和唯一新R根，不在首D原名下补跑/改脚本/伪造其收尾报告，不删旧材料腾budget。
- 继续使用subagent_mc_verify重委派同一D阶段，不跳C/D顺序、不降验证门。默认当前sixsource/plan全只读；先核旧D old-before/workspace-before，外部证据必须无改。Lead本次top/14.13是首D失败后有明确授权的计划收据变化，不能混成D期间源码修改；生产六源应全无变化。后继开始时再freeze现在plan/整个workspace，Lead在其执行期间不写source/plan。
- 原14.12 UUID定性、所有原安全/审查/actual243/422/两方向UUID/真实C↔R/OS共享锁/最终专用build及newJAR gates不变。后继可在预算允许下fresh重跑全套；若引用首D已核hash相符的部分执行，精确列历史/本次新执行，不谎称又跑过。完整build/新artifact门必须有本候选真实收据，没有才补专用mc_gradle，不按猜测填success。真实回归先交Lead/C；工具/框架中断不当业务PASS/FAIL，原失败通知与文件均保留。

### 14.14 Lead协助D恢复的实际完整构建收据（不是独立验收签名）

- 为防再次中断使关键构建无收据，Lead在首D结束、job_list为空且source六hash不变后，亲调用专用mc_gradle(projectDir=C:/Users/zhenz/Desktop/ReForgedPlay-1.21, task=build, args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"], timeout1200000)。没有并行writer、clean/refresh/-x/testskip/Gradle注入或构建依赖修改。
- 实际工具输出：**BUILD SUCCESSFUL in 59s；6 actionable tasks：4 executed、2 up-to-date**。compileJava/jar/shadowJar/remapJar执行，processResources/processIncludeJars UP-TO-DATE；compileTestJava/processTestResources/test/validateAccessWidener NO-SOURCE，remapSourcesJar SKIPPED；NO-SOURCE不是JUnit成功。工具仅可见文本，未虚构独立exitCode/timedOut字段或rawstdout文件。
- 实际Loom1.7.435/Gradle8.8，启动新daemon，三个既有Thread.stop/Object.finalize移除warning重现，部分中文诊断编码乱码原样；旧WorldRenderer$ChunkInfo/getChunk和JOML Matrix4f/Matrix4fStack remap诊断、旧Loom/Gradle deprecation仍输出。构建成功不表示这些旧诊断已修，也不签游戏/实际Mixins全模组加载。
- Lead亲跑sha256sum/wc -c：[新non-dev-shadow JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>)为17,521,960B，SHA256=358d39385591675fcb875cb6ac2ffbb946ca79ba816f0576342158c4d7b42612。构建后status仍原六源码＋plan，diffcheck无错误。
- 此构建是D恢复阶段的Lead协助证据，不能冒充首D已跑或后继D亲跑；后继须独立审查当前源码、核真实daemon若需要、验证这个新artifact/嵌套依赖并执行必要purehelper正负例。无候选变化且artifact可核时不必为凑次数重复build；若发现需要修复，原范围C修完后最终build仍须重新执行。D整体尚未PASS，source/plan从本段记录后冻结等待后继交接。

### 14.15 D恢复阶段PASS及Lead第四批最终源码提交检查点

**结论：本批已批准的源码实现、隔离生产逻辑验证、真实C/R与新最终构建产物门通过；可交用户审查并自行提交六源码＋计划。不是实际FFmpeg/播放器/NeoForge全模组/世界UI/网络上传验收，AI不安装/commit，不继续下一批。**

#### 精确源码范围与真实diff

Lead回读[D恢复阶段完整报告](<C:/Temp/reforgedplay-D4R-20261116-1921-a1c8742f/REPORT.md>)、[证据索引](<C:/Temp/reforgedplay-D4R-20261116-1921-a1c8742f/EVIDENCE-INDEX.md>)、[seal-summary](<C:/Temp/reforgedplay-D4R-20261116-1921-a1c8742f/seal-summary.json>)、新artifact运行日志/UUID正反日志，重读实际helper83–214整个apply及先前ledger/validator关键段，并逐段读五既有源码完整diff。D逐段完整review helper1125行，是独立审查层，不冒称Lead重新逐行通读全部核心或重跑全部用例。

| 源码 | 相对HEADe9e8919的真实+/- | 本批职责 |
| --- | --- | --- |
| [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>) | +22/-0 | 视频tempPath单次快照；原图片/资源ZIP/raw/cache方法未改 |
| [MetadataInjector.java](<src/main/java/com/replaymod/render/metadata/MetadataInjector.java>) | +25/-65 | 移除截断原视频路径；保留void兼容、增加实际结果入口、UUID16/XMLUTF8 |
| [VideoMetadataTask.java](<src/main/java/com/replaymod/render/metadata/VideoMetadataTask.java>) | 新增1125行 | 有界尾moov处理、精确媒体不变证明、候选/stage/原子发布/验证结果/清理 |
| [FFmpegWriter.java](<src/main/java/com/replaymod/render/FFmpegWriter.java>) | +15/-0 | 只读exit0且未abort的就绪门，不改进程退出/关闭策略 |
| [VideoRenderer.java](<src/main/java/com/replaymod/render/rendering/VideoRenderer.java>) | +30/-3 | metadata就绪/实际路径门与真实结果分流，保留finish/Post |
| [YoutubeUploader.java](<src/main/java/com/replaymod/extras/youtube/YoutubeUploader.java>) | +11/-4 | 球面preUpload只接受验证成功结果，不用请求开关冒充receipt |

实现合计+1228/-72；计划另计。普通git diff不含untracked新helper，不能用tracked统计冒称全六source。当前status仅五modified source＋新增helper＋plan；HEAD仍e9e89195a25096ad17c753ba003ea3da3dbe5a3b，无Git写、无额外source/config/dependency/test/wrapper/env/GUI修改。

Lead亲核六source hash均14.10/14.11值，生成[完整六source真实patch](<C:/Temp/reforgedplay-Lead4-final-69b47e12/six-source.patch>)，含五tracked git diff与新helper no-index真实diff，75807B，SHA256=af48a6b2a0dc74e899ea44c326991425bc2226bb6d6b293f68ba17246cb47e5a；[六source指纹](<C:/Temp/reforgedplay-Lead4-final-69b47e12/six-source.sha256>)另存。没有Git index/commit写；no-index正常差异exit1不是失败。计划不含在该sourcepatch中，以本文件/最终plan.patch为准。

#### 实际验证、归属与最终产物

- C1实际243断言/C2实际422断言后，首D又对当前源码fresh core243＋真实integration422＋fullsix外部javac全部exit0；中断通知没有抹掉这些实际原日志。D重试核source snapshots/CP/generated提取/测试未弱化，明确归首D执行，不伪说本次重跑422。
- D重试另执行**新最终JAR优先classpath purehelper243＋UUID正反23**，均javac/java exit0；没有编生产Task到harnessclasses遮盖artifact。实际Task CodeSource来自最终Jar，core新C↔R与Windows共享删除锁/faultprovider/句柄close/材料保留/精确清理都在所列用例中。UUID foreign内容字节保持＋proper追加是APPLIED；proper相同内容ALREADY、冲突/dup/misplaced均UNCHANGED/原hash完整，14.12停点有独立反向证明，不靠修改旧观察凑绿。
- Lead实际专用完整build见14.14：**59s成功，6 tasks：4 executed、2 up-to-date**；test NO-SOURCE不是JUnit通过，旧三removal与remap/Loom/Gradle诊断未顺手修。D只沿用并核这真实Lead构建，不冒称D亲跑，不重建同hash产物凑次数。
- **最终non-dev-shadow [JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>)**：17,521,960B，SHA256=358d39385591675fcb875cb6ac2ffbb946ca79ba816f0576342158c4d7b42612，Lead收尾再次亲跑sha256sum/wc -c与Dseal一致。D实际核六classes/Result/ready/public描述、原截断路径移除、实际1.1.7嵌套478classes与cache一致；第三批未改Full/Bridge/Mixins class与先前artifact一致，Folders原方法/其他非metadata逻辑不被牵动。不把嵌套classes一致当NeoForge全模组加载接受。
- Renderer执行证据仍为精确literal边界＋actualsetFailure等方法，不是whole renderVideo/UI/events；preUpload是整个实际类/MP4分支经Unsafe避构造，真实helper结果驱动，但非MP4转码/网络auth/upload没启动。fakeProcess仅只读状态门、provider故障仅注入层。保留这些边界而不是用漂亮PASS盖掉。

#### 保护、预算、限制及下一步

- Dseal共9,942旧保护文件、2,503工作区文件，changed/removed/added全空，source/plan/newartifact/R sentinel未变；Lead此后只改本计划和写外部diff收据，生产六source无变化。无权限升级字段/Git写/装包/旧证据覆盖或伪恢复，所有driver辅助失败和首D无报告通知如实保留。
- 首D＋D重试727条sample路径1,603,263B，全第四批B/C/D累计**3,751,776B＜16MiB**，最大301638B＜1MiB；硬链接逐路径逻辑大小保守计数，代码/classes/JAR/log另列。未删历史失败材料腾预算，不宣称第三批或所有历史外部文件也在该数内；Lead收尾仅差异/文档，不加样本。
- 此小片**只支持保守验证通过的尾moov、单video轨、nonfragmented subset，moov≤16MiB**；前置moov、碎片、多轨/音频、外部引用、未知结构/config/metadata等可被明确UNCHANGED拒绝，原视频保持。codec实现分支不限AVC但正例仅AVC，nonAVC合法媒体正例/任何真实解码、near16MiB physical-moov heap、memory-only parser内部构造close故障仍未测，不能宣传所有单轨MP4通用。
- tempPath候选和目标卷stage各约N额外空间且可能两份写入；目标盘不支持原子替换则不非原子fallback。独占closed-input/可信parent合同、null-key source相同内容同时间替换不可观察、目录marker不是inode、finalcheck-move/delete窗口/hostileancestor/CAS/断电持久化无保证。无有效cleanup凭据时诊断保留而不阻断已验证metadata成功，不承诺任意RAMDisk都即时删尽。
- **真实FFmpeg产物/进程、播放器/codec解码、NeoForge全模组加载、whole world/GUI/render queue/events、真实网络/auth/upload、nonMP4转码、OS满盘/ACL/卸载卷/不支持atomic驱动仍UNTESTED。** 后续人工另行授权后先用可丢弃短MP4看metadata/真实播放/实际输出就绪与渲染队列，不用用户唯一视频或录制做故障试验。本批未运行这些，不签“可以直接装/实际可播”。
- 本批到此停等用户审查六源码＋计划并自行commit（别漏新增helper）。AI不Git add/commit/push/reset/checkout，不复制到mods/安装、不自动进入native/cache/raw/tmp/保存恢复下一批。收尾核查后按控制面规则将活跃目标标complete以停止自动续作；没有目标历史物理删除工具，不假称已删除物理历史记录。

D重试原话：我宁愿把“由谁运行、验证到哪一层”写清楚，也不让一个漂亮的 PASS 替代缺失的证据。

## 15. 剩余目录/RAMDisk需求统一实施（A5，2026-11-16；B→C1→C2→C3→D）

### 15.1 当前授权、基线与成功标准

本节取代上文“下一批停等批准/停等commit”的**后续实施停点**，不改写历史失败/PASS。用户最新要求“剩下的直接一股脑做了”，已授权剩余相关源码及隔离验证一次完成；内部顺序切片不再逐项询问用户。只有确实必须改变受保护依赖/数据的缺口才回Lead，不以EXR单项阻断其余工作。A只追加本节，未写生产源码、未测试/构建/启动游戏，未写外部脚本或扫描旧证据目录。

- 本轮第一条命令只读核实 `HEAD=fbd2c02756572b7ddae016452fc0535ac9538490`。开始状态仅有用户既存 `.whale-craft/README.md`、`.whale-craft/RULES.md`、`.whale-craft/config.json` 删除；**不恢复、不访问、不顺手处理**。本节之后这些删除不计团队改动。
- 已实际加载本机 minecraft-java-build、minecraft-neoforge-mod 技能，并重读[构建](<build.gradle>)、[属性](<gradle.properties>)、[wrapper](<gradle/wrapper/gradle-wrapper.properties>)、[元数据](<src/main/resources/META-INF/neoforge.mods.toml>)及[libs](<libs/ReplayStudio-master-SNAPSHOT-slim.jar>)。固定 MC1.21.1 / NeoForge21.1.82 / Java21 / Architectury Loom1.7-SNAPSHOT（现有daemon日志1.7.435）/ Gradle8.8 / layered Yarn1.21+build.1＋既有NeoForge patch；**不迁移ModDev、不scaffold、不改依赖/映射/wrapper**。
- 本机 `java` 为Oracle21.0.12.1，PATH裸 `javap` 却是Microsoft25；本轮所有反汇编明确使用 `C:/Program Files/Java/jdk-21.0.12.1/bin/javap.exe`。后续 javac/java/javap 同样钉此已安装JDK，不安装下载或改环境。
- 完成标准：rawPath、recordingWorkPath、tmpPath真实落点；连续save→读取→再编辑/save；旧/新sidecar与重启恢复无丢失；录制写者排空后才提交；跨盘raw/GUI闭合；外部回放缓存不碰撞、不误删；ephemeralCache默认false，最后真实使用者释放才可删；native与回放cache隔离；已有图片/资源包lease/视频事务不回退。build和生产逻辑隔离验证都有新收据；未做游戏/驱动测试必须明列。

### 15.2 A5新核查事实（不是引用旧结论当实测）

| 对象 | 本轮直接核查及约束 |
| --- | --- |
| ReplayStudio实际依赖 | [构建](<build.gradle#L89-L90>)仍为flatDir优先的master-SNAPSHOT-slim；本机Gradle模块缓存未找到ReplayStudio JAR，不捏造一个“缓存版本”。本地JAR SHA256=`e034b0a39cc906ba38c401a13c09bf6fca5512ecb658428380994f71b7d48250`；其**6964个class与当前dev-shadow逐一字节相同，0差异**。ZipReplayFile本地/dev-shadow class均`698bb1fbdaeef8996b3a34c66d72092eab041bb67c2148a830f808c98a0e988c`。现有[最终JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>)整体hash仍`358d39385591675fcb875cb6ac2ffbb946ca79ba816f0576342158c4d7b42612`，该class remap后`d0abc9fa40c4705b3fba8ee03fbfe8e93862f5bc5738f8f623193b3013ce9b9c`；已另对最终class实际javap，不把hash差异当版本变化。未运行Gradle解析任务，下一次build仍须复核实际输入/产物，不能声称动态坐标永不变。 |
| 构造与恢复 | 本地/最终class的四参数签名均 `ZipReplayFile(Studio, File input, File output, File cache) throws IOException`；39–72从**output**派生`.tmp`，内有changed/removed/source；input=null时才尝试读取source定位旧ZIP，随后枚举changed/removed恢复增删。第四参绝不是tmp。导入sidecar须含removed和source语义，不能仅复制ZIP。 |
| save/saveTo/close | 新javap：save会系统createTempFile→saveTo→close→删output→move→重新ZipFile；**它并非永久关闭后不能再用**，但有删除窗口。saveTo先关闭登记的输出流，再FileOutputStream截断传入目标，合并未改ZIP项与changed（跳removed），不清sidecar、不关闭自身ZIP；close清map并递归删除tmp（83–88）。Utils.copy正常EOF才关闭输入，异常不能假设无残留句柄。禁止底层save、禁止底层saveTo直接写正式/raw最终文件、禁止不保恢复材料便finally close。 |
| 最小适配面 | 新javap确认 `AbstractReplayFile(Studio) throws IOException`；高级meta/packet/markers/timeline/assets方法通过基础get/write/remove实现，可继承它并只适配7个基础读写/cache方法＋save/saveTo/close，避免重写全部高层协议。packet输出也必须经过真实基础write追踪，不可只追踪调用者显式write。 |
| 录制/调用缺口 | [PacketListener](<src/main/java/com/replaymod/recording/packet/PacketListener.java#L246-L303>)忽略awaitTermination返回false/中断和packet流close失败，submit里的异常也未汇总，随后仍save；no-output分支提前关GUI、写no_recover、saveTo最终raw。[ResourcePackRecorder](<src/main/java/com/replaymod/recording/packet/ResourcePackRecorder.java#L83-L110>)另有不在saveService内的写入，不能只排空该executor就断言全部写者结束。[GuiEditReplay](<src/main/java/com/replaymod/editor/gui/GuiEditReplay.java#L155-L164>)写markers失败catch后仍继续MarkerProcessor，须局部return。 |
| 跨盘/恢复/cache | [FilesService](<src/main/java/com/replaymod/core/files/ReplayFilesService.java>)仍直接搬录制目录项、只扫正式根同级tmp、按URL反解缓存且递归删除；[Folders尾部](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java#L542-L589>)仍固定raw/recording、跨卷relativize；[GUI](<src/main/java/com/replaymod/recording/gui/GuiSavingReplay.java#L139-L166>)主线程直接move。不能只增加设置而保留这些调用。 |
| LWJGL完整现有打包面 | 已逐一读取现有最终JAR全部12个嵌套JAR目录（主JAR8439项），并检查源码树及processIncludeJars；**均无com/replaymod/render/utils/lwjgl.jar，也无其他lwjgl.jar**。lwjgl-utils只有131项GUI旧工具，不是LWJGL3 core。嵌套TinyEXR/processIncludeJars字节相同，嵌套lwjgl-utils/processIncludeJars亦相同；TinyEXR.class hash=`4889f216d47b7f54c5741e04914a03f703db35b62633c7522441dac797e013b0`。主JAR已含TinyEXR3.3.1的windows/x64 DLL411648B、linux/x64 SO162288B、macos/x64 dylib245880B。 |
| 可用替代classpath | 新读MC1.21.1的Gradle缓存minecraft-info.json：MC自带core/GLFW/OpenGL/OpenAL/STB/allocator等为3.3.3；本机core3.3.3 Java及windows/x64 native JAR均实际存在（DLL479232B）。本机另有3.3.1 Java缓存，**缓存中存在不等于实际游戏classpath，禁止偷偷以它补运行依赖**。可用“child从parent资源定义现有core3.3.3＋已打包TinyEXR3.3.1”，不需要制造缺失胖JAR。实际游戏未启动，以上是构建/MC声明及实际二进制静态清单，不冒充已运行的加载器资源可见性。 |
| native真实API与陷阱 | 新javap core3.3.3的Configuration存在`set(T)`（反射擦除Object）、LIBRARY_NAME/LIBRARY_PATH/SHARED_LIBRARY_EXTRACT_PATH/FORCE。TinyEXR3.3.1静态初始化实际调用`Library.loadSystem(Consumer,Consumer,Class,String,String)`。core3.3.3的SharedLibraryLoader在指定extract path不可写时**仍会回退系统temp/cwd/home**；仅set配置不够。Library.loadSystem对file资源且FORCE=false可直接System.load，core LIBRARY_NAME绝对路径也可直接load。EXRWriter.create确实走loader；A未加载native、未宣称EXR通过。 |

外部来源核对日2026-11-16：[JDK21 Files](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html>)核实createTempDirectory/createLink/mismatch/move、NOFOLLOW与可选能力边界；ATOMIC_MOVE不提供“目标存在绝不覆盖”。[LWJGL3.3.1 Configuration源码](<https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.1/modules/lwjgl/core/src/main/java/org/lwjgl/system/Configuration.java>)及[实际core3.3.3 SharedLibraryLoader源码](<https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.3/modules/lwjgl/core/src/main/java/org/lwjgl/system/SharedLibraryLoader.java>)已搜索后读取，确认初始化时序、回退与全局librarypath副作用；最终判断优先本机上述javap。未增加NeoForge事件API/Maven坐标，无需编造签名。

### 15.3 固定最小设计与项目自有接口（不是外部库API）

**设置/布局。** 在advanced注册 `RAW_PATH="rawPath"`、`RECORDING_WORK_PATH="recordingWorkPath"`、`TMP_PATH="tmpPath"`（String默认空）及 `EPHEMERAL_CACHE="ephemeralCache"`（Boolean默认false）；保留recordingPath/cachePath/tempPath原键。raw/work空值各继承正式根/raw、/recording；tmp空值在逻辑output父目录建立专属workspace，独立指定时在指定tmp根建立workspace；temp空值仍JDK默认选择。相对路径固定游戏runDirectory，拒绝Windows盘相对/根相对，不展开变量；非空错误不静默换盘。每个打开/录制/GUI任务固定快照；不热迁移旧任务。

仅新增以下5个窄类，不引入策略总线/泛型事务框架/新的依赖或测试钩子：

| 拟新增源码 | 固定职责和接口边界 |
| --- | --- |
| `src/main/java/com/replaymod/core/files/ReplayStorageLayout.java` | 纯JDK不可变布局；静态`resolve(Path gameDir, String recordingPath, String rawPath, String recordingWorkPath, String tmpPath, String cachePath, String tempPath, boolean ephemeralCache) throws IOException`，访问器`replayRoot/rawRoot/recordingRoot/tmpRoot/cacheRoot/tempRoot/ephemeralCache/recoveryRoot`（Path，开关除外；tmp/temp未配置可null）。统一规范化/已有祖先realPath和链接边界检查；不改Folders既有图片/资源包lease/视频方法。recoveryRoot固定gameDir下`.replaymod-recovery`，只存小定位记录，不存整份录像。 |
| `src/main/java/com/replaymod/core/files/ReplayWorkspace.java` | 纯JDK单回放工作区、归属/锁、小恢复记录和目标盘发布；`create(ReplayStorageLayout, Path input, Path output, boolean readOnly)`、`recover(ReplayStorageLayout, Path record)`、`scanRecovery(ReplayStorageLayout)`；内部持有base/physicalOutput/sidecar/stage/逻辑目标，不暴露任意递归删除入口。返回类型本类、List<Path>按上述语义固定。保存发布、恢复描述和精确owned清理的辅助方法留本类package-private，由C1细化，不另造第6个框架类。 |
| `src/main/java/com/replaymod/core/files/TransactionalReplayFile.java` | `extends AbstractReplayFile`，构造`(Studio, ReplayWorkspace, ReplayCacheLease) throws IOException`；实现ReplayFile基础面；mutable delegate可有，但**save不调用delegate.save或delegate.close**。持有read/write/cache流与首次真实close完成回调；package-private`onReleased(Runnable)`供Managed封装，回调只在真实关闭全部底层/流后执行一次。不能用逻辑close请求冒充释放收据。 |
| `src/main/java/com/replaymod/core/files/ReplayCacheLease.java` | 纯JDK，`acquire(ReplayStorageLayout, Path logicalReplay) throws IOException`、`path()`、幂等`close()`、`cleanupStale(ReplayStorageLayout)`；进程内引用计数＋进程独占命名空间/锁。读写cache流也计使用者，最后实际release才处理ephemeral，失败只诊断保留。 |
| `src/main/java/com/replaymod/render/utils/LwjglNativeWorkspace.java` | 纯JDK，`open(Path selectedTempRoot, ClassLoader resources) throws IOException`、`directory()`、`nativeResource(String)`、`cleanupStale(Path selectedTempRoot)`。只准备现有classpath资源、进程workspace/锁与file URL映射，不安装/下载，不重写LWJGL类。无新增embedded lwjgl.jar；以后若真实资源存在也只能复制进本任务temp。 |

Folders仅加`snapshotStorageLayout()`和`snapshotNativeTempRoot()`（后者只复用tempPath解析选择，不创建图片/视频任务）；FilesService加`openReadOnly(Path)`供已核实只读列表/markers读取，保持既有open签名。正常open仍可编辑；readOnly只禁止回放数据write/remove/save，允许cache操作。避免预览为获得元数据复制整部录像；可写open使用私有base快照以解除Windows目标ZIP句柄对发布的阻塞，需明确容量/额外一次复制代价，不长期硬链接可写源或把整文件读入heap。既有DelegatingReplayFile不用重写。

**事务/恢复算法。**

1. 取得布局、逻辑input/output的规范身份/服务锁和cache lease；先建立唯一workspace、owner记录、存活锁，再创建delegate。可写input流式复制为workspace私有不可变base并mismatch验证（不动原件）；readonly可直接读原ZIP，但物理output仍是本任务路径。发现旧output.mcpr.tmp时完整导入changed/removed/source，原sidecar在新工作副本验证可恢复前绝不删除；旧source读取有界且按真实构造规则处理input=null，不能漏掉“原ZIP不存在但source指别处”情形。构造任意IOException/RuntimeException/Error均回收已取得的服务锁/lease；若底层句柄释放不明则保守保留诊断，不仅catch IOException。
2. 基础write/writeCache返回受控真实流；高级ReplayOutputStream通过AbstractReplayFile走同一跟踪。save只在**所有输出流成功结束且无写入失败**后进行；不靠底层saveTo替调用方强关活跃packet流。保存锁串行化新写入，已有输入流不被save关闭；同条目尚有读者时拒绝破坏性重写，而非截断其正在读取的sidecar。保存期间不重定向cache身份。
3. 在逻辑目标父卷独占stage中调用**delegate.saveTo(stageFile)**，完整关闭ZIP写句柄，流式校验ZIP所有条目可读/CRC、必要metadata/recording条目（兼容minimal模式），以及删除/新增/未知条目不丢。只读回放格式检查与真实游戏可播验收分开。stage、预期目标身份/旧hash、工作区、阶段写入小记录后才能发布。自有saveTo(File)也走相同事务导出，不改变原save目标，不把其参数直传底层；新导出不覆盖已有项。
4. 新目标用stage所在卷的createLink不覆盖发布（现有能力取舍延续，无硬链接明确保源失败）；编辑替换仅在源快照/目标身份仍一致、调用方独占且目标盘支持原子替换时执行，不先删旧目标，不用ATOMIC_MOVE当CAS/no-clobber。无原子替换安全失败，stage/旧目标/workspace均留存。写完后重新验证正式目标，再标记committed；异常区分未发布/已发布待收尾/结果不确定，不能错误提示整次成功。对恶意外部竞态、断电目录持久化不作保证。
5. **保持delegate私有base＋累计changed/removed活着，不在save后重建/清tmp。** 因此同一公开ReplayFile可继续get/write/save，已有输入流不失效；下一次save仍合并完整状态。不需要每次save复制base或反射修改ReplayStudio私有字段。新增写入重新标dirty；cache写不算录像dirty。
6. close先停止新准入；任何活跃读/写/cache流都须先结束，延迟实际close而不强关仍在用的流/复制仍被写的sidecar，也不在主线程无限等。无活跃流且无未提交修改（含readonly/未改任务），才允许底层close及owned清理；有未提交修改/提交失败，先将base＋完整sidecar树（或已验证完整checkpoint）保存为可独立恢复材料并核对，记录实际恢复位置，**然后**才允许有删除副作用的delegate.close。保护复制失败则不调用破坏性close，报告保留路径。close失败不在finally强删；重复close/异步最后流close不能重复底层破坏动作。旧资源包读流仍活着时保持其base/lease；正常已排空的录制/closed-file搬移必须取得同步实际关闭结果，不走延迟分支。Managed只在真实release收据后解锁/释放cache，不在逻辑请求时onClose。
7. 恢复记录采用有版本、UUID、逻辑input/output、workspace/base/sidecar、stage/目标、phase及拒绝恢复状态的小文件；原子更新小记录或保留前代，不能半写后按文件名猜状态。固定游戏recoveryRoot使修改配置后仍能找到旧自定义tmp/work路径；只扫描该索引＋当前明确根的旧布局，不扫描磁盘。验证记录/owner/目录边界、NOFOLLOW，活跃锁跳过；锁不可证则保留/报告。记录不是任意文件删除授权，不跟随链接递归清理，不把root、raw、录像或未提交sidecar当cache删。
8. initialScan不再盲目move非空目录；旧正式/录制工作根`.mcpr.tmp`与新记录都交恢复入口；恢复GUI持记录映射物理工作区与逻辑目标，先安全save合并旧包，再重写时长，再save，兼容同一实例继续用。拒绝恢复只标记对应材料及时间，不先删除已有`.del`；2日清理只限已验证归属且无活跃锁的拒绝项。`.no_recover`只能在raw完整提交后对应清理，不能凭该标记删唯一恢复材料。

**调用、cache与native。**

- 录制启动固定layout/work路径，初始化中途失败也走保恢复close。PacketListener终止准入→排空saveService并确认返回true（中断保中断标志）→ResourcePackRecorder禁止新写并等正在写者完成→成功关闭packetOutputStream→检查所有记录的首个异步错误→save→真实close后才raw/后处理；超时/close/写失败中止提交并保恢复。不在GUI线程join；重复channelInactive只启动一个收尾任务，不用shutdownNow冒充已刷盘。ResourcePackRecorder局部增加stop/在途写者门，整个index＋payload写阶段受同一生命周期保护，晚到回调不能在close后写。
- no-output也先安全保存工作回放并close，再复用既有moveToRaw得到完整raw，最后关闭GUI/完成记录；取消提前“成功”与最终raw直接saveTo。MarkerProcessor延续moveToRaw保源契约，显式关闭自身packet输入流；只读探测走openReadOnly。GuiEditReplay写失败即return，不继续剪辑。
- ReplayFileOperations保留已验证moveToRaw算法；只添加窄`moveToReplayFolder(Path source, Path replayFolder, String replayName) throws IOException`，GUI命名`name (1)`与raw`.1`规则不变；内部复用现有复制/身份锚点/验证/不覆盖发布，不复制一整套算法。GuiSavingReplay主线程快照名称/目标布局并禁重入，后台串行搬移，主线程展示结果/刷新；失败保留原文件和实际已发布路径，不无条件close并当全成功。空名删除仅限该已关闭输出，发生失败也报告。
- cache采用`cacheRoot/replay-v2/<SHA256规范逻辑绝对身份>/<进程UUID>/...`，owner元数据绑定原路径；已有文件realPath，未存在文件用已解析真实父目录＋名称，处理Windows大小写/别名，不跨卷relativize，不仅文件名。相同逻辑回放在本进程共享lease计数；不同进程独占payload防互删。ephemeral在acquire时快照，最后delegate与所有cache/数据流真释放才删本namespace；关闭失败/所有权不明保留。持久默认7日/回放不存在策略只用于已识别且无活跃锁的新namespace；旧合法cache可保留兼容读取/独立受限清理，解码后的绝对/..路径拒绝；未知顶层、旧lwjgl-natives绝不按回放不存在递归删除。raw保留21日兼容，但跳过活跃任务和目录/链接，不扩展为raw即时清理。
- native首选**不需要临时JAR**：child loader沿用现有parent资源字节fallback定义org.lwjgl及EXR实现类（Frame/FrameConsumer等接口仍parent共享）；删除“没有lwjgl.jar就必失败”的前置门。LwjglNativeWorkspace从parent实际可见资源取MC core3.3.3及已打包TinyEXR3.3.1当前平台native，流式复制校验到tempPath下独占进程任务，保留相同bundled resource相对布局；child getResource优先把已准备native映射为本任务file URL。初始化任何Library/Platform等native依赖前，反射设置**child**Configuration：core LIBRARY_NAME绝对路径、LIBRARY_PATH仅本任务、FORCE=false及EXTRACT_PATH本任务；不改全局java.io.tmpdir/org.lwjgl.librarypath/org.lwjgl.tinyexr，不允许SharedLibraryLoader再去其他目录解包。parent JVM属性有冲突或资源来源/ABI不可验证则针对EXR明确失败，不能混用缓存3.3.1补齐或偷偷用MC已加载库。
- 上述file URL方案必须由C3/D用真实Library调用顺序验证（不是仅检查配置值）；若SharedLibraryLoader仍被调用/回退或NeoForge parent不暴露所需native，先保留可诊断的EXR失败及具体缺少资源/路径，向Lead交单项缺口，其余继续。现有包仅TinyEXR x64，ARM不得谎称支持。进程任务锁/owner及PID＋startInstant防跨进程误删；已加载后本进程不删native、不因URLClassLoader.close就声称已卸载，进程结束后下次启动/下一次native初始化确认原进程结束且锁/归属可信再清理；无法确认保留。无deleteOnExit/崩溃必清承诺。ReplayFilesService启动可调用纯JDK stale cleanup，不初始化native。

### 15.4 精确源码白名单与B/C/D所有权

仓库写入仅下表**19份源码**；本计划仅A/Lead追加，B/C/D只读，不另建PLAN.md。B→C1→C2→C3→D顺序，不重叠writer；D可在相同白名单内修明确小bug，语义/接口扩大先交Lead而非静默加文件。

| 精确文件 | B | C | D |
| --- | --- | --- | --- |
| 拟新增 `src/main/java/com/replaymod/core/files/ReplayStorageLayout.java` | 最小类型/上述接口和合同 | C1布局 | 审查/小修/实际类验证 |
| 拟新增 `src/main/java/com/replaymod/core/files/ReplayWorkspace.java` | 最小类型/接口，未实现在入口抛错 | C1事务/记录/恢复核心 | 同上 |
| 拟新增 `src/main/java/com/replaymod/core/files/TransactionalReplayFile.java` | AbstractReplayFile真实签名骨架，未实现抛错 | C1可续用save/流/close | 同上 |
| 拟新增 `src/main/java/com/replaymod/core/files/ReplayCacheLease.java` | 窄lease接口/合同 | C1最小真实acquire/path/释放；C2完整计数/归属/清理 | 同上 |
| 拟新增 `src/main/java/com/replaymod/render/utils/LwjglNativeWorkspace.java` | 窄资源/进程任务接口 | C3资源准备/遗留清理 | 同上 |
| [Setting.java](<src/main/java/com/replaymod/core/Setting.java>) | 不改 | C1仅4新键 | 同上 |
| [ReplayFoldersService.java](<src/main/java/com/replaymod/core/files/ReplayFoldersService.java>) | 不改 | C1布局/raw/work/tmp快照；C2 cache身份；C3 native temp快照 | 保护既有lease/图片/video方法 |
| [ReplayFilesService.java](<src/main/java/com/replaymod/core/files/ReplayFilesService.java>) | 不改 | C1 open适配；C2恢复/清理/lease；C3只接native stale清理 | 同上 |
| [ManagedReplayFile.java](<src/main/java/com/replaymod/core/files/ManagedReplayFile.java>) | 不改 | C1幂等真实release后回调，失败不假解锁 | 同上 |
| [ReplayFileOperations.java](<src/main/java/com/replaymod/core/files/ReplayFileOperations.java>) | 不改 | C2仅复用原核心新增GUI窄入口 | 不削弱既有raw证明 |
| [RestoreReplayGui.java](<src/main/java/com/replaymod/core/gui/RestoreReplayGui.java>) | 不改 | C2记录/新旧布局恢复/拒绝标记/失败保源 | 同上 |
| [ConnectionEventHandler.java](<src/main/java/com/replaymod/recording/handler/ConnectionEventHandler.java>) | 不改 | C2单次录制布局/初始化失败释放 | 同上 |
| [PacketListener.java](<src/main/java/com/replaymod/recording/packet/PacketListener.java>) | 不改 | C2写队列错误/终止门/流结束/raw无输出分支 | 同上 |
| [ResourcePackRecorder.java](<src/main/java/com/replaymod/recording/packet/ResourcePackRecorder.java>) | 不改 | C2仅录制写者终止/在途门，非回放lease重写 | 同上 |
| [MarkerProcessor.java](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java>) | 不改 | C2只读入口/输入流close/原raw接入核对 | 同上 |
| [GuiSavingReplay.java](<src/main/java/com/replaymod/recording/gui/GuiSavingReplay.java>) | 不改 | C2后台跨盘发布/结果，主线程只UI | 同上 |
| [GuiEditReplay.java](<src/main/java/com/replaymod/editor/gui/GuiEditReplay.java>) | 不改 | C2只读预览、写失败return/close错误分流 | 同上 |
| [GuiReplayViewer.java](<src/main/java/com/replaymod/replay/gui/screen/GuiReplayViewer.java>) | 不改 | C2只改列表读取为openReadOnly | 同上 |
| [Lwjgl3Loader.java](<src/main/java/com/replaymod/render/utils/Lwjgl3Loader.java>) | 不改 | C3现有资源加载/child配置/进程temp，不再cache/native混用 | 同上 |

严格保护：构建/属性/settings/wrapper/libs/映射/metadata、本地化/README/.env、仓库测试及实际配置/游戏/录像；[Image.java](<src/main/java/de/johni0702/minecraft/gui/versions/Image.java>)、[FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>)、[ReplayHandler.java](<src/main/java/com/replaymod/replay/ReplayHandler.java>)、[ReplayResourcePackReleaseBridge.java](<src/main/java/com/replaymod/core/files/ReplayResourcePackReleaseBridge.java>)、[MetadataInjector.java](<src/main/java/com/replaymod/render/metadata/MetadataInjector.java>)、[VideoMetadataTask.java](<src/main/java/com/replaymod/render/metadata/VideoMetadataTask.java>)及第四批其他源均只读，不回退既有释放/视频事务。若发现必须改保护源码才能正确实现，Lead先核实最小必要扩权，不能C自行加白名单或每项问用户。Git仅只读，不add/commit/reset/checkout/push，不安装到mods，不下载/升级任何包。

### 15.5 顺序交接与D验证门（未来执行，不是A已通过）

1. **B**只写5新类窄骨架，真实外部签名以15.2为准，自有未实现分支抛错，不接生产、不铺巨大框架、不构建；报告各文件/接口/占位清单。**C1**完成layout/workspace/真实ReplayStudio适配及Settings/Folders/Files/Managed最小接入；用新CTemp小探针证明连续save、removed/changed和失败先保恢复，再交C2，不等用户批准。为使C1能直接测生产adapter，C1同时实现cache最小真实acquire/path/锁与release，暂不自动删，不能用假lease替身宣称成功；完整共享计数/ephemeral/遗留清理由C2接续，C1不得签完整cache已完成。
2. **C2**完成录制流终止/全部调用、旧新恢复、raw/GUI和cache；可回修C1白名单内接口，不并行writer。**C3**只做native及指定两服务接入；不以单项ABI/资源问题撤销C1/C2。每片交Lead真实diff/新增类接口/源码hash、外部证据位置、实际执行/未测、遗留材料；片间不重复完整build，最终D一次完整build（失败小修后重做必要验证/build）。
3. 新隔离目录仅 `C:/Temp/reforgedplay-B5-*`、`C:/Temp/reforgedplay-C5-*`、`C:/Temp/reforgedplay-D5-*`（唯一不可覆盖）；测试代码/classes/小样本/日志全在新目录，**不得写仓库测试，不扫描/覆盖历史证据**。建议全链合成样本总预算≤64MiB、单样本≤4MiB，累计保守计副本/保留材料/硬链接逻辑大小，不删失败证据“腾预算”；classes/JAR/日志另计。不碰用户录像、不卸载/灌满RAMDisk；C盘内小样本＋既有允许卷的新小隔离目录验证跨卷，若本次只获准C:/Temp则其他卷物理正例标未测，用新C:/Temp的provider故障测试覆盖逻辑，不把它冒充真实跨卷。
4. D先只读HEAD/status/diff及白名单，保存本轮新源hash和既有保护源hash；javac/java明确21。**直接编译/运行生产新增类和真实ReplayStudio/JAR**，不复制事务模型，不把反射/字面边界探针冒充完整GUI。既有Junit依赖允许build原样执行，但NO-SOURCE不是JUnit通过；不下载补CP。外部harness若需MC边界替身，明确替身范围，同时实际生产adapter/IO核心必须真实运行；最终JAR优先classpath再次跑核心，防harness classes遮盖artifact。
5. 核心矩阵：默认/自定义绝对/游戏相对/中文空格路径；不同逻辑同名/目录外/跨卷身份；非法/重叠/链接/junction/未知owner保护；完整ZIP/sidecar新增+删除+source旧指向；同一对象save→get→write→save→close→重新打开；saveTo导出后原对象继续用；输出流未完/失败必须拒绝发布；copy/ZIP校验/发布/正式验证/close/清理故障和目标冲突保留旧数据；恢复重启新进程/索引跨配置变化、拒绝恢复/活跃锁、进程中断各阶段；cache两使用者/双进程/重复close/close失败/流未释放/ephemeral两值；零归属证明不删。使用≤4MiB真实合成有效回放容器，而不拿随机字节冒充录像验收。
6. 调用验收至少实际源码录制终止方法/资源写者门、no-output raw流程、GuiEditReplay错误return、GUI后台线程边界/失败不提前成功、新旧恢复入口；无法headless执行的whole GUI/网络/全MC加载明确UNTESTED。对资源包lease既有三层释放语义做非回归检查，尤其延迟源流close时cache不能提前删、base不能提前消失；图片/video已有方法保持字节/语义不变并跑必要真实生产回归，不复制旧PASS计本轮次数。
7. native验证单独起全新Java21短进程，当前已有core3.3.3＋真实嵌套TinyEXR3.3.1＋本次生产loader/workspace；记录classloader/资源URL/实际native落点、parent配置前后和main cache哨兵。最小1×1 EXR（≤4MiB）经真实TinyEXR及尽可能实际EXRWriter消费/读回，证明ABI，不只Class.forName。覆盖不存在embedded JAR的替代路线、显式不可用temp不回退、带空格/中文、已加载时不删除、后继进程stale清理、双进程隔离及资源缺失安全失败。若只能证明headless native核心，不签whole Minecraft EXR渲染。ARM/Linux/macOS本机未测不宣称通过。
8. **唯一允许wrapper构建调用**：`mc_gradle(projectDir="C:/Users/zhenz/Desktop/ReForgedPlay-1.21", task="build", args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"], timeoutMs=1200000)`。不clean/refresh/-x test，不shell gradlew，不增加compileJava/dependencyInsight等Gradle调用；已有依赖无需安装/补包。记录真实工具输出/耗时/任务状态与最终non-dev-shadow产物hash；复核实际ReplayStudio class与本地/dev-shadow、嵌套native/class清单。动态坐标变动/真实缺包回Lead，不自行改版本。
9. D可白名单内小修，但必须重跑涉及生产路径及最终build；最终按“已通过/安全拒绝或降级/未覆盖/需Lead决定的依赖缺口”四栏回报，不用build或JUnit NO-SOURCE代替测试，不装包/跑游戏验收。只把确实需要改依赖、受保护数据/源的阻塞交Lead，其余按本统一授权继续直到可交付。

**当前未验证/主要风险**：C1事务适配尚未实现，Windows目标句柄/私有base与旧sidecar导入必须用真实依赖证明；硬链接/原子替换/进程锁是可选能力，安全失败不能宣传任意RAMDisk可用；readonly预览不能吃掉旧sidecar；延迟close的流与cache释放必须同一真实收据。native替代方案静态资源足够构成候选，但3.3.3 core＋3.3.1 TinyEXR实际ABI、NeoForge资源可见性及“不回退解包”仍UNVERIFIED；不能谎称EXR已通过。无安装、真实游戏、物理满盘/卸载卷、断电持久化保证。以上均不授权回退已完成图片/资源lease/视频事务，也不是把全部其他目标卡在EXR前的理由。

### 15.6 Lead复核、独立只读意见与用户新增UI/raw开关（当前实施优先）

A已完成并返回19源码方案；用户随后明确补充两项：在mod设置中提供新增设置的UI，raw允许关闭不保留。该直接授权扩展15.3–15.5，旧表中不改本地化/GUI的限制不再适用于本节指定四文件；不要求用户再批准一个小批次。实际录像/运行配置、依赖/构建/测试/.env仍受保护。A未获知此补充，不把Lead新设计算作A已验证。

- `Setting`新增第五个键 `RAW_BACKUP_ENABLED`，序列化 `rawBackupEnabled`，Boolean默认true；布局增加同名布尔快照并和raw/work/tmp/ephemeral一道固定。保留上述8参数resolve作为可兼容重载（默认raw开启），另加明确raw布尔的重载/访问器即可，不为一个开关另造服务。C1负责键和布局；C2负责所有实际raw调用。
- raw关闭时：不创建/写入新的raw目录和备份，不删除历史raw；绝不能简单跳过MarkerProcessor的move却在仍读原件时覆盖它。可采用原文件所在卷的本任务临时保护目录/文件，保留完整已关闭输入，腾出输出路径后剪辑；仅在全部输出验证成功、读者真实释放后清理保护材料，失败保留并记录原件/输出/恢复位置。临时保护不是长期raw备份，不允许把唯一保护偷偷迁往易失temp后提前删稳定源。相同卷不覆盖发布/归属/能力不足保源契约仍成立；只读者打开原文件的Windows句柄不能阻止后续安全发布。若需要小恢复描述，复用ReplayWorkspace窄能力，不加通用框架。
- no-output分支在raw关闭时不另存raw；只在录制写者正确排空、必要流成功关闭且正常终结已确认后执行原‘没有剪辑输出’语义，任一保存/发布/关闭失败仍保留可恢复数据。开关不等于吞掉失败或删除唯一恢复材料；默认开启沿用15.3安全raw路径。
- UI：现有设置页增加‘存储设置’入口，专用页可编辑recordingPath/cachePath/tempPath/rawPath/recordingWorkPath/tmpPath，并提供rawBackupEnabled、ephemeralCache开关；解释空值继承、游戏目录相对路径、RAMDisk恢复风险与任务快照。raw关闭时其路径输入可禁用但保存原值，重新开启可复用；禁止自动迁移/清空旧目录。应用先纯校验再一次提交所有键并持久化；取消/Esc不部分写配置，路径错误在UI显示而非崩溃。不在UI线程做复制或递归目录扫描；六路径+两开关在小窗口应可滚动或分页，不能挤出屏幕。
- 精确新增白名单（在15.4的19源码之外）：`src/main/java/com/replaymod/core/gui/GuiReplaySettings.java`（现有入口）；拟新增 `src/main/java/com/replaymod/core/gui/GuiStorageSettings.java`；`src/main/resources/assets/replaymod/lang/en_US.lang`、`src/main/resources/assets/replaymod/lang/zh_CN.lang`（只增本功能文案，实际语言资源是.lang，不臆造JSON）。总上限21源码＋2语言资源＋本计划；当前仍只有计划改动。
- 顺序变为B→C1→C2（必要时顺序两片）→C3→C4(UI)→D。B仍只建原5类骨架；C4只改上述UI/语言四文件，必要时在已有Setting中修本功能display键，但不扩大到后端配置读写器或实际用户配置。先读项目自身GUI接口/当前用例和语言加载机制再写，不凭记忆拼GUI API。D加入UI应用/取消/无效路径与raw开关全分支验证，明确headless与实际游戏界面未测的差别。
- Lead另留独立验收清单 `C:/Temp/reforgedplay-Lead5-acceptance-69a55ca3.md`，来自实际源阅读和storage-acceptance只读复核，非测试报告。尤其补足mc.send迟到包入口/重复断连；ResourcePackRecorder的index与blob完整生命周期（blob成功后再发布index，同hash并发、关闭/换录像后迟到回调）；GUI多项部分成功后重试不重复发布；cache open与cleanup同一准入互斥。优先最小同一状态锁/真实流跟踪，不为每项另造租约框架。storage-review旧队员恢复因provider chatgpt无adapter失败，无源码修改；独立storage-acceptance已只读回报，失败队员不计验证签名。
- Lead已读取GuiReplaySettings、SettingsRegistry及LangResourcePack并获独立只读复核：六String键及两个专用开关保持displayString=null，不放入主页只支持Boolean/枚举的通用循环；语言资源按实际加载器写单行key=value，勿写裸续行或JSON。raw关闭时布局/校验不得为该rawPath做exists/toRealPath/建目录/容量探测，保存原字符串，实际raw调用必须先检查任务开关。
- 为避免UI逐键set触发回调时观察到半套目录，精确再纳入 `src/main/java/com/replaymod/core/SettingsRegistry.java`，仅增加小型批量set和一致snapshot API：先整体校验注册键/值，在既有settings映射同一锁内一次更新全部键，释放锁后通知callbacks；Folders从同一次map快照解析布局。C1实现该窄API/布局快照，C4以草稿校验后批量应用并只调用一次既有save；不改原单键set、不更换后端/监听器/持久化格式。不能把单次save冒充文件落盘原子保证；既有后端save为void且IO异常只记录的限制如实保留，不扩为无关配置系统重构。最终白名单上限22源码＋2语言资源＋本计划。
- 外部样本预算正式按15.5：全链≤64MiB、单样本≤4MiB，代码/classes/JAR/日志另计；仅新唯一C:/Temp目录，不用R盘或真实游戏资料。wrapper仅指定mc_gradle build；最后显示真实diff与最终JAR并关闭目标，不Git写、不安装、不签未执行的游戏/物理驱动验收。

### 15.7 B真实交付与C1起点（不是实现/测试完成）

B `3483ae2d-2c2e-4529-8db3-7434d47757aa` 已正式完成；仅新增五类骨架共340行，分别layout76、workspace53、adapter111、cache47、native53。所有未实现入口明确抛错，不接生产、无假成功。Lead逐份回读并核对status；只有五新类＋本计划及既存三项whale外部删除，git diff --check通过（只有既有LF/CRLF提示）。B用指定javap21核实真实AbstractReplayFile/Studio/shaded Optional签名；未构建/运行，外部SkeletonTestStub在独占B5目录，不能计任何通过。B回报其角色禁止读取技能正文，故未加载技能；此偏差原样记录，不冒称已加载。

C1顺序接手：完成ReplayStorageLayout/ReplayWorkspace/TransactionalReplayFile及ReplayCacheLease最小真实能力；改Setting、SettingsRegistry（15.6窄批量/snapshot）、Folders、FilesService、Managed的最小接入。其余调用/恢复扫描完整衔接交C2，native/UI后续顺序完成。两开关参数顺序固定ephemeralCache, rawBackupEnabled；raw关闭跳过不可用raw根的探测。C1必须用真实ReplayStudio二进制＋生产核心在新CTemp小探针证明save→read/edit→save和失败close前保护，不能用假lease或复制模型。C1不运行完整wrapper构建、不改计划/其余源码，报告接口、实际结果与未完项后交Lead继续，不等用户再次批准。

### 15.8 C1最终收据与C2顺序切片

C1 `3156c9d7-01d6-4f2b-b29d-4bb50bfc5e54` 曾收到中止通知，随后返回正式完成交接；以最终报告/源码/日志核查而非先到通知断言成功或失败。Lead已读 [C1完整报告](<C:/Temp/reforgedplay-C5-C1-20261116-7f46a2/C1-report-final-3b6e.md>)、实际layout/cache/service/adapter及workspace核心445行，并复核九源SHA256全部OK。第一次sha256sum --check因CRLF尾部被当路径字符而读不到文件，不是源码hash不匹配；仅在新Lead脚本中按CRLF解析原清单后九项通过，未改原证据。

- C1实测归属：javac21真实核心与九源编译；生产adapter/ReplayStudio核心60、边界19、服务设置15断言；真实新进程Runtime.halt后恢复、跨进程锁。MC-bearing边界stub和Windows故障注入选项有明确标签，不签完整游戏/可播放/物理跨卷。未运行Gradle。样本实际157392B、最大2206B，预算保守记1MiB；旧失败轮保留，不把多轮成功累加。
- 实现九源与15.7一致；工作区仅既定白名单及三项whale外部删除。只读根不复制整部base；可写打开有一份private base、失败关闭还需独立保护副本；默认raw开关和bulk settings已真实实现，cache共享/ephemeral/cleanup仍显式待C2，native仍B占位，UI尚未实施，整项目不可此时宣称完成。
- C2改为顺序两片，避免一个上下文同时写完整恢复/cache和所有录制/GUI：**C2a**负责cache共享/使用者回收、受控旧新恢复扫描/openRecovery、恢复GUI同entry读写适配及已确认旧材料生命周期；只写ReplayCacheLease、ReplayWorkspace、TransactionalReplayFile、ManagedReplayFile、ReplayFilesService、ReplayFoldersService、RestoreReplayGui七源中必要局部。**C2b**接录制与ResourcePackRecorder屏障、raw关闭保护、MarkerProcessor、GUI后台发布和只读预览等其余既定白名单。C2a→C2b严格顺序，不并行改源；有必要接口可在原总白名单内由Lead交接确认，不另问用户。
- 当前RestoreReplayGui同一entry先getPacketData再writePacketData会被新adapter正确拒绝；C2a必须在自身受控workspace或恢复任务中流式转换到独立材料，再关闭旧reader后重写，不能去掉adapter保护或整份读入heap。不再盲目搬非空恢复目录、不凭no_recover删除唯一材料。
- 同名cache身份/两个使用者/close失败或流仍活跃不提前删、open/cleanup竞态、双进程隔离、配置变更后固定索引恢复、拒绝恢复的2日安全清理是C2a必须动态证明的核心；未知owner/失去身份的材料保留并报告。持久cache默认false开关下应可合理复用已确认闲置的自己namespace，不以保留永不复用的大量副本冒充缓存。原有active目录和旧native不得误删。
- C2a仅新唯一C:/Temp/reforgedplay-C5-C2a-*，建议样本≤8MiB、单份≤4MiB；不运行wrapper或真游戏、不改计划/测试/配置/依赖，不扫旧证据。输出生产实际验证和接口，再无停顿继续C2b/C3/C4/D。

### 15.9 C2a真实收据与C2b录制/raw/GUI接续

C2a `1cecfa89-af97-4a01-aae0-def690ef5cbc` 已完成并返回 [最终报告](<C:/Temp/reforgedplay-C5-C2a-20261117-58a9df/C2a-report-final.md>)；Lead已读报告、实际Managed/FilesService/cache315行及adapter关键准入点，七源SHA256全部OK，git diff --check通过。除本轮白名单及三项whale外部删除无其他仓库变更。

- 实际生产Java21 javac通过；最终r4 core60、Windows边界19、cache主JVM36（另子过程5）、设置服务15、真实恢复service/GUI方法41及冷进程/跨进程锁、cache reader跨save补充通过。MC/jGui调度/布局为明确stub，方法为实际生产源码，不签真游戏/布局。未Gradle、未物理跨卷。C2a样本427668B、最大2499B，保守占1MiB；C1+C2a共保守2MiB，其余预算仍须累计。
- 已实现真实lease共享/引用/ephemeral/stale、服务openRecovery及layout显式open/openReadOnly、固定索引/两旧根扫描、版本拒绝/两日受限清理、workspace内spool顺序packet恢复；保留无原owner/旧读者证明的legacy。真实ZipReplayFile构造/packet writer可能隐式删cache，已有独占mutation门；外部构造抛错无release证明采取进程期quarantine，不假解锁。默认persistent仅同进程idle namespace复用，跨进程隔离不互删。
- 最终D补一项热更验收：最后persistent用户已真实释放后，将ephemeral从false改true并开启新一代任务，须按新任务快照回收；旧false用户仍在途时则保留优先。C2a现有owner.persistent在idle reopen时可能一直粘着true，不能把上一代保留策略误当所有未来代永久策略。D核实这一具体路径，若确认则在同白名单作最小修复并回归，不宣称此项已通过。
- C2b写范围：ConnectionEventHandler、PacketListener、ResourcePackRecorder、MarkerProcessor、ReplayFileOperations、GuiSavingReplay、GuiEditReplay、GuiReplayViewer八份既有源码；可在ReplayWorkspace/Managed/FilesService三份中增加本功能所需的窄保护/locator/任务快照接入，不重写已验核心。raw=false的保护留原卷，全部输出/读者关闭成功才清，失败定位；正常no-output关闭raw不另存备份，任一排空/关闭失败不进入正常丢弃分支。
- 为避免旧下载回调经‘当前listener’误写新录像，精确再纳入已有 `src/main/java/com/replaymod/recording/mixin/MixinDownloadingPackFinder.java`，仅绑定本次pack请求对象到所属recorder，不改Mixin JSON/其他Mixin。Lead已用指定javap21实查当前named MC JAR：ServerResourcePackManager有私有 `onAdd(UUID, PackEntry)`、`onDownload(Collection<PackEntry>, Downloader.DownloadResult)`，可在现有Mixin中用已存在的Coerce风格按pack对象身份捕获归属再消费，不凭UUID可重用/当前listener推断。写前仍核对实际调用/类签名，若不能证明归属则安全拒绝未知回调并报告，不假接收。总白名单更新为23源码＋2语言资源＋本计划；其他受保护资源不动。
- Packet入口封口必须覆盖mc.send之前已准入的待主线程票，以及saveService所有submit（packet、metadata、addMarker）；最后metadata在这些票完成后由唯一内部终结入口提交，再shutdown/确认真正终止和首错。Worker再seal并排空RP整段blob-close→index提交，不持replayFile锁等recorder，避免反向死锁；成功关闭packetOutputStream后save→确认Managed.isReleased才搬移/后处理/成功UI。重复断连只一次，超时/中断/写失败不假成功。
- C2b先用真实ReplayStudio javap查PacketData/ReplayOutputStream/Packet的retain/release；拒绝mc.send/拒绝executor、normal/minimal/失败各分支精确一次释放，不盲统一finally双release。具体独立只读判据在Lead验收清单。录制开始一次layout显式传至PacketListener、MarkerProcessor和GuiSavingReplay，任务中热更不得改raw策略/工作或最终目标盘。
- C2b只用新唯一C:/Temp/reforgedplay-C5-C2b-*，建议样本≤8MiB、单份≤4MiB；原C1/C2a证据只读，不重跑到旧根。完成实际源码边界验证与旧核心必要回归后接C3/native、C4/UI及D唯一完整mc_gradle build；不要每片重复全量构建，也不以stub边界PASS冒充真实网络/GUI。
- **Lead新动态反例已确认，交D必须修验而非UNTESTED带过**：在新 `C:/Temp/reforgedplay-Lead5-cache-policy-69a55ca3` 用真实C2a final-r4-classes＋Java21运行独立诊断，class来源实际回显指向该seal目录，结果persistent首次close保留=true、idle namespace复用=true、随后新一代ephemeral close删除=false。未改任何生产源或旧C2a证据。新样本4文件共468B（最大owner425B），与C1/C2a预算并计；不是MCPR播放验收。D在原白名单内最小修正‘闲置重开开始新策略代’并回归仍活跃false用户优先；复制诊断到新D根运行，禁止重跑覆盖本反例原log。该问题不影响当前C2b录制实现顺序，但最终不得带此已知反例交付。

### 15.10 C2b真实交接与C3 native范围

C2b `8d235e62-bc92-40d2-b6bf-bf50845bee06` 已完成，仅十份既有源码；Lead已读 [完整报告](<C:/Temp/reforgedplay-C5-C2b-20261117-6d8f3a/C2b-report-final.md>) 并复核十源SHA256全OK、status及git diff --check通过。录制固定layout/准入票/最终metadata/真排空、RP blob→index及原recorder归属、原卷raw-off保护、RFO GUI发布、后台重命名部分成功收据、Edit失败return、Viewer只读已实现；只额外窄修FilesService两处真实jGui prompt类型衔接，原stub类型错误证据保留，未重写恢复核心。

- 实测真实MC/jGui API javac exit0；生产方法headless126、core60、实际Windows边界19通过，normal/minimal packet引用语义用真实ReplayStudio javap及引用计数探针核对。真实Mixin仅方法调用/静态签名检查，未跑NeoForge注入或网络consent；MC DTO/调度/GUI边界stub明确，不能冒充真游戏。
- 已绑定PackEntry object归原DownloadRequest，onAdd不再猜当前listener；未绑定同UUID多请求/延迟consent歧义安全拒绝并警告，可能不录入该包，不宣传网络资源包100%齐全。超时worker继续隔离真实活写者/锁直到其退出或进程结束，不假flush。发布后收尾失败保留真实目标路径且停止自动重发，需按诊断人工核查；正常后项发布前失败的部分重试已测。
- 全轮样本263887B、最大2181B，C2b保守记2MiB；上游C1+C2a保守2MiB及Lead468B另计，当前保守约4MiB/64MiB。cache新一代策略反例仍交D，不在C2b冒称修复。
- C3写范围仅 `src/main/java/com/replaymod/render/utils/LwjglNativeWorkspace.java`、`src/main/java/com/replaymod/render/utils/Lwjgl3Loader.java`，以及已有Folders的native snapshot局部、FilesService纯JDK stale cleanup接入（后两份已有能力可不改）。不改EXRWriter算法/第三批资源包/视频/录制/cache/UI或构建依赖/其他资源。C3独立新C:/Temp/reforgedplay-C5-C3-*，建议样本≤16MiB（native拷贝计入）、单份≤4MiB；用当前MC声明core3.3.3与已打包TinyEXR3.3.1及对应现有native，不以缓存另一Java版本补运行依赖。
- C3按15.3现有资源child-loader路线消除缺失embedded lwjgl.jar门：绝不静默去系统temp/cwd/home二次解包，不全局改属性，不从cache根加载/清理native。资源缺失/ABI不明具体失败，不签未跑的EXR；不以此阻断UI和D。尽量新JVM真实1×1 EXR写/读回并执行实际EXRWriter；若headless需先初始化parent native，所有该初始化仅在隔离harness、用独立已准备副本，和生产child配置前后是否污染分开记录。已加载native本进程不删，只在后继确认原PID/startInstant结束、锁和owner可信时清，未知保留。
- C3完成后顺序C4设置页/两语言，最后D真实完整mc_gradle build＋已知反例修复和必要最终JAR生产回归；不额外重复wrapper、不改计划/仓库测试/实际配置/依赖、不装包/真游戏。
- Lead在C3执行期间追加只读实源复核：已读实际RFO保护/发布、Marker完整保护闭合、Saving GUI worker/部分收据、Packet准入至finish核心及Connection启动。D再核一项明确新录制语义：ConnectionEventHandler当前仍以 `open(layout, outputPath, outputPath)` 打开时间命名的新录制（151–152行），若该work文件已存在，会被当编辑源授权替换。新录制必须选择不冲突名字/sidecar并以新目标输入null打开，最终发布仍由不覆盖原语裁决；不能借旧时间名碰撞覆盖既有完整回放或消费待恢复sidecar。此为本需求‘新录制不覆盖’直接相关，允许D在原Connection/RFO/Files白名单内最小修正和生产用例验证，不借机改其他网络录制行为。当前未动态复现，不把源码风险计成测试FAIL；不得忽略该调用语义。

### 15.11 C3真实结果、EXR明确缺口与C4 UI

C3 `2ccc1f75-0363-4268-835b-cf396291343d` 已完成：仅native workspace、loader、FilesService后台清理3行，共三源。Lead已读 [完整报告](<C:/Temp/reforgedplay-C5-C3-20261117-80d4f9/C3-report-final.md>)、实际loader139行，三源hash全部OK，status/差异白名单及diff --check核对通过；Folders未改。删除embedded-JAR前置和cache/native混用，native来自实际parent资源，child独立配置/file URL/显式禁止SharedLibraryLoader fallback，纯JDK进程owner/锁/stale清理闭合。

- **实际生产EXRWriter不可用，不是‘仅未测’**：真实consume到EXRHeader.mallocStack时NoSuchMethodError；已用javap证实TinyEXR Java3.3.1调用Struct.wrap(Class,long)，MC core3.3.3已无此Java ABI。最后loader在consumer构造前具体拒绝，不崩到导出中/不生成假结果。要恢复exporter需要改变依赖或明确兼容适配范围，目前两者未授权，故此单项保留失败，不能宣称‘全部功能完成’。Lead已向用户明说此限制，未停掉其他目标。
- 真实TinyEXR JNI的1×1写/读回通过，但harness绕开失配generated mallocStack而用真实nSave/nParse/nLoad；这是native ABI诊断，不是生产writer替代或PASS。最终真实Library日志指core绝对本任务路径、Tiny来自准备file URL，无SharedLibraryLoader加载、parent Config全部前后null/JVM Properties完全一致、cache哨兵不变。真实API编译和native源＋实际EXRWriter等编译均exit0。
- 已测中文空格/空值/显式失败无回退/缺资源/伪binding/属性冲突拒绝、双JVM活跃隔离/后继结束清理、foreign/unknown/junction保留。cleanup JVM不加载org.lwjgl，加载native的JVM结束后可清精确task。实际launcher资源可见性、whole游戏/Linux/macOS/ARM仍未测；ARM仅拒绝分支。早轮ABI/平台log/Windows活锁/编码与seal失败证据都保留。
- 全轮样本14,262,950B、最大479232B；C3保守16MiB，加此前4MiB＋Lead468B后保守20MiB＋468B/64MiB。C4建议≤1MiB，余量给最终D，不重跑C3整套复制更多native。
- C4写范围固定四文件：现有GuiReplaySettings入口、拟新增GuiStorageSettings、en_US.lang/zh_CN.lang必要文案；保留六String/two Boolean键displayString=null，专用可滚动存储页/固定应用取消，初始路径和长路径不被默认textfield长度截断（先设足够maxLength再setText）；先草稿全校验再setAll/一次save，取消/Esc零set，raw关闭不探测其离线/非法路径并保留字符串。说明相对游戏目录/空值继承/任务快照、native本进程不随设置迁移、RAMDisk恢复风险，不自动搬/删历史数据。不要改后端、core协议、EXR依赖或其他GUI。
- C4真实API编译＋实际页方法/设置bulk与必要语言键验证、headless边界单列；未跑真游戏不得签截图布局/交互通过。完成后直接D，D必须修验15.9 cache策略真反例和15.10新录制不覆盖语义，再mc_gradle build及最终artifact回归。

### 15.12 C4 UI收据与最终D冻结入口

C4 `655a742f-59a8-4e09-bc27-60a6aeed4891` 已完成四文件：现有主页入口/滚动列表、新专用GuiStorageSettings、两语言各追加22键。Lead已读 [完整报告](<C:/Temp/reforgedplay-C5-C4-20261117-197fb2/C4-report-final.md>)、实际页294行/主页138行及真实AbstractGuiLabel.draw换行实现，四源hash全OK、status/差异白名单/diff --check通过；八个存储键仍隐藏于旧通用循环，未改核心协议或后端。

- 实际API javac exit0，生产UI/Registry/Backend/Layout方法headless323断言（19 cases）通过；真实TextField/滚动容器/composed键处理/事件，MC调度/字体渲染边界stub。初始八值/长中文空格路径、取消/Esc/removed/后台迟到零提交、raw禁用保非法字符串与零探测、整批callback/单次save、错误/回调异常/已有void后端IO只日志边界、两语言22键与几何小窗口范围均明确分层。不是实际字体/截图/鼠标/游戏验收。
- 样本2820B、最大651B，保守记1MiB；全链当前保守21MiB＋Lead468B/64MiB，D建议≤32MiB（含native副本）、单份≤4MiB，留余量。旧各片证据只读，全部新输出到唯一D5目录，不扫描旧上万文件/覆盖失败证据。
- **D先修两个已批准窄问题**：15.9/1312 cache新一代策略已有真实反例；15.10/1324新录制必须input=null且不碰撞/消费旧sidecar。只在现有23Java白名单内最小修，回归mixed活跃cache和新目标不覆盖；不能以‘未测’带过。其他发现的本需求明确bug可同白名单小修，不能重构无关系统。
- **D唯一完整构建**仍是 `mc_gradle(projectDir="C:/Users/zhenz/Desktop/ReForgedPlay-1.21", task="build", args=["-Djava.io.tmpdir=C:\\Temp","--console=plain"], timeoutMs=1200000)`；不clean/refresh/-x test/额外Gradle任务/直接shell wrapper。在窄修之后构建，若失败只修必要项再重跑，不把失败compile或NO-SOURCE说成成功。真实工具输出保留，不能伪造未返回的exitCode等字段。
- D需复验生产核心/录制raw/恢复cache/UI关键回归与最终non-dev-shadow JAR；至少真实最终JAR优先classpath跑纯核心，防旧classes遮盖。核对实际ReplayStudio依赖/新增类与语言/新Mixin target映射，区分静态/外部transform边界与真实NeoForge注入未测。已有Image/Full/Handler/Bridge/metadata/video保护源与构建/属性/wrapper/libs原字节保持；白名单23Java＋两语言＋plan，三项whale删除单列外部不恢复。
- **EXR ABI已知失败必须保留明示**，不能升级依赖、patch生成LWJGL类或EXRWriter算法，更不能拿RawTiny JNI诊断PASS替代生产exporter。D若其他门全过，可交付普通格式/目录功能与明确EXR限制，不签全部格式通用。无真实游戏/物理RAMDisk/跨卷/满盘/卸载/断电和完整UI验收则如实列出。
- D返回唯一REPORT、真实命令/日志/样本账、最终artifact路径/size/SHA256、完整实际diff（含六份新Java，排除whale内容）、最终源hash与已过/已知失败/安全拒绝/未测四栏。Lead复核后再交用户，不Git写/安装，控制面许可时结束目标并停止工作；没有物理删除历史目标工具不假称已删除。

### 15.13 D5最终收据、Lead复核与交付停点

**可交付的是目录/存储改造与普通格式的游戏测试候选，不是‘EXR已修复’或‘真实游戏全验收’。** D `33188c41-3c41-4bd0-9b8e-82ceca5dc8b6` 已完成，完整 [REPORT](<C:/Temp/reforgedplay-D5-20261117-6fa903/REPORT.md>) 与 [证据索引](<C:/Temp/reforgedplay-D5-20261117-6fa903/EVIDENCE-INDEX.md>) 保留原日志/所有失败轮。Lead已读两报告、实际D-only三源补丁、build工具原文本、artifact-cache策略原日志及runner事故记录，复核23Java＋两语言25项源hash全部OK、最终JAR实际hash/size和status/diff --check；没有再改实现或重复build。

- 两必修项真实关闭：idle cache新一代按新ephemeral快照，ACTIVE混合代false用户仍保留优先；新录制选名跳过完整文件/sidecar并以input=null打开，最终仍不覆盖发布。D仅改ReplayCacheLease、FilesService窄选名helper、ConnectionEventHandler两行与import，没有借机改其他网络。源19修复用例及最终JAR12修复/来源断言、原反例最终JAR输出true/true/true通过。
- 唯一授权构建：mc_gradle build，-Djava.io.tmpdir=C:\Temp，--console=plain；工具原文本 **BUILD SUCCESSFUL in 54s，6 actionable:5 executed/1 up-to-date**，compileJava/processResources/jar/shadowJar/remapJar执行。compileTestJava/test为NO-SOURCE，不冒充JUnit通过；既有Thread.stop/finalize、Loom/Gradle及WorldRenderer/JOML remap诊断原样保留，未顺手修。工具只提供text，未虚构额外exitCode/timedOut。
- D当前源实跑：core60、边界19、cache36＋子过程5、设置15、恢复GUI方法41、录制/raw/RP/后台GUI126、UI323/19cases；冷进程恢复、跨进程锁通过。最终non-dev JAR在CP首位且无旧生产classes/本地Studio/MC stub遮盖的core60/边界19/cache36＋子5、修复12/冷恢复/锁通过。以上每层边界明确，不把各轮数字累成一个‘全游戏总PASS’。
- 最终JAR实际loader/workspace/EXRWriter及真实Library/natives来源核验；JNI1×1仍通过、最终factory仍提前明确拒绝EXR Java ABI失配。六新class/两语言各22键存在，实际ReplayStudio public API与本地一致、dev-shadow class字节一致；最终remap字节不同不谎称hash相同。Mixin onAdd→pushNewPack/ServerPackData、onDownload→onDownload/BatchResult与patched Mojang descriptor静态匹配，未transform/未NeoForge实际注入。
- 最终产物：[reforgedplaymod-1.21.1-0.3.1.jar](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>)，**17,619,307B**，SHA256 **047f493007096d23fd1142bf65eb2bd057eba8f0bef009cea88b53c59aca37b4**。HEAD仍fbd2c02756572b7ddae016452fc0535ac9538490，没有Git写或安装。实际修改23Java＋两语言＋本计划，其中六新Java不要漏入用户自己的提交；三项whale删除始终单列用户/外部状态，不恢复不读内容。539项保护文件原字节保持、既有Folders图片/lease/video前缀与HEAD一致。
- D [完整实际差异](<C:/Temp/reforgedplay-D5-20261117-6fa903/complete-actual.patch>) 含六新Java/两语言及D起点plan；D-only三源补丁、25源hash和保护清单均在证据索引。Lead此后仅更新本计划的最终收据/状态，不改任何构建输入；最终交付以新 `C:/Temp/reforgedplay-Lead5-final-69a55ca3/complete-actual-final.patch` 为准，将plan片段更新为当前实际Git diff，原D patch不可覆写。该根FINAL-RECEIPT.json记录Lead新核验的25实现文件/539保护文件/JAR/完整26文件patch指纹，不冒充新增运行测试。

**流程偏差，Lead负责原样披露，不以绿灯掩盖：** D首轮复制UI harness时，Windows反斜杠替换未改源码正斜杠绝对fixture常量，误输出旧 `C:/Temp/reforgedplay-C5-C4-20261117-197fb2/samples-r1` 子树，现存5份合成文件1407B。未有该子树执行前前像，不能证明此前不存在或从未覆盖fixture；没有删除/回滚/伪恢复，旧根不能再签‘完全未动’。D已在新唯一D根修正harness路径并重新323通过，首轮结果不计最终验收；不是生产源或JAR被误改的证据，但违反旧证据只读要求确实成立。事故详情见runner-incidents.json与样本账。B/D角色禁止技能工具的偏差也按实际记录，不冒称加载；实际版本/API核实另有证据。

- 预算：D已知累计含误输出和清过native1,188,490B、最大479232B；不是全过程逐文件分配监控，内部成功临时删除另以D16MiB保守并账，全链保守 **37MiB＋468B/64MiB**。失败/recovery/unknown/保护材料保留，合法已结束native后继清理不是删除失败证据腾预算；Lead终检只写代码/patch/hash/文档，不新建合成录像样本。
- 尚未覆盖：完整NeoForge启动/真实注入、游戏播放/完整网络consent、实际字体/截图/鼠标交互、launcher资源可见性、物理跨卷/RAMDisk/离线卷/满盘/卸载/断电、其他OS/ARM。硬链接/锁/原子替换能力不足安全失败保源，不承诺所有RAMDisk；原卷独占/可信父目录合同仍在。legacy无owner/旧读者证明的材料保守保留；异常constructor quarantine须退出JVM后恢复；歧义RP不猜归属。
- 用户使用：关闭游戏替换唯一旧版mod，不用dev-shadow；回放列表的设置→存储设置，六目录/两开关可改。推荐cache/temp去已挂载RAMDisk，正式/raw/work/tmp先留稳定盘；关闭raw不删除历史备份、失败恢复材料仍保留。先用可丢弃短回放验证新UI/录制/播放/普通导出，不选EXR；EXR恢复需另定依赖/兼容授权，本轮未越权改库。
- 收尾控制面：目标goal-69a55ca3-87df-4520-9f54-b3a2504a4988仍revision3、active/disarmed。Lead在本后台完成通知回合尝试complete，Host明确拒绝“complete and blocked require a direct human turn or the current goal round”。未反复重试、未擅自resume/新建重复goal，亦未假称已删除历史。当前无后台jobs/待执行子代理，代码工作到此停止；下一次直接人类回合可合法关闭该控制面记录。

## 16. 纯客户端进服被要求服务端安装：只读诊断与待批准最小修复

用户明确定位：本mod纯客户端，服务器不应因没装它而拒绝连接。本回合只读检查源、最终JAR及本机真实NeoForge21.1.82二进制，未改实现/依赖/metadata或重新build。工作区仍保留上批23Java＋两语言改动及三项whale外部删除；HEAD仍fbd2c027。当前get_goal返回null，不再向旧goal ID发动作、不新建长期目标。

- 明确缺陷位于 [ReplayModRecording.java:74–78](<src/main/java/com/replaymod/recording/ReplayModRecording.java#L74-L78>)：`event.registrar("1")`后直接commonToClient注册`replaymod:restrict`，未调用optional。当前发给用户的non-dev JAR实际javap同样无optional调用，确认不是只读到源而成品另有行为。
- 真实NeoForge21.1.82 universal.jar指定javap21证明：PayloadRegistrar构造把optional设为false；commonToClient将该值传给NetworkRegistry.register（同时覆盖PLAY/CONFIGURATION）；optional()返回带true的**新registrar**而非原地修改。因此该限制消息接收通道被声明为必选，会要求另一端支持；这是能解释‘服务端需要该mod’的实际错误。不能把没有用户本次握手日志说成已实际复现完整服务器连接。
- metadata已有displayTest=IGNORE_SERVER_VERSION不等于网络通道可选；dependencies下side=BOTH表示依赖生效侧，不是本问题应随手改成CLIENT的开关。不通过移除限制通道/codec或让服务器装客户端mod来掩盖问题。
- **待批准白名单只有两文件**：本计划，以及 `src/main/java/com/replaymod/recording/ReplayModRecording.java` 的registrar一行：`final PayloadRegistrar registrar = event.registrar("1").optional();`。commonToClient、ID、CODEC、handler、协议版本及其它初始化全部保持；不动本轮存储/GUI、EXR、metadata、依赖/构建/wrapper、测试/真实配置、服务器或任何其它源码。不Git写、不安装到游戏目录。
- 批准后由Lead直接完成此单行修复，不再开启大型四阶段链；验证真实21.1.82 default/optional返回值与成品optional→commonToClient顺序，再通过唯一mc_gradle build（固定-Djava.io.tmpdir=C:\Temp --console=plain）并提供新JAR/真实差异/指纹。此回合没有运行新构建或虚构进服通过；最终仍需用户同一服务器实际重连验收，如果还有其它通道则依据实际截图/实例日志继续定位。
- 补充审计发现：本工作区现有 [logs/latest.log](<logs/latest.log#L1-L16>) 明确是D5 headless故障注入日志（C2bProbe、HEADLESS mc.send rejected和D5合成路径），不是用户本次进服日志，不可拿它判断用户服务器报错。它表明旧D探针的Log4j也曾随cwd写入被Git忽略的workspace日志；15.13的539源/构建保护核对不覆盖这些ignored日志，不能扩大成‘旧workspace全部没写’。本次未删除/覆盖它；后续新探针必须在新唯一CTemp cwd并收敛日志到该根/console，避免重演输出越界。

### 16.1 用户批准与实际实施起点

用户通过确认工具选择“执行最小修复 (Recommended)”，批准本节两文件范围。Lead已仅将registrar一行改成链式.optional()，保留返回的新对象；其余网络ID/codec/handler/协议和全部存储/UI实现不动。验证在新唯一C:/Temp/reforgedplay-client-channel-20261118-4a98c1进行，原D/Lead证据根不写，所有非Gradle探针cwd也固定该新根，避免Log4j再落项目日志。此段是实施起点，尚未签构建或真实服务器连接通过。

### 16.2 单行修复构建与成品收据

- 真实NeoForge21.1.82 universal.jar由独立URLClassLoader加载（parent仅JDK platform，不是mock），四项动态断言通过：默认required、optional返回新对象、新对象optional=true、原对象仍required。探针和日志只在本新CTemp根，未调用服务器握手；MethodHandles仅外部测试读取状态，不进入生产。
- 专用mc_gradle按既定两参数执行唯一build：**BUILD SUCCESSFUL in 22s，6 actionable:4 executed/2 up-to-date**。test为NO-SOURCE，非JUnit成功；既有Loom/PacketListener unchecked与WorldRenderer/JOML remap诊断未顺手改。工具原文本在本回合实际调用，不虚构独立exitCode字段。
- 最终JAR实际javap确认registerNetwork中.optional调用先于commonToClient；ID/CODEC/handler不变。源码按原字节反向撤销仅该替换后SHA恰等于D5旧保护hash，证明不是夹带其他源码修改；D5此前25份生产/语言源hash全部一致，另538份保护文件仍一致。git diff --check通过，未Git写或安装。
- 新产物仍为 [reforgedplaymod-1.21.1-0.3.1.jar](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>)，**17,619,331B**，SHA256 **4df462cb5843599418f8781a4e0e599a07229bbdd5ffcadc938aacfeeec3ee3d**。本次重构建按批准覆盖同名build输出；上批D5旧hash是历史收据，不再拿当前链接文件冒充旧产物。目录/GUI功能字节来源保持，EXR已知不兼容仍未修。
- 新证据根的verification.json、registrar-probe.log、artifact-network-javap.log、network-only.patch与REPORT.md保存本轮收据；最后plan-only.patch为本计划当前真实差异。没有真实游戏或用户目标服务器连接测试，不把编译/注册器验证说成已实际进服；交用户关闭客户端、替换唯一旧JAR并重连原服务器，若仍拒绝需实际断开界面/游戏实例日志识别其他问题。当前无长期goal、未新建大型代理任务。

## 17. 用户新报保存录像超时：诊断及待批准的最小修复

本轮用户要求读取放在工作区的错误信息。已读取 [用户报告](<crash-2026-11-17_18.15.24-client.txt#L5-L10>)，直接错误是 `Recording writer/main admission did not drain within 10 seconds; retaining recovery`，出自PacketListener.finishRecording第372行；不是服务端缺mod/握手报告。第16节optional修复不能解决本次保存错误，不用那份注册器验证替代本次诊断。当前仅追加计划，未改生产源、旧JAR、真实录像、配置或仓库测试，未构建。

### 17.1 已核查事实与证据边界

- [PacketListener:166–199](<src/main/java/com/replaymod/recording/packet/PacketListener.java#L166-L199>)先增加pendingMain再用mc.send投递，只有实际执行或投递抛异常才归还票据。mc.send正常返回、随后队列被清空时，claimed/rejected均不会执行，pendingMain不会归零。
- [PacketListener:241–263](<src/main/java/com/replaymod/recording/packet/PacketListener.java#L241-L263>)最终metadata也经mc.send；若该任务静默丢失，saveService.shutdown永远不会发生。两处都能造成当前worker等待超时，即使没有任何磁盘写入错误。
- [SchedulerImpl:53–58](<src/main/java/com/replaymod/core/versions/scheduler/SchedulerImpl.java#L53-L58>)已有明确说明：MC断开连接会清空自身任务队列，ReplayMod自有executor正是为避免丢失录制收尾任务而存在。core.runLater通过该独立executor排队，InputReplayTimer每帧调用core.runTasks，用户报告第35–43行也有这条独立调度调用栈。
- 不仅依赖注释：用JDK21 javap读取当前工程实际named MC1.21.1/NeoForge21.1.82缓存JAR，[真实字节码](<C:/Temp/reforgedplay-save-diagnosis-20261118-7c8d21/mc-scheduler-javap.log#L5626-L5636>)确认disconnect(Screen,boolean)调用cancelTasks；[ThreadExecutor](<C:/Temp/reforgedplay-save-diagnosis-20261118-7c8d21/mc-scheduler-javap.log#L8522-L8561>)确认send只入队，cancelTasks直接Queue.clear，不调用被丢弃Runnable。新取证均在唯一CTemp根；源码JAR只有NeoForge源，不含MC源，首次按Yarn路径提取失败日志如实保留，随后改查实际二进制成功，不伪称读到了MC源码。
- 因此已确认一个本轮录制改造引入、可以直接导致该症状的队列生命周期缺陷。报告本身没有pendingMain/finalQueued/worker栈等快照，不能仅凭此确定用户那一次卡在普通票据、最终metadata还是慢磁盘；实际运行NeoForge21.1.264，而工程仍钉21.1.82，不擅自升级。未把动态光源的附加CrashReport段归罪为根因，也没有据此判断录像已损坏或恢复成功。

### 17.2 候选实施白名单、验证与停点（等待用户确认）

- 仅修改本计划及 [PacketListener.java](<src/main/java/com/replaymod/recording/packet/PacketListener.java>)。两处非主线程调度由mc.send改为现有core.runLater：dispatchMain及queueFinalMetadata；仍在主线程时直接执行。同步更新该文件与mc.send直接相关的局部注释，不重构Scheduler、准入/packet引用计数、资源包、事务存储、UI、网络注册或依赖。第16节optional暂保留，不能因无关保存报告盲目撤销它。
- 不延长10秒阈值、不删超时报错、不强行shutdownNow、不假定任务已执行后手减pendingMain；保留真实排空、最终metadata先写后shutdown、首次错误和失败保源机制。新调度必须继续满足每份已接受票据/packet恰好归还一次，最终metadata只提交一次，主线程不等待worker。
- 批准后在新唯一CTemp根做隔离生产方法回归，显式模拟“投递成功后MC原队列被清空”两种窗口（普通已接受票据、最终metadata），不是只覆盖投递抛异常；同时验证主线程内联、重复inactive、调度拒绝/写入失败保源等必要原行为。边界mock/真实MC二进制验证分开标注；不修改仓库测试、不使用游戏真实录像当样本，不向旧证据根或项目logs输出探针日志。
- 最后只通过mc_gradle执行build，参数固定 `-Djava.io.tmpdir=C:\Temp --console=plain`，不clean/refresh/-x test/额外任务/直接shell wrapper。交真实局部diff、构建原输出、新JAR指纹与未测项；游戏内短录制→退出服务器→正常保存仍由用户验收，编译不等于通过真实游戏。EXR已知不兼容不在此修复范围，不Git写/装包/安装mod到游戏目录。

### 17.3 用户批准及实施起点

用户通过确认工具选择“执行最小修复 (Recommended)”。Lead按两文件白名单实施：PacketListener两个非主线程分支改为core.runLater，更新两处旧mc.send注释并明确断线队列风险；没有改准入票据、阈值、writer/保源/close算法或第16节网络注册。新隔离根仍是本回合唯一CTemp目录，修改前对src/构建/wrapper/libs及用户报告/原workspace日志共566个文件记录hash，后续逐项核对。仅复制旧D5的明确flow测试源码和边界stub到新根，未运行或写入旧证据；新副本将原先混成一个队列的MC/ReplayMod调度stub分为独立队列，专门补静默清空窗口。此段不是测试/构建已通过收据。

### 17.4 完成收据及真实边界

- 局部 [实际diff](<C:/Temp/reforgedplay-save-diagnosis-20261118-7c8d21/recording-only.patch>) 相对本次修改前：只有两个非主线程调用由mc.send改core.runLater，外加三处相关注释变更；该外部快照用于明确本轮差异，不把对HEAD的累计存储改造diff冒充本轮小修。
- 旧生产代码的两个动态反例均成立：普通packet/marker/metadata票据在MC队列清空后永久滞留，以及pendingMain=0但final metadata被清空导致executor永不shutdown。新生产代码在相同显式双队列边界下分别17/16项通过（各含1项fixture初始检查），metadata/marker从实际发布的MCPR读回，packet引用仅消费一次，真实writer终止和close收据成立；不把两个数字合成全游戏统计。
- 原flow安全回归新副本完整126断言通过、进程exit0：[原始成功日志](<C:/Temp/reforgedplay-save-diagnosis-20261118-7c8d21/after-flow-regression-r5.log>)。真实10秒超时/中断/写失败/close失败仍保源，正常/minimal/重复inactive/资源包/raw-off/部分发布等保持。MC调度/GUI为显式headless边界；生产PacketListener与存储算法、ReplayStudio真实，实际MC取消语义另外由精确JAR字节码核验，未做完整NeoForge注入/用户游戏运行。
- **构建工具实际输出：BUILD SUCCESSFUL in 24s，6 actionable:4 executed/2 up-to-date。** 唯一mc_gradle build使用固定两参数；test仍NO-SOURCE，不是JUnit成功。既有Loom、unchecked与WorldRenderer/JOML remap诊断保留，没有升级依赖或顺手修复。
- 新non-dev [JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>) **17,619,316B**，SHA256 **08abad21bd91b09427fa0b32a25f75735fd9916c39854e76b6d990fde24a7058**。实际成品javap确认dispatchMain/queueFinalMetadata调用core.runLater且无send，第16节optional调用仍在；ZIP校验通过。PacketListener源SHA256 **5f5737959ce77098f974b2c5471bd3604c9e8bb7769d224bddd8aa409636b02a**；其余565保护项hash未变（包括用户报告及旧workspace日志），本回合不Git写、不安装、不碰真实录像或配置。当前同名JAR覆盖上轮产物，历史第16节hash不再代表当前文件。
- 本轮测试启动问题如实保留：首次外部javac自动运行Mixin处理器缺映射，之后仅探针使用-proc:none；javac的-J参数先误放argfile，修正到命令；Java CP超过Windows长度后改Java argfile。合并回归前景命令120秒被终止，r4完整flow停在52项不计通过，后来独立r5新样本根完整126项通过；未覆盖旧失败/中断日志或向旧D5/C2b证据根输出。最早MC源提取路径不存在，已改以实际二进制为证，不伪称MC源码。详见 [报告](<C:/Temp/reforgedplay-save-diagnosis-20261118-7c8d21/REPORT.md>) 和外部probe收据。
- 新根当前合成样本（含中断/失败及game索引）281文件共80,039B、最大2208B；不是全过程分配监控，不以删除失败材料清预算。原D5边界stub曾将core.runLater映射同一mc.send且只测throw拒绝，漏掉静默清空真实窗口，是上轮验证缺口，不能拿旧126通过证明本次没有bug。
- 尚需用户以新JAR实际短录制→退出服务器→保存验收；用户那次错误具体栈缺口和NeoForge21.1.264运行差异仍未作真游戏验证。不要删除这次失败录像的tmp/恢复材料，本次没有自动修复它们。EXR原不兼容仍不在范围。本轮源改/构建到此结束，无后台job或子代理待收。

## 18. 未改选项保持默认；服务器录像加载报网络协议错误（只读核查与待确认方案）

用户新要求“所有选项在没更改的时候保持默认”，并报告加载服务器录像后出现网络协议错误。当前只核查源码和已有日志、追加本计划，未改任何生产源或实际游戏配置、未重新构建。不得因为一句通用错误再次猜测是必选网络通道或要求服务端安装本客户端mod。

### 18.1 默认值及候选最小修改

- [Setting.java:5–12](<src/main/java/com/replaymod/core/Setting.java#L5-L12>)当前默认：正式录像`./replay_recordings/`、cache`./.replay_cache/`、temp/raw/work/tmp字符串为空；ephemeralCache=false、rawBackupEnabled=true。[布局:30–36](<src/main/java/com/replaymod/core/files/ReplayStorageLayout.java#L30-L36>)将空raw/work分别继承正式目录的raw/recording，空temp交现有默认分支，空tmp使用既有任务工作区选择；没有默认启用RAMDisk或关raw。内部owned workspace/恢复索引是既有安全设计，不应把“默认目录不变”扩大成“物理临时目录树与上游一字不差”的未经验证承诺。
- [存储UI:181–210、225–253](<src/main/java/com/replaymod/core/gui/GuiStorageSettings.java#L181-L253>)目前即使没改也校验全部八值、setAll整批并save；[setAll:61–79](<src/main/java/com/replaymod/core/SettingsRegistry.java#L61-L79>)亦对相同值发回调。尚无证据表明打开UI会把默认值改成非默认；已确认的是无变化Apply仍有校验/通知/写盘副作用，不能混称默认值本身错误。
- 待用户确认按本次新增存储选项理解：未自定义继续原有默认/空值继承，不把解析的绝对路径写回设置；已有自定义配置不重置、不迁移。页面未改或改回初始值后Apply不提交、不save、不进行路径IO；有改动只提交真正改过的项，完整布局校验用当前未改值合并本次改动，避免页面旧草稿覆盖外部已更新的未改项。取消/Esc及raw禁用不解析raw等原合同保持。若需要对所有旧录制/渲染设置统一改变保存语义，须先明确，不能借此全面重构设置系统。
- 候选生产白名单优先仅 [GuiStorageSettings.java](<src/main/java/com/replaymod/core/gui/GuiStorageSettings.java>) 与本计划；确有必要的提示文案才申请两语言追加。暂不改SettingsRegistry/Backend、默认常量、布局/事务核心、播放/录制协议、依赖或真实配置。确认后才实施并用新唯一CTemp根隔离验证未改/改回默认/单项改动/外部更新/取消及原raw-off边界，再按固定mc_gradle build命令构建，不把方案当已实现。

### 18.2 播放协议错误：当前缺少直接证据

- 当前工作区根没有新错误截图/游戏日志/MCPR；以前那份保存超时crash文件也已不在，本轮不恢复或借旧内容代替新报错。工作区 [logs/latest.log:1–8](<logs/latest.log#L1-L8>)仍明确是旧D5合成探针日志（HEADLESS mc.send rejected、C2bProbe及CTemp样本），不是用户回放播放日志，不能用它诊断本次异常。
- [ReplayHandler:362–395](<src/main/java/com/replaymod/replay/ReplayHandler.java#L362-L395>)播放通过本地ClientConnection/EmbeddedChannel驱动；“网络协议错误”界面本身不能证明真实服务器网络/服务器需要本mod。[FullReplaySender:590–605、608–684](<src/main/java/com/replaymod/replay/FullReplaySender.java#L590-L684>)涉及LOGIN/CONFIGURATION/PLAY状态及custom payload过滤，错误可能落在解码、注册表/模组payload或录制内容等不同层面，目前均未定位，不选一种当根因。
- 请用户在实际游戏实例复现打开录像失败后，把该实例的latest.log另取名如replay-playback-latest.log放工作区，同时给完整错误截图；说明录像是新修复版录的还是旧录像、录制与播放Minecraft/模组环境是否相同。优先日志中的最早异常和Caused by，不只看最后“network protocol error”；必要时才请求最小失败MCPR样本，仅读取副本、不改唯一原件。
- 协议修复范围尚未确定、未获生产修改授权。拿到直接证据后提出精确白名单和验证方案；不全丢custom payload、不跳过注册表/握手检查、不吞异常伪装能播，不擅自升级NeoForge/ReplayStudio，不删旧录像或恢复材料。默认值小修与播放兼容诊断分别验收。

### 18.3 用户批准设置修改；新日志到达

用户确认“按这个方案修改”，并说明latest在工作区、录像是新的。现根目录 [latest.log](<latest.log>) 已出现，原字节GB18030而非UTF8，已在唯一新根`C:/Temp/reforgedplay-defaults-playback-20261118-b2741c`无损转换UTF8副本（替代字符0，原SHA256=e3bb8a418f3c8c4004c32afaec92bb3efeab40ece560b06c21bfde80fd226428），未改用户原件或旧workspace日志。该根保存566项修改前hash和仅为本轮diff准备的UI源前像；只复制明确旧UI探针/stub文本，路径全部改指新根，不运行旧证据。Lead仅实施已批GuiStorageSettings源改；其他源码仍未授权。只读子代理2f5a1dc8-87e0-4f5e-a08e-767ff9573fd4独立分析协议异常，不写plan/源码或执行构建。

- 新日志949打开18_52_30.mcpr，989–995最早回放异常为`An objective with the name 'commons' already exists!`（ClientboundSetObjectivePacket→handleAddObjective→Scoreboard.addObjective）；1022–1024进入onPacketError/storeDisconnectionReport，1040为网络协议错误。原始日志编码按副本保留原行号。列表FileLockedException来自别的后台预览读取，暂分开分析，不把它等同于协议异常根因；重复计分项的来源仍在查。
- 设置实施细化仍仅页面：保存打开时八项初值，Apply筛出相对初值确实改过且当前尚未是目标值的项；空变更直接返回父页，零路径IO/通知/保存。有变化以当前其他存储值合并校验，回主线程提交前核对校验基线仍一致，异步期间外部修改则不提交并提示重试，防已校验组合过期；最终setAll只带真实变化项。保持原有raw禁用不探测、取消迟到零写、完整批次及错误语义。
- 两语言旧文案出现“八项已生效/All eight values”等，改为部分提交后不再准确；若要修这些文案，须额外确认只改相关现有提示，不借机更改语言其他内容。当前不越权写两语言。

### 18.4 设置功能已验证构建；当前可交付范围

- 本轮生产范围仅GuiStorageSettings.java（未改默认常量、Registry/Backend、语言或播放源码），[真实本轮差异](<C:/Temp/reforgedplay-defaults-playback-20261118-b2741c/defaults-only.patch>)。原源动态反例确认无变化Apply仍提交八项/save；修改后新增默认行为59断言/10cases、原UI安全回归323断言/19cases均exit0。MC/字体/按钮边界明确为stub，UI/Registry/Backend/layout/textfield方法真实，不是全游戏验收。探针只在新根运行，不改仓库测试或旧证据。
- 新增验证覆盖默认/自定义原值不重置、编辑后还原零写、raw默认开/cache即时清理默认关、空值继承不写绝对路径、真正单项提交/回调、外部未改字段不被覆盖、校验过程中外部改变拒绝过期提交及重试。原取消/Esc/迟到/raw-off/类型/布局/回调及save错误回归保持。
- 首轮外部探针runtime CP漏原项目证书资源，Utils静态初始化报Missing input stream；r1日志保留。r2补入现有src/main/resources并同旧D5方法编译实际TextField源码后完成验证，未为此改生产代码/依赖或假称首轮PASS。
- 工具真实完整构建：**BUILD SUCCESSFUL in 45s；6 actionable tasks:4 executed/2 up-to-date**，固定mc_gradle build及-Djava.io.tmpdir=C:\Temp --console=plain。test仍NO-SOURCE；既有Loom/unchecked/WorldRenderer/JOML诊断未顺手修。最终JAR ZIP完整性及实际javap核对新初值/no-op逻辑和先前core.runLater/optional修复仍在。
- 当前 [设置修复JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>) **17,619,892B**，SHA256 **5fbc5d4cbd2cc3b7a8fdc076b3183623a51f7a0f8f07bd72ef0532787de7372a**；UI源SHA256 **7cb45c379e36055ddaf81fc5e43f193901d6e1e37e6cd7fb08f2ec6a273563fa**。565项目保护文件hash不变（含用户根latest与旧workspace日志），本计划单独更新，Git仅status/diff。更早同路径JAR收据是历史，不冒称当前文件仍有旧hash。完整 [设置报告](<C:/Temp/reforgedplay-defaults-playback-20261118-b2741c/REPORT-UI.md>) 与receipt-ui-final.json保留边界。
- 文案追加确认被中断，没有可靠批准结果；因此两语言未改，旧计数措辞仍是已知提示瑕疵，不假称修好。设置功能可交付测试，真实游戏UI尚未验收；**本JAR未修回放计分项异常**。EXR原不兼容仍未改。

## 19. 回放协议错误：已确认重复计分项；候选最小兼容（待用户批准）

### 19.1 已查实的直接原因与生成源边界

- 只读子代理最终报告已收，Lead已读取原始 [逐包序列](<C:/Temp/reforgedplay-defaults-playback-20261118-b2741c/diagnosis-agent/verified_packet_sequence.json#L175-L223>)。两份失败原始MCPR由标准库ZipFile只读分析，不调用ReplayFilesService/ZipReplayFile/游戏，不改用户录像或配置。
- 18_52_30的第50/370帧（0基），偏移143400/265151；18_54_50的49/363，偏移143202/260436：各有两份完全相同的SET_OBJECTIVE(0x5e)、mode0、名字commons，均时间0；其间没有该objective REMOVE或重配置/新GameJoin。这是文件自身的重复创建，不必假设播放sender同帧双投递。用户实际MC/NeoForge21.1.264字节码的mode0直接addObjective，重名抛异常；日志随后进入onPacketError和disconnect.packetError，直接解释网络协议错误。
- 裁剪生成路径有可解释风险：MarkerProcessor前导cutFilter输出统一写t0；部署SquashFilter没有Objective专门处理，unhandled保留它们，JoinGame只清部分world状态并覆盖JoinGame包，因此可形成失去原边界却保留重复ADD的序列。但本次生成根因未有裁剪前raw对照，不把该机制假设签为已确认录制错误；不擅改ReplayStudio依赖或用户旧录像。认证/皮肤401/DNS、列表FileLocked和错误报告R盘异常不冒充该计分项触发原因。

### 19.2 候选修复、白名单与验证门

- 先提供播放已有录像的局部兼容，不重写文件：新增一个回放专用ClientPlayNetworkHandler计分项处理Mixin，并仅在当前本地回放连接、主线程、objective CREATE且同名已存在时替换旧同名objective后继续原版创建。真实联机、非CREATE、首次创建、不同名字及其他异常保持原行为；不硬编码commons、不全清scoreboard、不跳过所有custom payload或异常。移除旧同名会同时重置其旧分数/显示槽，必须对后续分数/显示包恢复及回退重播做验证，不能只以不崩溃当成功。
- 候选新增 `src/main/java/com/replaymod/replay/mixin/Mixin_ReplaceDuplicateReplayObjective.java`，仅必要注册 `src/main/resources/mixins.replay.replaymod.json`，加本计划。该JSON是模组资源Mixin清单，明确申请单独许可，不把禁止改真实配置偷换为可随意改所有配置。可选同时把两语言已指出的计数提示改为“更改项已更新”，仅这些现有提示；两语言也需明确批准。
- 此段只是候选，尚未触碰播放源码/清单。批准后先核对本工程named API与实际264对应签名、Mixin实际主线程边界和回放连接归属；若最小hook无法安全区分当前回放，停止扩大修改并报具体问题。完成时逐层区分实际Mixin变换、生产方法隔离测试和真实游戏未测，实际编译/构建必须成功。
- 验证首创/重复创建/UPDATE/REMOVE/不同名字、分数与显示槽、重播回退/连接切换及真实联机不受影响；两份真实失败文件仅用只读数据或隔离副本，不写原件、不自动清锁。后续若修生成源，需单独确认raw对照和MarkerProcessor最小范围，不纳入本候选偷偷做。

### 19.3 用户追加disconnect报告

已实际读取新 [disconnect报告:4–12](<disconnect-2026-11-18_16.22.14-client.txt#L4-L12>)：时间2026-11-18 16:22:14，异常同为commons objective已存在，Scoreboard.addObjective→ClientPacketListener.handleAddObjective→ClientboundSetObjectivePacket.handle。[29–38行](<disconnect-2026-11-18_16.22.14-client.txt#L29-L38>)明确入站minecraft:set_objective、不可跳过、onPacketError/storeDisconnectionReport；线程Render thread，协议play/CLIENTBOUND。它与前一份11月17日日志是同类直接错误，不谎称时间不同的两报告是同一次事件。报告的动态光源附加段和java.io.tmpdir ERR不改变最早重名异常的直接因果链。无需再索取同一disconnect信息，原件仅读未改；19.2兼容候选仍待批准。

### 19.4 用户已批准“继续修复并验证”；A阶段精确实施计划（2026-11-18）

#### 19.4.1 授权、时代和当前保护基线

- 用户已通过ask_user_question明确批准“继续修复并验证”。此批准覆盖下表的最小兼容及计数文案，不再等待每个小步骤批准；19.1–19.3的“待批准”是历史状态，本节接续为已批准。**严格A完成→B完成→C完成→D完成，后续阶段不得并行。A本轮只追加本节，不修改生产文件，不编译、不测试、不构建。** Windows大小写等价，继续使用本 [plan.md](<plan.md>)，绝不另建PLAN.md。
- A先读 [构建](<build.gradle#L1-L11>)、[属性](<gradle.properties#L6-L20>)、[wrapper](<gradle/wrapper/gradle-wrapper.properties>)、[元数据](<src/main/resources/META-INF/neoforge.mods.toml>)、[现有vendored库](<libs/ReplayStudio-master-SNAPSHOT-slim.jar>)及相关源树：固定 **MC1.21.1 / NeoForge21.1.82 / Java21 / Gradle8.8 / Architectury Loom1.7-SNAPSHOT（既有实测解析1.7.435）/ Yarn1.21+build.1＋NeoForge patch1.21+build.4**。这是既有现代Java21 Loom工程，不是待scaffold项目；不升级，不套新模板，不迁移ModDev，不改映射/依赖/构建/wrapper/元数据。
- 已实际加载 [minecraft-java-build技能](<C:/Users/zhenz/.dsh/profiles/desktop/node_modules/minecraft-dev/assets/skills/minecraft-java-build/SKILL.md>) 与 [minecraft-neoforge-mod技能](<C:/Users/zhenz/.dsh/profiles/desktop/node_modules/minecraft-dev/assets/skills/minecraft-neoforge-mod/SKILL.md>)，并先查其 [API参考](<C:/Users/zhenz/.dsh/profiles/desktop/node_modules/minecraft-dev/assets/skills/minecraft-neoforge-mod/references/api/mod-bus-events.md>)。参考覆盖事件/注册而无本次MC scoreboard/连接/Mixin API；缺项按用户要求用精确本地JAR及 **C:/Program Files/Java/jdk-21.0.12.1/bin/javap.exe** 补核，不拿新模板的21.1.249/ModDev API代替本工程。未新增任何依赖坐标。
- A只读实测 [UI源](<src/main/java/com/replaymod/core/gui/GuiStorageSettings.java>) SHA256仍为 **7cb45c379e36055ddaf81fc5e43f193901d6e1e37e6cd7fb08f2ec6a273563fa**；[当前non-dev JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>) 仍 **17,619,892B / SHA256 5fbc5d4cbd2cc3b7a8fdc076b3183623a51f7a0f8f07bd72ef0532787de7372a**。59＋323设置隔离断言及45秒build是18.4历史收据，不是本次objective已通过；当前JAR尚无此兼容。既有全部源改保持，不借累计Git diff撤销或重写；Git仅status/diff/log（含diff --check）。已观察到的.whale-craft三删除是用户外部状态，不恢复、不删除、不修改。

#### 19.4.2 精确文件白名单与顺序所有权

| 文件 | B：框架 | C：内容 | D：审查/验证 |
|---|---|---|---|
| 新增 `src/main/java/com/replaymod/replay/mixin/Mixin_ReplaceDuplicateReplayObjective.java` | 只建一个ClientPlayNetworkHandler Mixin，核实后的shadow/注入框架；空主体明确为未实现，不能当修好 | 补当前回放连接身份门、CREATE重名局部移除；必要自有窄helper只能在此Mixin内 | 只准本文件的小编译/映射修正及同范围缺陷修正；不得新建其他生产类 |
| [mixins.replay.replaymod.json](<src/main/resources/mixins.replay.replaymod.json>) | 仅client数组加一次新Mixin类名 | 通常不再改；不能扩展到其他Mixin/插件/全局compatibilityLevel | 检查实际打包注册一次、目标及映射正确 |
| [en_US.lang](<src/main/resources/assets/replaymod/lang/en_US.lang#L367-L370>) | 不改 | 仅下文三类key中的计数词；实际英文仅callbackFailed含All eight values | 核对仅指定词组变化、占位符及未确认落盘语义不变 |
| [zh_CN.lang](<src/main/resources/assets/replaymod/lang/zh_CN.lang#L367-L370>) | 不改 | 仅applied/callbackFailed/saveFailed三处“八项”→“更改项” | 同上 |
| [plan.md](<plan.md>) | 追加B交接，未完不签成功 | 追加C交接、实际语义/限制 | 追加原始验证结果、构建/产物指纹/未测项 |

白名单总计一个新Java＋一个既有Mixin清单＋两个既有语言文件＋本计划。表外所有生产文件（特别是 [ReplayHandler](<src/main/java/com/replaymod/replay/ReplayHandler.java>)、[FullReplaySender](<src/main/java/com/replaymod/replay/FullReplaySender.java>)、[QuickReplaySender](<src/main/java/com/replaymod/replay/QuickReplaySender.java>)、[MarkerProcessor](<src/main/java/com/replaymod/editor/gui/MarkerProcessor.java>)、[设置UI](<src/main/java/com/replaymod/core/gui/GuiStorageSettings.java>)）、其他资源/仓库测试/真实配置/.env/依赖/build脚本/wrapper/libs均只读。D的唯一授权build可正常生成/覆盖build输出，不表示可手工改构建文件或安装JAR到游戏。需要新accessor/额外生产类/其他Mixin/改sender或生成源时先停报，不静默扩大范围。

文案精确约束：仅 `replaymod.gui.storage.applied`、`replaymod.gui.storage.callbackFailed`、`replaymod.gui.storage.saveFailed` 这三类现有key。中文三处只替换前述词；英文callbackFailed只将“All eight values”替换为“Changed values”。英文applied/saveFailed现无八项计数，保持原样；不为了凑六行修改其他词。所有%s、回调失败/保存调用错误、仅请求save而非磁盘写入收据语义原封不动。两语言hint.tasks虽仍提八项，但不在本次授权三类内，**不顺手改**，记录为残留文案限制而非声称全页文案已统一。

#### 19.4.3 已核验API、主线程注入和连接强归属

核验日期均为2026-11-18。A用指定JDK21执行只读javap，cwd=C:/Temp，输出仅工具返回，没有运行旧脚本、构建或测试，也未创建外部取证文件。

1. 编译API的精确来源是 [实际named MC1.21.1/NeoForge21.1.82 JAR](<C:/Users/zhenz/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/neoforge-21.1.82-minecraft-merged/1.21.1-loom.mappings.1_21_1.layered+hash.426444933-v2-neoforge-21.1.82/neoforge-21.1.82-minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.426444933-v2-neoforge-21.1.82.jar>)，不是intermediary或只凭Yarn网页。`ClientPlayNetworkHandler.onScoreboardObjectiveUpdate(ScoreboardObjectiveUpdateS2CPacket):void`：偏移6首先调用 `NetworkThreadUtils.forceMainThread(Packet, PacketListener, ThreadExecutor):void`，偏移15取mode，mode0分支49调用Scoreboard.addObjective；mode1/2分别remove/update。packet公开 `getName():String`、`getMode():int`；`ADD_MODE=0 / REMOVE_MODE=1 / UPDATE_MODE=2` 已用javap -constants核验。Scoreboard公开 `getNullableObjective(String):ScoreboardObjective`、`removeObjective(ScoreboardObjective):void`。handler持有 **private final Scoreboard scoreboard**；新Mixin可在自己内部精确 `@Shadow @Final` 此字段，操作与原版同一board，不转而用可能已切换的全局world scoreboard。
2. B首选唯一注入点：目标完整descriptor为 `onScoreboardObjectiveUpdate(Lnet/minecraft/network/packet/s2c/play/ScoreboardObjectiveUpdateS2CPacket;)V`；在 `Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/util/thread/ThreadExecutor;)V` 的 **INVOKE之后（At.Shift.AFTER）**，非cancellable、不捕获局部变量，`require=1 / expect=1 / allow=1`，保留正常remap，不用require=0掩盖漏注入。forceMainThread实际字节码：非主线程先executeSync再抛OffThreadException，只有主线程正常返回；所以回调不会在Netty线程第一次调用时提前移除。C仍加显式mc.isOnThread检查，不在HEAD先改board，不吞OffThreadException，不再投一份packet/手动执行原版创建。
3. **强归属必须是多重对象身份门，不是global replayHandler!=null：** 取得本注入handler自身（不是用当前handler替代注入对象），要求mc.getNetworkHandler()与此对象 `==`；它自身getConnection()非空且getPacketListener()与此对象 `==`；从该connection经既有 [NetworkManagerAccessor.getChannel](<src/main/java/com/replaymod/recording/mixin/NetworkManagerAccessor.java#L8-L12>) 读取channel，要求是仍open/active的EmbeddedChannel；该channel.pipeline().get("packet_handler")与该connection `==`，且pipeline().get(ReplayHandler.PACKET_HANDLER_NAME)与**当前ReplayModReplay.instance.getReplayHandler().getReplaySender()同一个实例**。ReplayModReplay.instance/当前replay为空、accessor缺失、channel缺失/已关、sender/handler/连接不匹配时，一律不做兼容修改，继续原版语义，不catch所有错误。可用instanceof安全拒绝无accessor的外来对象，不另造accessor或反射读取私有ReplayHandler字段。
4. 上述归属链有本工程源依据：[setup:352–465](<src/main/java/com/replaymod/replay/ReplayHandler.java#L352-L465>)每次创建新ClientConnection和EmbeddedChannel，在公开常量PACKET_HANDLER_NAME安装该ReplayHandler自己的full/quick sender，随后安装packet_handler=该connection；[getReplaySender](<src/main/java/com/replaymod/replay/ReplayHandler.java#L475-L480>)返回当前模式sender，[切模式:540–568](<src/main/java/com/replaymod/replay/ReplayHandler.java#L540-L568>)用同一常量替换pipeline为同一个sender实例。[重启:242–268](<src/main/java/com/replaymod/replay/ReplayHandler.java#L242-L268>)先关旧channel、断开再setup；[结束](<src/main/java/com/replaymod/replay/ReplayHandler.java#L270-L316>)保留新旧会话身份边界。故真实联机即使全局replay指针还活着，也没有该sender实例；不同回放sender不同；同回放回退重建时旧handler不再是mc当前handler且旧channel关闭。**不能仅检查EmbeddedChannel类型、pipeline名字存在、sender类型或CameraEntity**；这些都不能证明当前会话归属。所有身份读取/移除都在主线程同一回调完成，不缓存全局handler/channel供下次使用。
5. accessor已在 [现有recording清单:14](<src/main/resources/mixins.recording.replaymod.json#L14>)注册，且 [元数据](<src/main/resources/META-INF/neoforge.mods.toml#L50-L60>)已注册两清单，均不需修改。A javap确认named及 [实际运行实例srg基础JAR](<D:/Jerry's/MCJE/.minecraft/libraries/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar>)均有private Channel channel、handler private final scoreboard及公开getConnection/getPacketListener；运行基础JAR用Mojang名Connection/ClientPacketListener。编译named JAR另有NeoForge channel()，**本方案不用它**，避免把补丁新增方法的版本可用性当成已签字的运行证明；复用既有accessor不增加生产文件。
6. Netty签名另从 [实际4.1.97.Final缓存JAR](<C:/Users/zhenz/.gradle/caches/modules-2/files-2.1/io.netty/netty-transport/4.1.97.Final/f37380d23c9bb079bc702910833b2fd532c9abd0/netty-transport-4.1.97.Final.jar>) javap确认 `ChannelPipeline.get(String):ChannelHandler`、Channel.isOpen/isActive/pipeline。网页 [Netty官方API](<https://netty.io/4.1/api/io/netty/channel/ChannelPipeline.html>)已搜索并读取，确认get无匹配返回null；网页版本是较新4.1文档，精确可编译性以本地97为准。Mixin的INVOKE/AFTER和require/allow语义由已读取 [At官方源](<https://github.com/SpongePowered/Mixin/blob/master/src/main/java/org/spongepowered/asm/mixin/injection/At.java>)、[Inject官方源](<https://github.com/SpongePowered/Mixin/blob/master/src/main/java/org/spongepowered/asm/mixin/injection/Inject.java>)核对，参数存在性另以 [本地Sponge0.8.7 JAR](<C:/Users/zhenz/.gradle/caches/modules-2/files-2.1/net.fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/2af2f021d8e02a0220dc27a7a72b4666d66d44ca/sponge-mixin-0.15.2+mixin.0.8.7.jar>) javap确认。网页通用语义不冒充已运行Mixin变换。

**C逻辑契约：** 身份门、主线程、packet.getMode()==ADD_MODE、当前同名objective确实存在四者同时成立，才对该旧对象调用一次removeObjective；随后正常返回回调，让原版根据**新包的displayName/renderType/numberFormat**创建新objective。第一次CREATE、不同名字、UPDATE、REMOVE、其他mode、非当前回放连接不得多一次移除/创建；不硬编码commons、不全清board、不取消包、不跳过所有异常、不把重名CREATE改成UPDATE。所有自有helper以@Unique窄命名留在新Mixin内；不放可变静态会话状态、不新建生产类。

#### 19.4.4 分数/显示槽语义、证据缺口和不支持范围

- 已读 [实际264基础Scoreboard字节码:316–381](<C:/Temp/reforgedplay-defaults-playback-20261118-b2741c/diagnosis-agent/Scoreboard.javap.txt#L316-L381>)：removeObjective删除名字索引和criterion索引，遍历所有display slots清空**仅指向旧对象**的槽，遍历玩家scores移除旧objective对应分数，最后通知removed。它不是无损“覆盖标题”；旧分数/槽不会按同名自动挂到新对象上。其他objective/team/其他对象显示槽不得受影响。
- 新CREATE重建本包属性，后续UPDATE只改属性，**不能恢复旧分数或显示槽**。只有后续相应SET_SCORE和SET_DISPLAY_OBJECTIVE等包才能恢复相应内容。不得为避免空显示盲目复制旧scores/slots到新对象，那可能把被裁剪边界之前的旧世界状态带入新段，且已超出本批准替换策略。
- 已读 [逐包证据](<C:/Temp/reforgedplay-defaults-playback-20261118-b2741c/diagnosis-agent/verified_packet_sequence.json#L175-L233>)显示两份处理后文件第二CREATE后仍有UPDATE，但该证据主要列SET_OBJECTIVE及状态边界，**没有完整列出score/display后继包**，不足以签“显示完整”。D必须在新证据根另做只读/隔离副本的scoreboard序列审计，分别记录第二CREATE前后的每个显示槽、score holder/value/format以及后续恢复时刻；不是重跑旧诊断脚本，也不能把仅UPDATE当成恢复分数证据。
- 验收拆分：本次可承诺的目标是**当前回放重名CREATE不再直接抛该异常，且严格局部替换/后续包语义成立**；不保证已丢失包/边界的录像自动还原完整原服务器计分板。若目标样本第二CREATE后缺少恢复包或出现缺行/空sidebar，报告具体缺哪类状态、对应索引/时间，标记“显示保真未成立/不支持自动恢复”，不得以不崩就PASS内容。若用户要求恢复缺失状态或修生成源，需另行授权最小范围。
- 生成源仍未证实：19.1裁剪/SquashFilter是机制风险，不是本次已定位并修好的生成原因；不改原MCPR、MarkerProcessor、ReplayStudio依赖或Studio实现。EXR既有不兼容、锁文件/认证/DNS/动态光源/java.io.tmpdir ERR等均不顺手修。

#### 19.4.5 B/C/D交接、隔离测试与唯一构建门

**安全取证根与基线：** A未写任何外部证据。首次需要输出时，Lead或后续阶段以create-new语义新建唯一 `C:/Temp/reforgedplay-objective-A-20261118-<随机唯一后缀>`，将实际绝对路径追加本节；本轮探针脚本、stub、classes、合成fixture、变换输出/日志和收据全在该新根，所有非Gradle探针cwd也只能是此根或其子目录。不写项目logs，不触碰旧证据根。执行前记录实际java/java.home/javac/javap21；对src/资源/构建/wrapper/libs及原报告/日志做只读hash保护，对本轮白名单保存前像，收尾用**本轮前后差异**而非对HEAD的累计diff验白名单。UI源必须仍等于19.4.1保护hash。

旧测试只允许复制明确需要的源码文本/stub到新根；复制后先搜出所有硬编码绝对fixture/输出/LOG路径并改成新根，加入resolve/normalize后的startsWith根限制和每次写前断言，确认无旧根/游戏目录/项目logs写路径再运行。不能只换cwd而遗留D5绝对fixture常量；不运行旧脚本、不写原证据、不覆写失败日志。真实MCPR只作标准库ZipFile只读输入或先复制到新根；禁止ReplayFilesService/ZipReplayFile等可能产生sidecar/锁/保存的打开路径，原件和原证据hash应前后不变，不清锁/删恢复材料。

**B交接给C：** 只建本节唯一Mixin骨架＋一次client注册；首选主线程AFTER注入及scoreboard shadow、复用既有channel accessor契约。交出精确descriptor/注册位置、空主体未实现声明和相对本轮基线diff；不改语言、不build、不签功能完成。若框架无法安全绑定连接或必须动其他生产文件，立即停报。C开始前回读本节及B实际文件，不并行抢写。

**C交接给D：** 实现19.4.3身份门及单objective替换，四处计数词变更，追加实现/限制/验证待办。C只写批准文件、不build、不修改测试仓库；D开始前回读C源/语言/实际差异。D允许在同白名单修小编译或映射错误并在收据说明，重大策略变更回报Lead。

**D外部隔离测试矩阵（执行实际生产回调/helper，不抄一份手写算法代测）：**

1. 当前正确FullReplaySender和QuickReplaySender两条pipeline：首次CREATE零额外remove；重名CREATE恰好一次remove、原版恰好一次add，新对象身份与新包displayName/renderType/numberFormat一致；第三次同名CREATE亦可；不同名字及其他objective/team均保持。模式UPDATE/REMOVE/非法mode及无旧objective的行为与未注入原版一致，其他异常继续暴露。
2. 真实联机对照：global replay指针空/非空都测；socket或伪EmbeddedChannel但无相同sender、只有相同名字/类型的不同sender、packet_handler不是该connection、connection.packetListener不是该handler、mc当前handler不是注入对象、channel空/关闭/inactive，各自必须零兼容移除，重名仍保留原版抛错。不能只测“没开回放”这一条。
3. 会话与线程：回放A→B，A的迟到包；同回放回退重建后旧连接迟到包；full↔quick；结束后真实联机而全局指针暂存；缺world/未入play。非主线程第一次调用在ensure抛出后尚未移除，只在主线程重入时一次移除一次创建，无双投递；纯直接调用helper的off-thread stub不能代替此调度/注入位置证明。
4. 状态语义：旧同名objective有多玩家scores、多个display slots，另一个objective有独立分数/槽/team；替换后旧同名状态清空而其他状态不变。发后继UPDATE/SET_SCORE/SET_DISPLAY包恢复新对象并核对具体分数、显示槽和格式；故意不发恢复包时应保持缺失，明确记录此不支持边界。回退重播同序列结果一致。尽可能用真实Scoreboard/packet二进制测试；若只能stub，逐项标注边界，不拿stub的清理规则冒充MC行为证据。
5. 两份样本依据19.4.4作新的只读score/display后继审计，关联真实重复索引；原逐包证据只读引用，不重跑旧脚本。语言仅四词组差异，原key集合/%s/磁盘未确认段完全不变；设置功能源码不改，旧59＋323通过只作保护基线，不假称本轮重新执行。

**Mixin证据必须分层：** 生产方法/自有helper隔离执行（明确MC/ReplayHandler/线程stub边界）≠ASM数descriptor匹配≠真实Sponge Mixin变换≠NeoForge全模组客户端运行。D应使用现有缓存Sponge0.8.7/ASM/离线映射工具，在新根对实际named目标做真实变换，并对最终remapped non-dev Mixin＋实际264 Mojang/srg目标复核/尽可能真实变换；同时包含既有NetworkManagerAccessor以验证channel身份链。检查require/expect/allow、callback位于ensure成功之后且在add之前、shadow映射、调用原版仍存在；能执行变换后目标则另记动态结果。不得通过手工ASM注入复制回调来伪称Sponge变换命中。standalone host若需屏蔽非本次plugin或stub外部类，必须列明其差异；不能签成NeoForge launch service/全部安装模组验收。若映射/classloader/bootstrap不具备，记录准确BLOCKED/UNVERIFIED，不用编译成功替代此层；新增安装依赖/改生产配置以解锁测试不在范围。

**唯一Gradle调用仅D执行一次：** `mc_gradle(projectDir="C:/Users/zhenz/Desktop/ReForgedPlay-1.21", task="build", args=["-Djava.io.tmpdir=C:\\Temp", "--console=plain"], timeoutMs=1200000)`。20分钟超时留足Loom处理，A/B/C不调用；不clean、不refresh、不-x test、不tasks/help/runClient、不shell wrapper。先用外部静态核查/必要的隔离探针消除小错误，再执行此唯一build；若失败如实记录，不擅自第二次构建。外部javac仅编译新根的隔离探针，不替代项目构建，也不改仓库测试。发现继承Gradle JVM不符合指定Java21则停报，不安装JDK、不改构建/系统环境绕过。

D必须交真实构建工具原文与任务状态（NO-SOURCE不是JUnit通过）、本轮白名单diff和保护hash、最终non-dev JAR大小/SHA256/ZIP完整性、实际JAR内新Mixin与注册一次/两语言词组/映射检查、各层PASS/FAIL/BLOCKED/未测清单。构建会覆盖同名build输出，19.4.1旧JAR收据仅保留为历史，不用当前文件链接冒充旧字节。不能改真实配置、安装包、游戏mod目录或启动用户实例来补验证。真实游戏打开两份失败录像、前后拖动/切模式/重新进入、核对sidebar行/数值/格式，再退出回放连真实服务器，由用户在替换JAR后验收；在此之前明确“真游戏/全模组兼容及显示保真未验收”。

#### 19.4.6 风险及停点

- 首要风险：消除重名异常不等于恢复完整计分板；score/display后继缺失时本策略有明确不可逆清理代价，必须分开报告播放不崩和显示保真。
- 真实NeoForge21.1.264与编译21.1.82、其他Mixin/Connectivity实际变换链仍有差异；A核对基础字节码与源归属链并非已完成运行时变换。若AFTER hook被改写/匹配数量不一、accessor/shadow不成立，停报，不降require为0。
- 不能退化为global非空、channel类型或pipeline名字检查；sender对象身份＋注入handler当前性＋连接listener＋活跃channel缺一不兼容，尤其防同sender重启旧channel及指针残留真实联机。
- JDK路径、外部fixture硬编码/日志cwd及旧证据写入是环境风险；只用指定JDK21及唯一新CTemp根，原证据只读。vendored ReplayStudio库已有，不缺jar也不替换；没有任何安装/迁移理由。
- A结束时仅本节已追加；生产代码、注册和语言均尚未实施，测试/build/真实Mixin变换均未执行。下一步只能串行交B，不提前交C/D执行。

## 20. 用户新报读取后退出录像列表隐藏：只读诊断及候选释放修复（待批准扩围）

### 20.1 事实与分层证据

- 用户明确：物理.mcpr仍在，退出重进仍不显示，重启电脑（用户称cache/temp等随重启清除）后才恢复。不能由此独断是纯内存锁或纯磁盘缓存，因为重启同时改变进程/句柄/缓存等状态；未要求用户删缓存/删锁/丢恢复材料。
- 只读独立审计e20b1759-fa23-4fee-82aa-ba86d7ef2070已确认日志两份原件仍在，18_52_30=554397B、18_54_50=523575B，标准库ZipFile中央目录各5项且metaData.json/recording.tmcpr存在，观察期间size/mtime不变。新证据 [physical-files.json](<C:/Temp/reforgedplay-disappear-readonly-20261118-e47b6a91/physical-files.json>)。这不是完整ZIP CRC/回放可播验证，也不把观察到文件存在扩大为所有历史录像都绝无删除风险。
- 原日志1042–1048/1277–1283及其后列表加载报ReplayFilesService内部lockedPaths的FileLockedException；[列表:377–402](<src/main/java/com/replaymod/replay/gui/screen/GuiReplayViewer.java#L377-L402>)读取失败只日志、不创建条目。因此有明确的“文件还在，但列表因锁跳过”路径，协议objective异常与物理删除不是同一结论。
- Lead直接读源核对：[FullReplaySender:1080、1369](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1072-L1081>)取得ReplayInputStream；[terminateReplay:382–421](<src/main/java/com/replaymod/replay/FullReplaySender.java#L382-L421>)只停生产/shutdown/中断/关channel/释放资源包，无packet stream close；[async退出:1207–1210](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1207-L1210>)无finally；[同步EOF:1407–1411](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1407-L1411>)直接将replayIn=null未close。仅在某些restart分支close并不覆盖结束/EOF。
- 精确vendored ReplayStudio javap经只读审计确认AbstractReplayFile.getPacketData走虚拟get(recording.tmcpr)，ReplayInputStream.close才实际关底层。当前adapter [read:80–95](<src/main/java/com/replaymod/core/files/TransactionalReplayFile.java#L80-L95>)跟踪真实stream使用数，[finishClose:298–313](<src/main/java/com/replaymod/core/files/TransactionalReplayFile.java#L298-L313>)活stream存在时不释放；Managed只在真实release时解[服务锁:70–73](<src/main/java/com/replaymod/core/files/ReplayFilesService.java#L70-L73>)。此安全门不应删掉；应修播放器自己漏关的读者。
- Lead新根 [实际流锁探针](<C:/Temp/reforgedplay-leak-proof-20261118-c1e932/LeakProof.java>) 只用合成MCPR，重新编译当前生产核心/实际ReplayStudio（MC/GUI边界stub）：真实ReplayInputStream存活→Managed.close延迟→列表openReadOnly实际FileLocked→原字节不变；实际stream.close→真实release→同文件无需删cache或重启即可metadata预览、原字节仍不变。编译/运行均exit0，7项计数含1项fixture初始检查，[原日志](<C:/Temp/reforgedplay-leak-proof-20261118-c1e932/proof.log>)。这是实际适配器/Service所有权链验证，不冒称已动态跑过FullReplaySender或复现重启磁盘缓存的全部因素。
- 另有[ReplayHandler:289–290](<src/main/java/com/replaymod/replay/ReplayHandler.java#L289-L290>)save抛异常就跳过close，finally仅终止sender；[GuiHandler:229–238](<src/main/java/com/replaymod/replay/handler/GuiHandler.java#L229-L238>)可能随后清全局handler指针，让泄漏不再可达。startReplay初始化/兼容警告另有所有权疑点，未认定为本次必需修改；不得全面重构或以可写播放全部改只读来破坏标记/时间线编辑。

### 20.2 候选新增白名单及硬约束（当前未实施）

- 申请在19节已批四生产/资源文件之外，仅再增加现有 [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>) 与 [ReplayHandler.java](<src/main/java/com/replaymod/replay/ReplayHandler.java>) 的退出/EOF读流所有权及save失败close局部修复。本计划记录许可；不改Service/adapter/cache真实释放门、不强移除lockedPaths、不改Viewer去忽略错误、不删原文件/sidecar/恢复记录/缓存、不改真实设置或依赖。
- FullSender在终止和同步EOF确保它持有的packet stream由实际读者停止后恰好一次真实close，再清引用；异步终止/模式切换/同步任务/预读需明确区分，不能在仍读取时由主线程强关。主线程不join/await worker、不等sender长monitor，保留既有session/generation与资源包lease协议；必要后台收尾只在本类内，不另造生产框架。真实close失败不伪造release，保留诊断及重试机会。
- Handler保存失败也应请求保源的真正close，并保留主异常/附加close异常，而非异常路径跳过文件收尾；依旧只在所有真实读者释放后由现有adapter交回服务锁，不能用逻辑close冒充收据。新回放/新联机的重入身份隔离保持，不把普通播放改只读而破坏回放内编辑。
- 批准后由A补精确竞态/关闭算法及测试门，合并到同一A→B→C→D链、同一最终构建；目前B尚未开始，无两个生产writer并行。验证实际FullSender终止/同步EOF/重复terminate/模式切换/有阻塞读者/close失败保留、Handler保存失败仍close及原件hash、退出后重开/列表读取，必须区分隔离生产执行与真实游戏。若需要更多源码（如启动兼容警告所有权）先报告，不静默扩围。
- Lead已读 [只读审计完整报告](<C:/Temp/reforgedplay-disappear-readonly-20261118-e47b6a91/AUDIT.md>)。报告把“默认观看不得篡改原件”写为产品边界，是代理在只读取证约束之外的推断，不是用户已确认的新交互要求；本轮不能据此废除原有标记/时间线等编辑保存。私有副本/另存为观看模式是更大设计，不纳入这次释放修复。报告列出的启动/兼容取消额外所有权缺口保留为已发现但未授权范围，不谎称仅修两个退出文件就覆盖全部启动失败路径。

### 20.3 用户已批准“一并修复并验证”：退出读者精确算法及合并交接（A续，2026-11-18）

#### 20.3.1 授权覆盖、成功标准和本轮观察边界

- 用户已通过ask_user_question选择“一并修复并验证”，新增授权**仅两个已有Java文件的退出/EOF及异常收尾局部**；20节标题及20.2“待批准/申请”保留为历史，现由本节覆盖。B尚未开始；仍是**一次A→B→C→D，严格串行，一个最终build**，不是追加并行修复链，不再每小步确认。
- **19.4全部objective身份门、注入位置、语言四词组、取证/文件保护和显示保真限制原样有效。** 只把19.4.2的禁止改两sender/handler条款中的FullReplaySender、ReplayHandler，为本节指定的局部收尾开白名单；其他禁止不变。本轮不把“默认播放必须只读”当用户要求，不改变marker/timeline等原有编辑及正常退出save权限。
- 成功标准分开：①原objective兼容门全部保留；②terminal请求不在MC线程取得长期sender monitor，不join/await实际读者，packet stream在实际读取停止后真实关闭；③EOF不遗失关闭入口，正常模式切换/合法预读不被terminal化；④Handler的closing callback/terminate/save失败仍请求保源close，primary/suppressed可追溯；⑤真实adapter不提前release，最终实际close成功后同路径可重开。某读者永久不退出或底层close永久失败时只能保持安全锁/诊断/重试，不承诺强制释放成功。
- A本次先重读 [构建](<build.gradle>)、[属性](<gradle.properties>)、[wrapper](<gradle/wrapper/gradle-wrapper.properties>)、[NeoForge元数据](<src/main/resources/META-INF/neoforge.mods.toml>)及libs/src，确认仍为**MC1.21.1 / NeoForge21.1.82 / Java21 / Gradle8.8 / Architectury Loom1.7-SNAPSHOT / Yarn1.21+build.1＋NeoForge patch1.21+build.4**。多个旧metadata并存不能误判时代；不升级/改映射/改构建。A只追加本节，没有改生产/资源，没有运行javap、测试、构建、旧脚本，没有安装包、Git写或读取.whale内容。

#### 20.3.2 核实依据及最小性结论（不是已实现/已动态通过）

| 核实点 | 精确证据/本次读取 | 对算法的约束 |
|---|---|---|
| packet stream的真实所有权 | 本次读取既有精确vendored Studio [getPacketData字节码:206–231](<C:/Temp/reforgedplay-disappear-readonly-20261118-e47b6a91/replaystudio-javap.txt#L206-L231>)、[ReplayInputStream.close/readPacket:3180–3208](<C:/Temp/reforgedplay-disappear-readonly-20261118-e47b6a91/replaystudio-javap.txt#L3180-L3208>) | getPacketData虚拟调用get(recording.tmcpr)；EOF不等于close；close直接调用底层in.close，无本层成功标志可代替真实调用。无需新Studio API/依赖。 |
| 所有packet读取/重开/预读目前受同一monitor串行 | [FullReplaySender异步:1072–1210](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1072-L1210>)、[同步:1333–1424](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1333-L1424>)、[PacketData:1755–1799](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1755-L1799>) | 可复用已有this monitor作**后台排空屏障**，不必全文件换锁/引入per-read引用计数/新增reader框架。禁止把这把锁搬到主线程终止路径。 |
| 活stream及close失败的真实门 | [adapter:80–95、292–315](<src/main/java/com/replaymod/core/files/TransactionalReplayFile.java#L80-L95>)、[Managed:27–49](<src/main/java/com/replaymod/core/files/ManagedReplayFile.java#L27-L49>) | 底层流close失败不减计数；末流close也可能在finishClose阶段抛错。即便末流已减计数，也可能仍需再次ReplayFile.close完成后半阶段。可复用现有ManagedReplayFile.isReleased/preserveRecovery，不修改这些类。 |
| shutdown不是读者已退出凭证 | 2026-11-18搜索后读取 [JDK21 ExecutorService官方API](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html>) | shutdown允许已排队任务继续，且立即返回；不能以isShutdown/取消Future当零活读者。保留shutdown，不用shutdownNow造成未运行任务的doneSending永不置位；绝不用JDK21 ExecutorService.close（它会等待）。submit可抛RejectedExecutionException。 |
| interrupt不是任意I/O已退出凭证 | 2026-11-18读取 [JDK21 Thread官方API](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html>)；本地 [SchedulerImpl:20–28](<src/main/java/com/replaymod/core/versions/scheduler/SchedulerImpl.java#L20-L28>) | sleep/runSync的Future.get可被中断；阻塞reader/monitor未必可中断。只能用terminal admission＋实际monitor排空证明不再读，不能interrupt后立即清ref/close。 |
| 失败链 | 2026-11-18读取 [JDK21 Throwable.addSuppressed官方API](<https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html#addSuppressed(java.lang.Throwable)>) | addSuppressed线程安全，但禁止self/null；先发生的业务失败保持primary，独立cleanup失败附加，后台失败必须另有实际日志和保留状态，不能只塞进已返回的Future。 |

**最小性选择：** 复用this作读者屏障，新增一个仅FullSender内部、至多一个同时存活的packet/file收尾线程；不新增executor/生产类/公共生命周期框架，不重写读取调度。该线程也承接Handler的**最终ReplayFile.close请求**，因为主线程直接close可能在adapter的真实read monitor上等待，或触发耗时protect/finish。正常save仍由Handler主线程按现有时序执行（关闭回调之后、close请求之前），不擅自把save异步化：只读检查已确认 [startReplay:166–185](<src/main/java/com/replaymod/replay/ReplayModReplay.java#L166-L185>)、[render queue:249–258](<src/main/java/com/replaymod/render/gui/GuiRenderQueue.java#L249-L258>)依赖其同步退出/IOException边界，异步保存会扩大调用契约。

**不得过度承诺“整个endReplay无阻塞”：** 本方案保证终止控制/关闭请求本身不等待sender读者；既有save本来就是同步I/O，可能等待adapter内部read；任意用户回调也可能阻塞。只读还确认 [现有SimplePathing closing回调:176–185](<src/main/java/com/replaymod/simplepathing/ReplayModSimplePathing.java#L176-L185>)自身await最多一分钟，表外不能改。本节不再新增主线程join/await/读者等待，并移除Handler自己已有的channel.close().awaitUninterruptibly；不声称顺带消除外部回调/保存I/O的所有卡顿。若D发现必须改这些表外调用才能解决确定死锁，报告BLOCKED，不把异步save或任务泵等待偷塞进本补丁。

#### 20.3.3 FullSender新增窄状态/helper与锁序

以下是C需实现的局部职责，helper名字可沿项目习惯小调但不得多造框架；B只能建惰性声明/stub，不能先执行这些新语义。

- sender monitor保护一个新 `replayInputClosePending`（当前stream已退休、不得再读/覆盖）；继续只持有既有 `replayIn`，**不另建丢失流的列表**。退休失败就留下原对象及pending，未成功关旧流禁止再赋新流。`nextPacket/replayInGeneration`仍受原锁保护，不重标旧包generation。
- 新短 `packetCloseStateLock` 只保护请求/线程启动与诊断元数据：`packetCloser`、单调请求序号、`replayFileCloseRequested`、已确认完成状态及最少故障引用（业务primary、第一次packet-close失败、第一次file-close失败/最新诊断）。这些是**实例字段**；不加静态会话集合。请求计数和运行线程标记避免“worker刚退出，Handler才追加file-close”丢通知。不得在此锁内取sender monitor、做I/O、启动主线程回调或等待主线程；worker在等待重试时用wait释放此短锁。
- `closeReplayInputLocked()`：调用方已持有this，设置pending，尝试**当前对象**真实close；成功才清 `replayIn`，清pending。异常则保留原对象/pending，向调用方传播IOException/RuntimeException/Error；不得finally置null、先detach再遗忘或提前宣称closed。重复成功关闭为no-op；失败后的再次真实尝试不违反“恰好一次成功关闭”。若底层流已关但adapter.finishClose抛错，保留入口并在terminal file阶段再次ReplayFile.close，不能误认为再次stream.close足够。
- `requestPacketInputClose()`：仅登记terminal清流请求并确保后台线程已启动/被唤醒；不取this，不直接close。`requestReplayFileClose(Throwable primary)`为**包内、只供Handler退出使用**的窄入口：合并primary，登记最终文件close及保源意图，唤醒同一线程；不是可任意提交Runnable的通用任务队列。这些入口可被重复调用；Handler传来业务primary时应将此前独立保存的cleanup故障附加到该业务primary，而不是反过来让早到的后台cleanup盖掉业务主错；同对象不重复suppressed。Thread创建/start失败必须保留请求和引用、同步抛出/记录，不能置假完成。
- `runPacketCloseWorker()`：只处理本sender、本ReplayFile及诊断；名字如replaymod-packet-input-close，daemon，在失败/尚未真实release时维持可达的重试责任，**不因handler全局指针被清掉就遗失失败流**。至多一个同时存活；正常成功且无待办时退出，晚到请求可串行重新启动一个，不积累空闲线程。
- worker先在**不持短状态锁/不持modeLock**时取得this，等实际当前reader退出；terminal gate已关闭，排队reader随后只能no-op。worker可在该锁内关闭当前packet流，清terminal的nextPacket；不得在锁内runSync/executeTaskQueue/join任何线程或触碰handler/world/global指针。离开this之后才执行ReplayFile保源/close/receipt检查及日志，不能拿sender锁包住全部文件收尾。
- 最终file close先处理已登记业务primary：若为现有ManagedReplayFile，用其已核验的preserveRecovery(primary)保留恢复意图，再请求close，不自动save/saveTo/删恢复材料。每次即使packet-close失败也应尝试一次文件close请求，让adapter保持closing门；活流仍在时它只能延期，不允许伪造release。Managed只有isReleased()==true才登记“实际释放确认”；返回close但isReleased=false是PENDING而非PASS；非Managed只记“close调用返回”，不得称服务锁已交回。
- packet/file close失败：保留对象/原primary与第一次对应cleanup异常，真实记录LOGGER.error或等效既有日志（含阶段、retrying及可得恢复信息）；失败后以1秒起、上限30秒的有界退避重试，等待时不持this/modeLock，新的terminate/file-close请求唤醒重试。不busy-spin、不每次向primary无限追加新同类异常（保留首个每阶段suppressed及最新日志即可）、不调用System.gc、不通过删锁“修好”。永久失败可使该daemon持续保留安全锁，这是未释放状态，不是成功。无已知失败但Managed尚有其他活stream时同样低频核对/重试文件close，由真实adapter决定何时release。
- 请求发布/worker退出必须同一短锁线性化：处理完一次快照后再看请求序号/待办，只有确认无新增请求且本次所需close已确认才清线程标记退出；失败/PENDING不能清掉最后的可达重试责任。后台不需要任何MC回调，故不依赖主线程泵队列完成关闭。

#### 20.3.4 FullSender各生产入口的精确改动点

1. **terminal与once-only分开。** 保持既有stopResourceProduction的lease.stop→terminal→terminate/asyncEpoch→interrupt顺序，resource-pack生产gate不改。terminateReplay保留terminationCleaned只管shutdown/events/channel的一次清理、resource-pack requestRelease原协议；在覆盖所有失败的finally中另行requestPacketInputClose，**不能把它放进terminationCleaned CAS内部**，否则失败不可重试。重复terminate不重复事件/channel，不抑制真实close失败诊断，已有collectFailure继续保留原primary。
2. **排队sync任务≠活reader。** syncSender.shutdown后已排队任务仍会运行原finally置doneSending，但terminal admission必须在doSendPacketsTill的sender monitor内再次检查，不能重开流。终止清流不以executor终止或asyncWorker字段证明排空；以this实际取得证明。sendPacketsTill的submit竞争只在terminal确已成立时把RejectedExecutionException当取消返回，否则照常传播；doneSending等待循环遇terminal可直接返回，不能留下MC线程为已停止任务无限自旋。setSyncModeAndWait显式模式切换仍可泵任务队列，终止重入后立刻退出等待；terminate本身绝不调用它/awaitTermination/Future.get。
3. **异步真正退出。** runAsyncSender增加覆盖return/break/InterruptedException/异常退出的finally；terminal时它只请求同一closer，不在非terminal普通mode切换时关闭共享stream/清合法nextPacket。terminal会中断长sleep/paused/runSync等待；autoRead循环继续检查terminal，迟到runSync/MC callbacks继续由原session/generation门拒绝。不得为等closer而阻塞MC，也不得要求MC替closer完成文件I/O。
4. **EOF保留关闭入口。** sync EOF不再直接replayIn=null；在当前读者持有this且本次read已结束的位置调用closeReplayInputLocked，成功后才清；异常不落回“继续read”的IOException循环，报告并结束此次发送，保留pending供下次调用或terminal后台重试。async EOF亦在pauseAsync/EOF等待前退休真实stream，不让暂停后的EOF读者继续占锁；若close失败则退出本次worker并保留失败，不能无限读已退休stream。**EOF不是terminal，不stop lease，不advanceGeneration，不误清其他合法预读**；保留原暂停/后续重播意图及原EOF之后的控制时序，本补丁不另设计自动循环/EOF产品行为。
5. **restart及异常路径统一。** 两个已有restart close分支改调用同一close helper，成功才允许打开新流，失败不可覆盖旧流；restart自己原有nextPacket丢弃和generation边界不改。getPacketData前/任何PacketData读取前发现pending，先在this内重试旧流close；仍失败则退出，不在已退休对象上read。非terminal意外worker异常如需退休流，必须在**该次持有this的try/catch/finally内部**完成，不能先解锁再由旧worker外层finally去关可能已属于新worker的流；记录原异常并suppressed关闭失败，保留restartPending。不要把普通epoch失效/Full↔Quick/false→true mode切换当异常退休，更不能仅因worker线程退出就清nextPacket。
6. **保留prefetch/session。** 仅terminal、实际EOF、原有后退/连接代际退休及真正不可继续的异常允许清对应nextPacket；正常sync↔async模式交接保留同stream同generation预读。forward rush只更新control意图，不能为清流advanceGeneration。既有dispatchGeneration ThreadLocal、controlEpoch、resourcePackLease、loader/connectionEpoch/ownsFullReplaySender判定及ready/queued admission原封不动。

#### 20.3.5 Handler局部收尾算法、失败链与重入

- 仅修改endReplay及其同类窄helper/字段，不改构造try边界、setup/startReplay/兼容取消。保留MC线程前置校验、constructionFailed门、ending gate和新旧会话身份保护；不会让closer执行handler/world/UI回调。
- 新增最少退出进度标志：closing callback已尝试、closed callback已尝试、save已尝试、最终file-close已请求（及必要的Quick unregister成功标志）。事件标志**调用前**置位，递归endReplay由endInProgress拒绝；回调抛错也不再次重播整组事件。不能声称失败事件中尚未运行的listener都已运行。原endCompleted只标记逻辑退出已完成，**不是实际文件release凭证**；重复调用即便逻辑completed也可唤醒已有file-close重试，但不能重新save/重发事件/碰新会话。
- 正常路径仍是：stopResourceProduction→closing callback一次→terminateReplay（一次性channel/events清理）→原有Quick unregister→save一次→登记requestReplayFileClose(null)→本handler原有channel/UI/world/closed callback逻辑。**save保持同步**、保持编辑保存能力；移除Handler自己的awaitUninterruptibly，只请求关闭其本地EmbeddedChannel，不等读者。file-close登记是异步资源请求，不叫真实释放收据；后台失败通过真实日志+保留状态/重试报告，不假称会同步从endReplay抛回晚到的IOException。
- 任一stop/closing callback/terminate/Quick unregister/save异常：保留最先的IOException/RuntimeException/Error为primary；finally中分别尝试terminateReplay和requestReplayFileClose(primary)，前一步异常不能跳过后一步。close请求不调用save，不把未成功save改成已提交；Managed.preserveRecovery在真正close之前保留未提交编辑/失败材料。业务失败仍按原类型抛出，cleanup独立失败附加suppressed，不用finally异常替换业务主错；后台后来才发生的失败还必须输出含primary关联的日志。
- 一旦file-close开始/请求，**重试只做收尾，不再save**，因为adapter.closing会拒绝新修改；不能重复调用save制造二次“closing”异常，也不能为重试取消真实close gate。首次失败时尚未执行的UI清理可按原身份门再试，closing/closed事件不得重复。Quick unregister若失败可重试其收尾，不把“已开始”冒充“已成功”。
- 回调可同步打开新replay：channel/overlay局部清理仅本handler持有对象；disconnect/timer/forcefullyStopReplay/setScreen仍每次检查当前全局handler==this，回调返回后重新检查。closer仅捕获旧sender与旧ReplayFile，不读取或清理当前新handler/新loader/新channel。保持19.4连接身份门不受逻辑结束、指针残留或迟到close影响。
- **未覆盖明确列出：** 初始metadata/getMarkers/FullSender构造失败、startReplay先开新file再结束旧handler的所有权、GuiModCompatWarning取消/Esc/屏幕替换、GuiHandler菜单兜底等更大所有权缺口仍未修；此处只修已建handler退出及FullSender自身读取收尾。不能因为构造catch会间接调用更完整的terminate就签字“全部启动失败已回收”。

#### 20.3.6 合并文件所有权与严格串行B/C/D

| 合并白名单（仅此表） | B：框架，不改变可执行语义 | C：串行内容 | D：审查/验证 |
|---|---|---|---|
| 新增 `src/main/java/com/replaymod/replay/mixin/Mixin_ReplaceDuplicateReplayObjective.java` | 19.4一个Mixin空回调/shadow/精确hook | 身份门＋CREATE同名局部替换 | 19.4实际变换/动态矩阵；窄编译/映射修正 |
| [mixins.replay.replaymod.json](<src/main/resources/mixins.replay.replaymod.json>) | client注册一次 | 不额外扩展 | 打包/映射及一次注册 |
| [en_US.lang](<src/main/resources/assets/replaymod/lang/en_US.lang>)、[zh_CN.lang](<src/main/resources/assets/replaymod/lang/zh_CN.lang>) | 不动 | 19.4指定四词组，不改hint.tasks | key/%s/保存未确认语义保护 |
| [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>) | 仅必要惰性字段声明/未接线窄stub；不得启动线程/实际close/改循环或异常语义；无必要则完全不改 | 本节输入流退休、terminal后台收尾、submit竞争/等待退出最小接线 | 实际生产方法/线程/流锁矩阵；仅同范围小修 |
| [ReplayHandler.java](<src/main/java/com/replaymod/replay/ReplayHandler.java>) | 仅必要惰性标志/未调用stub；不得提前改endReplay语义 | 本节失败仍请求保源close、once-only/retry/身份门，去本地await | save/callback/terminate失败动态测试；不扩大入口/构造范围 |
| [plan.md](<plan.md>) | 追加B框架前像/交接 | 追加C实现/限制 | 真实收据/失败及未测项 |

合并总数：**一个新Java＋两个已有Java＋一个Mixin清单＋两语言＋本plan（七个文件）**。B不能为“框架”添加通用任务接口、新生产类或临时仓库测试；C/D完成不得残留TODO空stub/重复框架。19.4其余全部只读限制继续，特别是Service/Managed/Transactional adapter/ReplayWorkspace/cache/ReplayModReplay/GuiModCompatWarning/GuiHandler/Viewer/QuickSender/lease bridge/设置UI/构建/依赖/metadata/libs；不改lockedPaths、不删除恢复/缓存/原文件、不全播放readonly，不动.whale，不装包，不Git写。

**交接次序：** A本节完成→B一次合并骨架及Mixin注册→C先objective＋语言片，交接并回读后**同一C顺序**完成释放片→D。若Lead出于上下文将C分两个子回合，也必须前一个完整结束后再启动后一个，不存在两个生产writer。B/C均不build；D开工前读取两片实际源码和相对本轮前像diff。D可在表内修小错误，策略/文件扩大即停报，但不再为表内每个小步骤申请批准。

#### 20.3.7 D新增最小真实生产方法动态矩阵（与19.4合并，不以旧adapter演示代测）

沿用19.4唯一新CTemp证据根规则，**本轮只创建一个新的、create-new随机唯一根**容纳objective和释放两片所有脚本/夹具/classes/日志/收据；若尚未创建可采用 `C:/Temp/reforgedplay-objective-release-20261118-<随机唯一后缀>` 并记录实际路径，不再开第二个根。所有非Gradle命令cwd为该根/子目录；绝不运行旧探针或写旧证据。旧LeakProof/AUDIT/javap只作只读因果依据；需要的旧stub文本复制后审计所有路径常量及写前root断言。真实用户MCPR仅标准库只读或隔离副本，不对原件走Service/ZipReplayFile。

**真实性门：** 必须执行修改后的实际FullReplaySender.terminateReplay/runAsyncSender/doSendPacketsTill/sendPacketsTill及ReplayHandler.endReplay（必要用反射驱动private生产方法），不复制一份手写关闭算法代测。MC/GUI/事件边界可stub，但当前ReplayStudio、生产Service/Managed/adapter尽可能真实参与合成MCPR链；明确每项是“生产sender真实方法＋哪些stub”还是“仅adapter因果验证”。原7项LeakProof收据不是本次sender PASS。外部javac只编新根夹具和所需生产源/类，不改仓库测试；若整类依赖无法隔离，列准确BLOCKED，不能裁掉关键finally/锁/循环后冒称整类已测。

| 必测slice | 调度与观测（有界latch/barrier，不靠随缘sleep） | 验收 |
|---|---|---|
| idle terminal、重复terminal | 未开流/已开但空闲分别实际terminate两次以上；收尾完成后再调用 | 无MC join/await/sender monitor等待；零流不造流；已开流真实成功close一次；events/channel/lease一次语义不变，无多closer |
| active terminal、不可中断blocked reader | reader持this进入真实/包装read，用latch阻塞且忽略interrupt；另一“MC线程”调用terminate；随后才放行reader | terminal调用先返回、close计数仍0/锁仍在；放行后read退出，再真实close一次/最终release。不得通过强关reader解除latch；永久不放行标PENDING/安全持锁，不签已释放 |
| 异步实际退场 | 真runAsyncSender线程分别paused、长timestamp sleep、autoRead=false、runSync pending；terminal后排队旧主线程callback迟到 | 测实际worker退场/finally和closer；无monitor↔MC等待环；迟到回调无新会话动作；仅interrupt调用次数不足以PASS |
| sync EOF、async EOF | 合成短/空packet流；实际生产读取到EOF，包含minimal framing与Studio readPacket路径可达性说明 | EOF真实close且ref仅成功后清；async EOF暂停前不再占stream；close失败保留pending、未再read/覆盖，随后terminal可补关；EOF不terminal/不stop lease |
| shutdown队列与submit race | 一个正在读任务＋至少一个排队任务；shutdown前后barrier制造submit竞争；terminal时主调用仍在doneSending循环 | 活读者未结束不提前关；排队任务finally完成/no-op不重开；terminal submit拒绝正常取消，非terminal拒绝仍暴露；主等待可退出，无shutdownNow丢done |
| 模式、预读、回退 | sync预读一个未来包→async→sync；旧worker迟到；Full↔Quick；forward rush与后退/连接重建 | 正常mode切换不关合法stream、不清合法nextPacket、不advanceGeneration；包恰好一次且旧generation不被重标；后退原关闭/重开正确 |
| close失败及重试可达 | 首次底层packet close抛，再成功；另测底层已关而adapter.finishClose失败；file.close本身失败；连续terminate/Handler清全局指针 | 原ref/primary/suppressed/真实日志可见；失败时无fake release；只有一个在途closer；即使全局handler被清仍有重试责任；成功后同一真实底层close不重做，file后半段可再次close完成 |
| Handler异常收尾 | 实际endReplay注入closing callback、terminate、save各自失败；组合save主错＋close次错；Quick注销失败；closed callback失败 | 每条仍登记file close；primary身份/类型不变、cleanup suppressed无self/覆盖；未提交fixture恢复材料仍在、无额外save；重入及重试不重复closing/closed、终止事件，不因endCompleted跳过已有重试 |
| 旧handler→新会话 | closing回调同步安装B；A迟到closer/事件/重复endReplay；另测回真实联机但旧指针残留 | 只关A持有文件/流/channel/overlay；不disconnect/清B、不改B timer/screen/loader；结合19.4旧handler objective拒绝门 |
| 真实adapter端到端 | 合成MCPR经实际Service打开，由实际FullSender读取路径调用ReplayFile.getPacketData持流，Handler失败/正常退出请求close；列表等价openReadOnly重开同路径 | 持流期间真实FileLocked、没有删锁；实际stream关闭＋adapter真实release后metadata可重新读取；失败保源case原输入hash不变/恢复可读。正常可写save允许现有语义，不能把fixture合法save当用户原件被禁改的新需求 |

**回归/证据要求：** 19.4所有objective/score/display/真实Sponge变换门不减少；重新在新根执行所需资源包lease/session边界回归：暂停/autoRead/runSync终止、旧producer/queued admission、forward rush合法包、后退generation、Full↔Quick、结束→新replay/真实联机、重复release、ready路径延迟加载及close失败不误删。旧测试只可复制审计后的文本，不运行旧脚本。UI源仍需19.4保护hash，表外生产/资源及原证据前后不变；旧59＋323/旧build只历史，不谎称本轮重跑。

#### 20.3.8 唯一build、产物与未测风险

- D仍且仅一次调用：`mc_gradle(projectDir="C:/Users/zhenz/Desktop/ReForgedPlay-1.21", task="build", args=["-Djava.io.tmpdir=C:\\Temp", "--console=plain"], timeoutMs=1200000)`。A/B/C不build，不tasks/help/clean/refresh/runClient，不shell wrapper，不追加第二build；失败原样报告。构建前完成可做的静态及新根生产方法探针，Java21继承环境不符停报，不装JDK/改环境/依赖绕过。
- D交工具原文/任务状态、相对本轮前像七文件diff、表外保护hash、最终non-dev JAR大小/SHA256/ZIP完整性/新Mixin一次注册及映射和语言词组、生产sender/handler动态matrix逐项结果、真实adapter重开收据、真实变换层级和全部FAIL/BLOCKED/PENDING；后台关闭只“已请求”的项不能算release PASS，NO-SOURCE不能算测试通过。
- **风险/停点：** uninterruptible reader不退出、末流close或protect/cache.finish永久失败会故意留锁，不能强清；后台收尾为eventual而非方法返回即release。Handler原save及表外回调仍可同步阻塞，本补丁不承诺全退出零卡顿；不通过全面异步save/改SimplePathing等表外文件偷解。真实NeoForge运行264与编译82、实际Mixin链及全模组GUI/资源包行为仍需游戏验收。构造/启动/兼容取消额外所有权缺口明确未修。
- **真游戏未测**：D隔离生产执行/adapter链/构建成功也不等于用户实例可播及列表体验已验收；最终仍需用户替换包后打开两份录像、重复进退/切模式/拖动、核对score/sidebar及列表恢复、退出回放连服务器。不得安装到用户游戏/改配置/删cache来替代验证。A本节结束时仅计划已追加，新增两个生产文件也均未改；下一阶段只能串行B。

### 20.4 Lead复核、唯一证据根及用户重启边界补充

- Lead已读A首轮19.4及续规划20.3，批准既有精确范围内依次交B→C1(objective/语言)→C2(退出释放)→D。当前生产文件仍与设置交付基线一致。B无需为了框架而给FullSender/Handler制造无用字段或stub，可只建唯一Mixin空hook与client注册，退出片的真实状态/helper由C2一次接入；不得将任一空hook签为功能修好。
- **本轮唯一取证根已由Lead create-new建立：** `C:/Temp/reforgedplay-objective-A-20261118-6d9a3c`。baseline-hashes.json记录567项source/build/wrapper/libs/用户日志和报告；allowed-sources.json列六实现/资源文件（另plan）；五份既有文件前像用于真实本轮diff，新Mixin在B前不存在。check_scope.py before-B实际输出changed=[]/added=[]/protected_unchanged=567；UI原hash及当前JAR仍匹配19.4.1。B/C/D所有新输出均只在此根下自己新子目录，别另开总根或复用旧D路径。
- Lead在该根 [验收输入定位](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/LEAD-VERIFICATION-INPUTS.md>)列出可只读复制重编译的旧Sponge host源码/CP及旧Full/Handler回归输入定位，不运行旧脚本/旧classes、不写旧根。现有archive_packet_observations对18_52_30第二CREATE后的index372显示、414–422分数及另一份对应包已有raw观察，但仍不是完整score/reset审计；D按19.4执行完整新审计，不能以几个后继包冒称完整保真。
- Lead额外尝试只作MC API语义取证（非生产Mixin验证）的ScoreboardFacts：javac成功，首次runtime被旧宽泛compile CP内DFU6抢先导致Codec.stringResolver NoSuchMethodError；按本机MC metadata明确的DFU8.0.16调整**仅外部probe CP**后，第二runtime到MC注册表Not bootstrapped失败。两轮原日志保留，未再盲目重跑或改项目依赖；**没有实际MC Scoreboard动态PASS**。D若继续这层需用准确声明依赖及合法bootstrap/清楚的隔离边界，不拿此处编译成功当动态通过；原Sponge变换门仍保持。
- **用户最新明确补充**：是完整关闭Minecraft后重新启动仍不显示；可将重启电脑理解为清cache/temp，不排除缓存原因。此事实高于早期“同进程退出重进”的可能解释。lockedPaths是每个Service实例的内存集合，不可能跨已结束JVM直接延续；既有日志/流锁探针只证实一条真实缺陷，不能唯一解释该跨进程观察，不能宣称关闭reader就已证明解决全部列表隐藏。
- D新增同一合成MCPR/相同cache与tmp根的冷新JVM对照：先退出/异常收尾，**不清cache/temp**，在新进程由实际Service/list-equivalent openReadOnly读取metadata；再区分此前仍活进程持锁/已结束owner、有效缓存/失败恢复材料。所有样本为新根合成小文件，不操作用户原cache/temp或真实配置。若现有白名单之外的持久缓存/临时恢复问题被实际复现，记录准确FAIL及需要的新最小文件，不擅自改cache/Workspace保护；此新诊断不成为再删cache或绕锁的理由。最终报告保留用户实际跨进程重现尚未由真游戏验证的边界。
- 旧D-final“save失败后二次end重试save成功”的历史F1与本轮“失败也请求保源close”契约冲突已在20.3.5明确改为：close请求后仅重试收尾、不重做save；失败编辑依靠既有恢复材料保留。D必须在新结果中说明这一语义变化，不能套旧F1标签或把假保存成功算通过。

### 20.5 B框架交接（2026-11-18；仅空hook，功能未实现）

- B已完整回读19.4、20.3、20.4及构建/属性/wrapper/NeoForge metadata，确认仍为MC1.21.1、Neo21.1.82、Java21、Gradle8.8、既有Architectury Loom1.7-SNAPSHOT及原映射；不scaffold、不改版本。受本B角色明确禁止查skills的约束，本B未加载技能；19.4.1中A的加载记录仍只是A的动作，没有冒充B已加载。本轮API依据为精确本地JAR的实际只读javap。
- **唯一B输出子目录：** [B-skeleton-20261118-7f2c91](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91>)，在20.4已定唯一根下create-new建立。所有命令cwd及新增脚本/日志/收据均为该子目录；未运行旧脚本或改旧证据/项目logs。执行前[保护检查](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91/scope-before-B.json>)为changed=[]、added=[]、567项原hash全部一致。
- 新建 [Mixin_ReplaceDuplicateReplayObjective.java](<src/main/java/com/replaymod/replay/mixin/Mixin_ReplaceDuplicateReplayObjective.java#L16-L39>)：目标ClientPlayNetworkHandler，`@Shadow @Final private Scoreboard scoreboard`；唯一回调`replaymod$replaceDuplicateReplayObjective(ScoreboardObjectiveUpdateS2CPacket, CallbackInfo)`主体只有TODO注释。**未实现**主线程显式检查、任何连接/sender身份门、objective移除或其他兼容逻辑；不取消packet、不重投packet、不引入可变静态状态，也未改变FullSender/Handler运行语义，不能把此空hook签为修好。
- 精确目标descriptor为`onScoreboardObjectiveUpdate(Lnet/minecraft/network/packet/s2c/play/ScoreboardObjectiveUpdateS2CPacket;)V`；唯一INVOKE目标为`Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/util/thread/ThreadExecutor;)V`，`At.Shift.AFTER`、`cancellable=false`、`require=1/expect=1/allow=1`，不捕获局部变量、不禁用remap。由指定`C:/Program Files/Java/jdk-21.0.12.1/bin/javap.exe --system ...`对19.4.3精确named MC/Neo82 JAR再次核对：handler的private final scoreboard存在，目标偏移6调用ensure、偏移49调用addObjective；ensure离线程路径executeSync后抛OffThreadException，正常返回仅主线程。见[handler:5491–5519](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91/named-handler.javap.txt#L5491-L5519>)、[ensure:25–38](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91/named-thread-utils.javap.txt#L25-L38>)、[Sponge注解签名](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91/mixin-annotation-api.javap.txt>)。这只是签名/字节码核验，**不是**编译、真实Sponge变换或动态PASS。
- 实际注册在 [mixins.replay.replaymod.json:13](<src/main/resources/mixins.replay.replaymod.json#L13>)的**client数组**，紧随Mixin_HandleInputsInReplay，仅一处`Mixin_ReplaceDuplicateReplayObjective`。未改mixins/server、plugin、compatibilityLevel、metadata或其他清单。FullReplaySender、ReplayHandler完全不改，不制造惰性字段/stub；C2按20.3一次接入真实退出收尾。两语言完全不改，交C1按19.4四词组落实。
- [真实本轮两文件diff](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91/actual-after-B.patch>)直接比较20.4前像，不用累计HEAD diff：仅新增上述40行Java＋client清单1行。[收尾保护检查](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91/scope-after-B.json>)为changed=[该清单]、added=[该Mixin]、原567项中566项未变（唯一变化为获批清单）；UI SHA256仍为7cb45c379e36055ddaf81fc5e43f193901d6e1e37e6cd7fb08f2ec6a273563fa。既有源改不撤销。另有[本交接段追加diff](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91/actual-B-plan.patch>)，旧计划内容原样保留。
- [JDK环境](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91/jdk21-environment.txt>)记录指定java/java.home=Oracle21.0.12.1；javac/javap仅`-version`或javap只读反汇编，均exit0；未以此声称继承Gradle JVM已验证。角色契约所需[外部测试占位stub](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/B-skeleton-20261118-7f2c91/BStageTestStub.java>)只在B外部目录，assert true，无生产依赖，**未编译、未执行、不代表任何功能测试**，未新增仓库测试。
- B未build、未编译、未运行测试/游戏/装包、未Git写、未访问.whale、未接触真实录像/配置/cache/temp或恢复材料。用户“完整重启Minecraft仍隐藏”边界按20.4保留，不宣称进程内lockedPaths是跨进程唯一根因，也不扩围cache/Workspace。真实Mixin变换、objective行为/显示保真、退出释放和冷新JVM对照均交D验证；本B不提前启动C或D。下一步由Lead收到完成通知后串行交C1→C2。

### 20.6 C1内容交接（2026-11-18；objective身份门及指定语言词组）

- C1完整回读19.4/20.3/20.4/20.5、B实际空hook及现有归属链；先读[build.gradle](<build.gradle#L1-L11>)及[NeoForge metadata](<src/main/resources/META-INF/neoforge.mods.toml>)确认既有现代Java21/Loom时代，MC1.21.1/Neo21.1.82/Yarn+patch及所有构建设置不变。角色禁止加载skills，故未加载或冒称加载技能；仅先查[A指定API reference](<C:/Users/zhenz/.dsh/profiles/desktop/node_modules/minecraft-dev/assets/skills/minecraft-neoforge-mod/references/api/mod-bus-events.md>)，其不涵盖scoreboard/连接/Mixin，缺项用指定本机JDK21及精确本地JAR补核。
- **唯一C1输出子目录：** [C1-content-20261118-a9d43b](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b>)，在20.4唯一根下新建；所有本片命令cwd及新脚本/前像/日志/classes/收据均在此子目录。未执行旧脚本或旧classes，未覆写旧失败日志。执行前[保护收据](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/scope-before-C1.json>)核对567项：相对Lead基线只有B获批清单改变及B新增Mixin，UI保护hash一致；本片保存B后逐源hash及六文件前像。
- [Mixin_ReplaceDuplicateReplayObjective.java:45–90](<src/main/java/com/replaymod/replay/mixin/Mixin_ReplaceDuplicateReplayObjective.java#L45-L90>)现有实际逻辑：只有ADD_MODE且身份门通过，才在shadow的原版同一scoreboard查询同名旧对象并移除一次，然后回调正常返回，继续原版add，保留新包的displayName/renderType/numberFormat。首次创建/其他名字/UPDATE/REMOVE/其他mode不额外移除；不硬编码名称、不全清board、不catch/cancel/重投包。B完整descriptor、AFTER forceMainThread、require/expect/allow=1和shadow均未改变。
- 唯一窄`@Unique`实例helper显式检查`mc.isOnThread()`，要求mc当前handler==注入对象、该对象的connection非空及listener==它；安全instanceof复用既有[NetworkManagerAccessor](<src/main/java/com/replaymod/recording/mixin/NetworkManagerAccessor.java>)，要求其channel为open且active的EmbeddedChannel，pipeline.packet_handler==该connection，且pipeline的PACKET_HANDLER_NAME项==当前ReplayHandler.getReplaySender()同一非空对象。全局module/handler/sender空、accessor/channel缺失或任一身份不符皆不兼容，继续原版语义；无新生产accessor/类/静态会话状态，无跨回调缓存。Full/Quick共用同一对象身份门，不依赖sender类型。
- [zh_CN.lang:367–370](<src/main/resources/assets/replaymod/lang/zh_CN.lang#L367-L370>)仅applied/callbackFailed/saveFailed三处“八项”→“更改项”；[en_US.lang:368](<src/main/resources/assets/replaymod/lang/en_US.lang#L368>)仅callbackFailed的“All eight values”→“Changed values”。其余字节、key/%s、英文applied/saveFailed及未确认落盘语义均不改；两语言hint.tasks仍提八项是明确残留限制，不声称全页已统一。
- **API核实：** 指定Oracle21.0.12.1的java/java.home/javac版本及javap全部exit0；[MC连接/主线程API](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/named-client-connection-thread-api.javap.txt>)、[packet/scoreboard API](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/named-scoreboard-packet-api.javap.txt>)、[Netty4.1.97 API](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/netty-channel-api.javap.txt>)及[Unique注解API](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/mixin-unique-api.javap.txt>)均按19.4精确JAR核实，有[输入JAR指纹](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/api-inputs-sha256.json>)。没有新依赖或签名猜测；此层不等于Sponge命中或运行验证。
- **仅外部API编译：** [编译收据](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/compile-api-c1-receipt.json>)及[原捕获日志](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/compile-api-c1.log>)记录指定javac21 `--release 21 -proc:none -implicit:none` exit0；复制后的生产Mixin与既有accessor逐字节不变，MC/Netty/Mixin为真实JAR，**只有ReplayModReplay/ReplayHandler/ReplaySender三个应用类型为编译边界stub，未执行它们**。最小CP缺NeoForge Dist注解枚举，产生4条unknown enum constant Dist.CLIENT警告（本机诊断按UTF-8捕获出现乱码，日志原样保留），没有编译错误；不签“无警告”或“完整项目编译通过”。Mixin源码SHA256=`862cc4300b0c073ac2293c96c562fdf081569d23f265ac25936ebb4be379c521`。未运行动态测试、注解处理、真实Sponge变换或Gradle；唯一build仍留D。
- **真实差异/保护：** [仅C1三文件差异](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/actual-C1-only.patch>)比较B后前像；[B+C1相对Lead前像差异](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/actual-B-plus-C1.patch>)保留B清单注册；[计划追加差异](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/actual-C1-plan.patch>)不改旧段落；[源hash](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/source-hashes-after-C1.json>)及[收尾保护收据](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C1-content-20261118-a9d43b/scope-after-C1.json>)检查本片只变Mixin/两语言，清单和FullSender/Handler及其他被保护源不变、旧证据原样、UI仍为19.4.1指纹，语言精确四词组替换，plan仅追加。不是累计HEAD diff，不撤销既有修改。
- **语义及未测限制：** 移除旧objective会清除它的scores和display slots；仅后继SET_SCORE/SET_DISPLAY等相应包可恢复，后继UPDATE不能恢复，不复制旧状态。C1未做样本score/display审计，不把“不崩”称完整保真。实际Sponge变换、线程调度/当前Full与Quick、旧handler/连接切换/真实联机拒绝、score/display动态矩阵、remap后264兼容及真游戏均待D；没有行为PASS。用户跨进程列表隐藏原因未由本片解释或解决，20.4边界继续有效。
- C1未改[FullReplaySender](<src/main/java/com/replaymod/replay/FullReplaySender.java>)、[ReplayHandler](<src/main/java/com/replaymod/replay/ReplayHandler.java>)、清单、UI、默认值、cache/Workspace/Service、构建/依赖/metadata、仓库测试、用户真实配置或原录像；未Git写/装包/游戏/读取.whale。**本C1完成后等Lead实际回读，再在独立上下文串行交C2退出释放片，不能并行；C2完成后才交D唯一构建及合并验证。**

### 20.7 C2退出释放内容交接（2026-11-18；实现已接线，动态验收待D）

- C2在Lead实际回读C1后串行开始，完整读取20.3精确算法、20.4重启边界及20.6，并先读既有build.gradle和NeoForge metadata识别现代Java21/Loom时代；固定MC1.21.1/Neo21.1.82/Gradle8.8/Yarn＋patch，不动构建或依赖。角色明确禁止查skills，故未加载或冒称加载技能；先读19.4所列API reference，退出专属契约另读实际Managed/Transactional/Workspace源码及精确本地JDK21/Studio API，没有使用新模板API。
- **本片唯一新输出子根：** [C2-content-20261118-c7f042](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042>)；自建标记后cwd切入此根，所有本片命令cwd、前像、复制源、classes、脚本、日志/收据均在此子根。未运行旧script/旧probe/classes；仅只读解析Lead列出的旧javac命令JSON定位依赖，删去全部旧夹具CP，仅保留本项目生产编译依赖和实际缓存JAR。既有production build/classes只作javac输入，不执行或覆写。
- **FullSender实际状态/helper：** [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>)新增this保护的replayInputClosePending；closeReplayInputLocked只在当前对象真实close成功后清ref/pending，失败保留原对象并记录每阶段首错。非terminal异常在该次this范围内退休本次reader/保留restartPending，不在旧worker解锁后的finally关新worker流；pending在下一次读者入口先重试，失败不继续读/覆盖。sync/async EOF均真实退休，EOF不terminal、不stop lease、不advanceGeneration；两个原restart close点共用helper，普通mode/epoch交接仍保留合法stream/nextPacket。
- **单closer及线性化：** 短packetCloseStateLock仅维护请求序号/单packetCloser/packet与file完成状态、业务primary及首packet/file失败；请求不取this或做close，启动失败保留请求/失败线程引用并日志/抛回。daemon worker取得this排空时不持短锁或modeLock，仅在此屏障内关packet流/清terminal预读；退出this后才处理保源意图/ReplayFile.close及日志。Managed.close返回而isReleased=false仍PENDING并重试；非Managed仅标close调用返回，不宣称服务锁释放。失败/PENDING以1秒起、上限30秒退避，新请求可唤醒；请求发布和退出判定使用同一短锁，晚到file请求不会丢失。per-stage首cleanup只附加一次，不以GC/覆盖流/删锁强制释放。
- **保源意图不是备份收据：** worker在Managed最终file阶段先preserveRecovery(primary)，成功返回后只登记一次replayRecoveryIntentRegistered；实际Workspace.saveFailed会将失败record-write直接suppressed到primary，故不能每次file-close重试重复登记而无限增长异常链。真正protect/finish和release仍完全由未改adapter裁决。日志记录阶段/retrying/业务primary及可得recovery路径；路径出现不等于保护已成功。packet-close失败仍进入file-close阶段，adapter可保持closing门；永久读者/close失败继续安全持锁和可达重试，不能签release成功。
- **FullSender生产接线：** terminateReplay在terminationCleaned CAS之外的finally请求packet close，保留一次events/channel及原resource-pack release协议；runAsyncSender增加覆盖退场的terminal-only请求finally。sync任务在this内重查terminal，shutdown保留已排队任务finally置done；submit拒绝仅在terminal已成立时按取消处理，否则仍抛；sendPacketsTill和显式setSyncModeAndWait等待遇terminal退出。没有新增MC join/await/sender长monitor等待，也没有改原session/generation/connection/lease门、forward rush或quick协议。
- **Handler实际接线：** [ReplayHandler.java](<src/main/java/com/replaymod/replay/ReplayHandler.java>)仅endReplay及其私有进度/失败helper：closing/closed事件调用前标attempted，save调用前标attempted，Quick unregister仅成功才标记；保留constructionFailed/endInProgress/ending/旧handler身份门。正常save仍原同步I/O，并保留标记/时间线编辑。移除本地channel.close().awaitUninterruptibly，仅关本handler本地channel。任意stop/callback/terminate/Quick/save失败保留业务primary原类型，在finally分别尝试terminate与requestReplayFileClose(primary)，前一步失败不跳过后一步，独立首cleanup suppressed不覆盖primary。
- **重试语义已改变且不冒称旧F1通过：** 一旦file-close已请求，后续endReplay只收尾，绝不再次save或重播事件；首次失败未做的本地UI收尾可在身份门下重试，closed事件也最多尝试一次，不能声称抛错事件组后续listener都运行过。endCompleted只表示逻辑退出，重复调用仍可唤醒file收尾，不代表实际release。closer只捕获旧sender/file，不读取或清新handler/world/loader；原disconnect/timer/forceStop/screen重入身份检查不变。
- **实际编译/API层：** [最终整两源编译收据](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/v5-compile-whole-C2-receipt.json>)及[原日志](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/v5-compile-whole-C2.log>)：指定Oracle21.0.12.1，--release21/-proc:none/-implicit:none及空sourcepath，完整未裁剪两源复制件对真实named MC/Neo82、Studio和既有生产compile-only类编译exit0、输出无诊断；**无stub、未执行任何行为，不等于全项目/Gradle通过**。最终[生产字节码](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/v5-production-bytecode.javap.txt>)及[JDK/Managed/Studio API](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/v5-managed-studio-jdk-api.log>)仅静态javap、均exit0；没有动态owner方法PASS。首轮脚本在java版本命令exit0后因Python控制台GBK打印失败退出，修捕获打印后使用v2/v3/v4/v5分名保留日志，非生产编译失败，未覆写失败日志。
- **本片指纹/真实差异：** FullSender SHA256=`0d79e9af57189b39b2454c244b2f29deca1e5af8baec7a8baa86fa058bbd9b6e`；Handler SHA256=`a417d29b5379cf89a64d84e158a66e2bae2bdaa18ad77febbf72919e9ecd7c11`。相对C1后前像的[两源实际diff](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/actual-C2-only.patch>)、[本段追加diff](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/actual-C2-plan.patch>)、[源hash](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/source-hashes-after-C2.json>)与[收尾保护收据](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/scope-after-C2.json>)核对本片仅两源变化、plan仅追加，C1 Mixin/两语言及B注册不变，568项既有源/资源/构建/日志中其余566项不变，src无新增文件；UI仍`7cb45c379e36055ddaf81fc5e43f193901d6e1e37e6cd7fb08f2ec6a273563fa`，git diff --check exit0（仅既有LF/CRLF提示）。不是累计HEAD差异，不撤销既有改动。
- **取证输入漂移如实披露：** [最终审计](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/v5-audit-C2.json>)继承C1的45项旧证据manifest复核44项一致；唯一不同是LEAD-VERIFICATION-INPUTS.md，C2首读29行、后读36行，新增Lead冷JVM基线说明（C2未写该输入）。首次“全旧证据相等”断言因此失败，[失败说明](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/C2-content-20261118-c7f042/audit-first-failure.txt>)保留，未伪称45项全未变。新Lead合成core冷JVM对照不替代本片真实owner矩阵，更不等于复现/排除用户跨进程缓存问题。
- **明确未测/交D：** C2没有运行Gradle、生产动态探针、旧7项LeakProof、游戏或安装；20.3.7全部真实Full/Handler线程/EOF/故障/reentry/adapter重开矩阵仍须D新根执行，不能拿此编译或原7项代替，旧save失败后二次重save F1合同已被本节新语义取代。保留同cache/tmp冷新JVM对照，不清用户cache/temp、不删锁。完整Minecraft重启仍隐藏不是纯内存lockedPaths可跨JVM延续的证明，本片不声称解释或解决全部列表隐藏；若复现表外持久缓存问题停报，不改Service/adapter/Workspace/Viewer。
- **边界与下一步：** 原同步save以及表外closing回调（含SimplePathing等待）仍可能阻塞，不宣称整个endReplay零卡顿。metadata/markers/FullSender构造、startReplay新file所有权、兼容警告取消/Esc/屏幕替换及GuiHandler兜底缺口仍未覆盖。未改表外生产/资源/测试/设置UI/构建/依赖/metadata/libs、真实配置/录像/.env/.whale，无Git写；**本C2完成先等Lead实际回读，再由Lead交D唯一build和合并验收，C2不启动D。**

### 20.8 Lead合并实源复核与D冻结入口

Lead已实际读取C1新Mixin92行、四词组diff、C2两源546行diff和Handler完整endReplay，回读20.6/20.7收据；本轮 [Lead范围核对](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/scope-lead-after-C2.json>) 为相对基线五既有文件改变＋唯一新Mixin，其余562保护项hash不变，UI仍7cb45c379e36055ddaf81fc5e43f193901d6e1e37e6cd7fb08f2ec6a273563fa。现在才串行进入D，不把外部API编译当功能验证。D自己的新子根内可以修六实现/资源文件中的明确小缺陷，其他文件仍只读。

- Lead当前总根的旧输入至此冻结，不再修改LEAD-VERIFICATION-INPUTS.md；C2期间该文件新增冷JVM说明是Lead自己写的可定位差异，44/45保留不是旧证据全不变，已如实披露。D新输出仅总根新D子目录；完成后Lead仅新建Lead-final子目录和更新本计划，不改D日志。
- Lead新增 [真实录像保护收据](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/real-replay-input-protection.json>)：两份用户MCPR仅读ZIP CRC全部entry通过，源SHA256/size/mtime前后不变（18_52_30 sha94fc745ec251d886f80107c6e78c24a2475afab9c53ad72d1857190b668be1c7；18_54_50 shac8ee41847b11544d8fe8fe5069ee15f09ef138d36019415a019667f382ffef36）。不是协议可播证明，D若需生产方法数据用隔离副本或只读原字节，不得在原路径调用可写Service/ZipReplayFile或save/cleanup。
- 新 [冷进程基线](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/lead-cold-baseline/receipts.json>) 已执行：两个独立PID8912→5484，第一进程故意不关闭合成真实packet reader后退出，第二进程保留完全同cache/tmp/sentinel，实际Service metadata读取成功。它只编译当前core/adapter/ReplayStudio及MC/GUI边界stub，明确不含C2正在改的Full/Handler；**不是C2生产owner验收，亦未复现用户实际跨进程隐藏**。D仍补20.3全部生产owner矩阵及新cold对照；未复现不能写成已排除缓存因素。
- Lead复核关注点交D：关闭线程的责任交接与晚到file-close请求不得丢；“单closer”应验证资源操作单责任/无并行重复close，不能仅用逻辑完成旗标当物理release。末流close抛在adapter.finishClose后半段、preserveRecovery登记失败/诊断获取失败、Handler先save失败后再收尾/原始primary与cleanup错误、回调重入新handler等都需要边界明确的实际生产方法测试。正常切换mode/epoch与合法预读不能被旧worker finally退休；旧F1语义不得再签原样通过。真实Sponge目标变换/运行边界和用户264实例未测必须单列。

### 20.9 D真实编译审查与分层验证（2026-11-18；候选交Lead复核，不签真游戏验收）

- D完整回读19.4/20.3/20.4/20.6/20.7/20.8后，唯一create-new输出子根为 [D-verify-20261118-e618d4](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4>)；[完整报告](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4/REPORT.md>)、[证据索引](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4/EVIDENCE-INDEX.md>)包含每层边界/失败/未测。角色禁止加载skills，故未加载；按指定reference与精确本机JAR核验。未改六文件生产实现，C1/B/C2源指纹保持，未改表外生产/测试/构建/配置、未Git写/装包/启动游戏/碰whale。
- **唯一实际mc_gradle build成功36s**，参数严格为 `build -Djava.io.tmpdir=C:\Temp --console=plain`、timeout1200000；没有第二次/clean/tasks/help/refresh/-x test/shell wrapper。[工具原文](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4/mc_gradle-build-tool-original.txt>)与[收据](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4/mc-gradle-receipt.json>)保留。工具canonical渲染对exit0不打印marker；本次BUILD SUCCESSFUL且无非零/signal/timeout marker，按实际renderer解码exit0/timedOut=false，未声称工具另暴露了JSON字段。`test NO-SOURCE`绝非JUnit成功；Loom过旧、deprecated/unchecked、Gradle9、WorldRenderer/JOML既有诊断原样保留。继承JAVA_HOME为空，但实际Windows wrapper首Oracle javapath经cmd实测为指定Oracle21.0.12.1，不是PATH第二项25；所有外部Java工具显式指定JDK21。
- **新non-dev产物** [reforgedplaymod-1.21.1-0.3.1.jar](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>) 为 **17,626,514B / SHA256 3328e9958090a2d51c565eaea674cb19512af90c761a2f7d889a9aa2b843c42a**；ZIP全entry CRC、唯一client注册、两语言精确源字节通过。[最终产物收据](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4/artifact-receipt.json>)及11项ASM接线断言确认mapped descriptor/ensure/shadow/require=expect=allow=1、terminal/async finally请求、sync退休、Managed实际release检查、Handler一次save/event callsite和无本地join/await。旧5fbc产物仅历史，不能再当当前包。
- **Objective分层PASS**：未改生产callback/helper身份矩阵41项，正例用当前完整Full/Quick类型Unsafe分配作身份对象，真实Netty；MC/scoreboard/application边界stub明确，不冒充变换后目标执行。另在实际用户实例Mojang基础JAR、精确MC声明依赖DFU8和client-extra资源下合法bootstrap，实际Scoreboard 7项通过：旧多holder/多槽清理、其他objective/team保持、新display/render/format、UPDATE不恢复、后继显式score/display恢复。named bootstrap缺FMLLoader仍BLOCKED；Mojang首试缺语言资源失败后准确补外部CP成功，原失败不删。
- **真实Sponge PASS**：新编译独立host对当前named Neo82两目标、最终non-dev JAR-first的Mojang82两目标和actual264实例基础srg两目标均真实MixinTransformer命中；唯一hook严格位于ensure成功之后/原版add之前，accessor和shadow映射成立。standalone配置省略项目plugin/其它mixins，**不等于NeoForge264 launcher/全mod顺序**；尚未实际执行变换后handler的off-thread调度重入，不能拿直接callback stub替代该层。
- **完整owner生产行为PASS**：79项实际未裁剪Full/Handler/core/Service/Managed/adapter/Studio执行（MC/GUI/事件注册/Quick边界stub、Unsafe跳过Handler构造）；另28项真实异步paused/长timestamp/autoRead/runSync终止、正常mode/prefetch、submit竞态及真实adapter末流close已关delegate后workspace.finish故障/保锁/引用保留/重试release；另12项preserve意图失败、诊断获取失败、Handler close请求启动故障及Quick注销故障注入重试。断言观察真实close/Managed.isReleased及实际FileLocked→preview，不以isShutdown/flag当release。idle/active不可中断reader、late file request、EOF/close失败、业务primary/suppressed、事件attempt一次、save一次、重入新会话隔离、正常可写编辑保存均有新收据；故障边界细分见报告，有限探针不声称覆盖所有线程启动/永久失败/穷尽竞态。
- **回归PASS**：新编译资源包lease/session19组；Quick0/targetzero/forward/intent/repeat/重建channel/readeronly/prefetchonly共9个独立执行（实际Handler/Full/Studio，Quick/decoder/restart边界明示）；UI默认59＋原UI323实源断言通过，英文只按批准措辞更精确期望，旧断言未删弱，fixture根全部改D。一次Quick聚合外层240s超时，7份已完成exit0保留、在途readeronly退出码未知不签通过；readeronly/prefetchonly另编号新fixture重跑exit0。早期工装编译/路径/协议表/pause fixture错误和失败日志保留，部分最早Python控制失败只有ops返回、未实时重定向，证据缺口在报告明示，未编造不存在的raw日志。
- **两份真实录像完整新审计**：14,954/9,367个包逐包sourceZIP→私有副本一致；score/reset/display/objective全部解码并要求每payload消费完毕。事件51=23objective+2display+21score+5reset；42=14objective+2display+21score+5reset。第二CREATE索引370/363；旧7行/slot1被有意退休，后继372/365恢复display，414–422/748–756重建9行lobby分数（均timestamp0），后续reset演化和格式也留存。五个旧Limbo holder最终不复存，不盲目复制到新段；无未处理raw/server oracle，**完整画面保真和生成重复源头仍未确认**，不是“不崩即保真”。用户原件只读hash/mtime/CRC保持，无原路径Service/Zip/save/清理。
- **新owner冷JVM对照**：首PID5784实际owner失败收尾/release＋正常可写save，另新PID15968保留同一fixture/cache/tmp/sentinel，真实Service/list-equivalent metadata成功，未清cache/tmp。**未复现用户完整Minecraft关闭重启仍隐藏**，不等于排除所有缓存因素，也不再把纯内存lockedPaths说成跨已结束JVM延续。
- **合同及未覆盖项保持**：file-close请求后仅重试收尾，绝不重save，不套旧F1“二次重save成功”；恢复材料保留不代表save成功。同步save及表外callback仍可能阻塞；永久reader/close失败保守持锁，不强删lockedPaths。构造/startReplay/兼容取消/GuiHandler其它所有权缺口未修；真实全mod/264游戏、两录像渲染及sidebar、进退切模式/拖动/真实联机和完整重启症状仍待用户/Lead验收。
- [最终范围/保护](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4/final-protection.json>)、[最终源hash](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4/final-source-hashes.json>)、[相对本轮源前像完整patch含新Mixin](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4/actual-current-round-full-sources.patch>)和[D仅追加plan差异](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/D-verify-20261118-e618d4/actual-D-only.patch>)供Lead复核；567基线/六实现资源白名单、旧证据manifest和UI sha7cb45c379e36055ddaf81fc5e43f193901d6e1e37e6cd7fb08f2ec6a273563fa保持。**可交付：是，作为新构建隔离验证候选；不是已完成真游戏/用户故障验收。** D仅回报同plan绝对路径及报告，等待Lead复核，不自行goal完成、安装或提交。

### 20.10 Lead独立复核、最终交付与未解决边界

- Lead实际回读D完整REPORT/证据索引/原mc_gradle输出及20.9；认可其分层结论而不是拿分项数拼成“全游戏测试通过”。D全部阶段完成，无生产修补；A/B/C1/C2/D串行链到此结束，没有待启动的实施阶段。
- Lead在只写新 [Lead-final](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/Lead-final>) 子目录执行独立终验：相对原基线五既有源/资源改变＋一个新Mixin，562表外项hash未变；两语言仅精确四词组变化；UI源仍7cb45c379e36055ddaf81fc5e43f193901d6e1e37e6cd7fb08f2ec6a273563fa。两份用户原录像SHA256/size/mtime仍与20.8收据全部一致，未save/清理/改名原件。工具git diff --check通过，Git始终仅status/diff/log。
- 独立核对当前non-dev ZIP全部CRC、一次注册、两语言打包字节与源一致；实际final javap再次确认新hook身份门、packet/file close worker与请求接线、Handler.endReplay无本地await或直接ReplayFile.close，前序optional注册/core.runLater/默认UI初值逻辑仍在。未在D后改生产文件、重建或洗掉D失败/中断证据。
- 当前唯一交付 [reforgedplaymod-1.21.1-0.3.1.jar](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>) **17,626,514B**，SHA256 **3328e9958090a2d51c565eaea674cb19512af90c761a2f7d889a9aa2b843c42a**，与D最终收据一致。Lead [真实完整六文件patch](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/Lead-final/complete-approved-source.patch>) 包括untracked Mixin，非累计HEAD差异；[独立最终收据](<C:/Temp/reforgedplay-objective-A-20261118-6d9a3c/Lead-final/FINAL-RECEIPT.json>) 和final-artifact-javap.log保留核对层边界。构建原文36s、test NO-SOURCE及原诊断保持，不另编造JUnit/真实游戏收据。
- 功能交付覆盖：未改设置不写/只提交更改项（先前18.4已实现，本次原样保留并回归），指定词组更正；当前回放连接重复objective局部兼容；退出及EOF实际packet reader退休、保源关闭请求及真实Managed释放/重开。没有删除缓存/锁或禁用原编辑保存；失败后请求close即只重试收尾、不再重save的合同变化明确，恢复材料不是保存成功证明。
- **用户完整重启Minecraft仍隐藏的具体症状没有在隔离对照中复现，因此不能声称已完全解决或已排除缓存。** 新同cache/tmp冷JVM对照成功仅签该层。真实264全mod顺序、变换后handler线程重入、完整sidebar/画面保真与用户实例仍待替换包实测；原EXR不兼容、启动/构造/兼容取消等未授权所有权缺口仍保留。若更新后仍隐藏，应保留当次缓存/恢复材料，并将该次新日志另名提供，不先删缓存掩盖。
- 交付操作只声明当前build输出供用户关闭游戏后替换唯一旧JAR（不要并存）；不自动安装、commit或改游戏真实设置。此轮实现/隔离验证范围完成，后续真实游戏验收不冒充已完成。

## 21. 服务器回放团队 REMOVE 断连、单人“继续游戏”卡死：A 只读诊断及待批准最小范围（2026-11-18）

### 21.1 授权、时代、交付和证据边界

- **本节是新诊断/候选，不是新实现授权。** 本轮只追加本计划；不启动 B/C/D，不改 Java/资源/依赖/构建，不构建、不编译/运行仓库测试、不安装、不 Git 写、不触碰 `.whale-craft`、游戏设置、录像、cache/tmp/锁或旧证据。19–20 节实施与有限验证历史完整保留；其中 PASS 不提升为“服务器回放/单人卡死已解决”，也不据时间先后将此次卡死归咎旧六文件。
- 先回读 [构建](<build.gradle#L1-L11>)、[属性](<gradle.properties#L6-L20>)、[wrapper](<gradle/wrapper/gradle-wrapper.properties#L1-L7>)、[NeoForge 元数据](<src/main/resources/META-INF/neoforge.mods.toml>)及库/源码布局：**MC1.21.1 / NeoForge21.1.82 / Java21 / Architectury Loom1.7-SNAPSHOT / Gradle8.8 / Yarn1.21+build.1＋既有NeoForge patch1.21+build.4**。现代 Java21 既有 Loom 工程；不 scaffold、不迁移 ModDev、不升级映射/平台。报告实际 NeoForge21.1.264、Microsoft Java21.0.8；本机反汇编明确使用已安装 Oracle21.0.12.1，不把两者说成相同运行环境。
- 本 A 角色上级规则明确禁止读取 skills，故**没有加载 minecraft-java-build / minecraft-neoforge-mod**，没有冒称满足用户该子要求；缺失 API 直接核项目实际二进制＋官方/上游文档，不套用新模板。Lead 可在自己权限内补该记录。
- **证据文件限制如实披露：** 本 A 上级仅准写本计划，禁止另建任何文件/目录，因此用户指定的 `C:/Temp/reforgedplay-team-hang-A-20261118-204010` **本 A 未创建、未写入**。本轮 javap/stdout 原始返回在工具会话保留；关键输入、结果、纠错和精确偏移收录本节，不伪造一个 raw 日志路径。Lead 如需独立证据包，可由其权限另行导出该会话原文到指定新根，不能称本 A 已完成。旧证据未覆写；本轮未运行产生非零退出的命令，首轮 GUI 方法猜测未命中正确 callback 的输出见21.5保留，不删改成首次就正确。
- 没有访问原录制目录，也没有打开/复制/保存任何录像：新报告和静态代码已足够确定直接抛错点；包流生成原因及单人卡死需要另一层证据，不能为凑诊断遍历用户盘。

### 21.2 新报告能直接确认什么、不能确认什么

已逐项读取新 [disconnect 报告](<disconnect-2026-11-18_20.40.10-client.txt#L4-L38>)：

1. 时间字符串为报告原文 `2026-11-18 20:40:10`（时区未确认，不跨时区换算）；Render thread 的 `Scoreboard.removePlayerFromTeam` 抛 `IllegalStateException: Player is either on another team or not on any team. Cannot remove from team '9599_8b96'.`，调用方 `ClientPacketListener.handleSetPlayerTeamPacket`，输入 `clientbound/minecraft:set_player_team`。该异常进入 `onPacketError`，足以解释此次服务器录制回放1–3秒断连的**直接失败点**。
2. 报告目标类的 Mixin 清单包含现有 `Mixin_ReplaceDuplicateReplayObjective`；这仅证明此类的该 Mixin 被列入变换信息，不证明其 callback 在本次具体包执行，也不证明旧 objective 问题已彻底解决。此次不是同一个 objective 异常；不撤掉旧兼容，也不假定“旧改动造成新异常”。
3. 运行基础字节码核实后可推断：抛错来自**移除成员**分支，而非整队 REMOVE。该分支对应 TeamS2CPacket 的 packetType4；整队删除是packetType1，二者不能混为一谈。失败当时该成员对目标 Team 对象不匹配（可能 null，也可能另一队）；报告没有成员名字、实际 current team、完整 packet payload/索引/时间戳，**不能确定是哪一种**。
4. **尚未确认生成源：** 未新审计真实录像团队包，不知道文件本身已有过期/重复REMOVE、快进/重启状态差异、录制快照/包序问题、Quick重建、服务器/其他mod变换哪一项导致不匹配。此处不得套用19节“重复objective确实写在文件中”的结论到team。
5. 报告的 [内存/JVM信息](<disconnect-2026-11-18_20.40.10-client.txt#L62-L105>) 为 `-Xmx1536m`、`java.io.tmpdir`空间探测ERR；这只是这份服务器断连报告的环境事实，**没有OOM/GC停顿或单人磁盘故障证据**，不以此代替单人卡死根因。Dynamic Lighting 附加段也不是根因证据。

### 21.3 本轮实际 JAR/API 核实与可复查摘要（不是新测试 PASS）

工具固定 `C:/Program Files/Java/jdk-21.0.12.1/bin/javap.exe`，`--system C:/Program Files/Java/jdk-21.0.12.1`；实测 `javap -version` 原文 `21.0.12.1`。只执行 `-p -c` / `-p -v` /签名读取，未运行生产方法。输入是：

- **N 编译 named**：[实际 MC1.21.1/Neo82 JAR](<C:/Users/zhenz/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/neoforge-21.1.82-minecraft-merged/1.21.1-loom.mappings.1_21_1.layered+hash.426444933-v2-neoforge-21.1.82/neoforge-21.1.82-minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.426444933-v2-neoforge-21.1.82.jar>)。
- **R 用户实例基础 srg/Mojang**：[实际 client 基础 JAR](<D:/Jerry's/MCJE/.minecraft/libraries/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar>)。这是用户实例基础类，不是运行时 Neo264＋全部mod变换后的内存导出。
- **S ReplayStudio**：[项目 vendored JAR](<libs/ReplayStudio-master-SNAPSHOT-slim.jar>)，本轮重新 SHA256=`e034b0a39cc906ba38c401a13c09bf6fca5512ecb658428380994f71b7d48250`。
- **E 已有 MixinExtras0.3.5**：[本机依赖 JAR](<C:/Users/zhenz/.gradle/caches/modules-2/files-2.1/io.github.llamalad7/mixinextras-neoforge/0.3.5/ef3ffcf309dae656517b2f120189b1c9b21737dd/mixinextras-neoforge-0.3.5.jar>)；未下载/新增依赖。

| 核实对象 | 本轮实际字节码/签名结果 |
| --- | --- |
| N `ClientPlayNetworkHandler.onTeam(TeamS2CPacket):void` | 偏移6先 `NetworkThreadUtils.forceMainThread`；9–88获得/新建目标队及未知队警告早退；89–103正常应用team属性；106–167成员ADD；170–221成员REMOVE循环；**218唯一** `Scoreboard.removeScoreHolderFromTeam(String,Team):void`；224–236保留整队REMOVE。 |
| R `ClientPacketListener.handleSetPlayerTeamPacket(ClientboundSetPlayerTeamPacket):void` | 同样偏移6ensure、218唯一 `Scoreboard.removePlayerFromTeam(String,PlayerTeam):void`、236 `removePlayerTeam(PlayerTeam):void`；局部变量槽与N有差异，方案不捕获locals。 |
| N/R Scoreboard两参remove | 原文摘要：`0 aload_0; 1 aload_1; 2 invokevirtual getScoreHolderTeam/getPlayersTeam; 5 aload_2; 6 if_acmpeq 26; 9 new IllegalStateException; ... 25 athrow`；**身份一致才**在26后删map/目标队成员表。不能用队名字符串相等替代 `==`。 |
| N `TeamS2CPacket.getPlayerListOperation()` | packetType0/3→ADD，4→REMOVE，其余null。`getTeamOperation()`仅0→ADD、1→REMOVE，其余null。 |
| E `WrapOperation` / `Operation<R>` | 实际存在 `method():String[]`、`at():At[]`、`remap():boolean`、`require()/expect()/allow():int`；`Operation<R>.call(Object...):R`。候选void调用的签名为 `(Scoreboard,String,Team,Operation<Void>):void`，由上游约定和本地API共同确认，不凭新版文档捏造。 |
| S `ReplayInputStream.readPacket()` / `close()` | readPacket偏移1调用 `fillBuffer`、8 Queue.poll；fillBuffer偏移16/24从真实InputStream读两个int，68 writeBytes(InputStream,length)，425 ViaVersion convert；close偏移4直接调用 underlying `InputStream.close()`。API不承诺非阻塞或可中断，不能假设只关flag即可返回。 |

网络核实日期2026-11-18，均实际 fetch 正文：

- [Yarn1.21+build.1 Scoreboard](<https://maven.fabricmc.net/docs/yarn-1.21+build.1/net/minecraft/scoreboard/Scoreboard.html>)：公开 `getScoreHolderTeam(String):@Nullable Team`、`removeScoreHolderFromTeam(String,Team):void`、`getTeam(String)`；这是配置的Yarn基线网页，**1.21.1/Neo82精确存在性以上述N为准**。
- [MixinExtras 上游 WrapOperation 文档](<https://github.com/LlamaLad7/MixinExtras/wiki/WrapOperation>)：实例调用收到receiver、原参数及Operation，调用original时传receiver和原参数；允许有意不调用以bypass局部操作，多wrapper可链式组合，优于硬Redirect的独占替换。网页含新版其它能力，本方案只取本机0.3.5已核实的INVOKE wrap，不使用Expression/WrapMethod等未核能力。
- [Oracle JDK21 jcmd 官方手册](<https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html>)：同机器/有效用户，`Thread.print -l`打印线程及并发锁，impact Medium；不声称零扰动，也不将“未检测到Java monitor deadlock”视为无逻辑等待。

本轮只读指纹收据：当前 [non-dev JAR](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>)仍 **17,626,514B / SHA256 `3328e9958090a2d51c565eaea674cb19512af90c761a2f7d889a9aa2b843c42a`**；并非本轮新构建，也未核用户实际安装文件hash。源/报告SHA256如下，后续实施以此再冻结而非直接引用旧hash：

| 输入 | SHA256 |
| --- | --- |
| [FullReplaySender](<src/main/java/com/replaymod/replay/FullReplaySender.java>) | `0d79e9af57189b39b2454c244b2f29deca1e5af8baec7a8baa86fa058bbd9b6e` |
| [ReplayHandler](<src/main/java/com/replaymod/replay/ReplayHandler.java>) | `a417d29b5379cf89a64d84e158a66e2bae2bdaa18ad77febbf72919e9ecd7c11` |
| [原 objective Mixin](<src/main/java/com/replaymod/replay/mixin/Mixin_ReplaceDuplicateReplayObjective.java>) | `862cc4300b0c073ac2293c96c562fdf081569d23f265ac25936ebb4be379c521` |
| [新 disconnect 报告](<disconnect-2026-11-18_20.40.10-client.txt>) | `0a9b83013b9e8b8316f2a0825e7afac20c3ac46910ed902eaf855d606650f303` |

### 21.4 首选候选：仅当前 replay 连接、仅不匹配成员 REMOVE 的局部兼容（待用户批准）

**不改 Scoreboard 全局行为，不跳整个Team包，不catch IllegalStateException，不把set_player_team加入BAD_PACKETS，不修写录像。** 建议只增加一个client Mixin，wrap N的 `onTeam(Lnet/minecraft/network/packet/s2c/play/TeamS2CPacket;)V` 中唯一 `Lnet/minecraft/scoreboard/Scoreboard;removeScoreHolderFromTeam(Ljava/lang/String;Lnet/minecraft/scoreboard/Team;)V` 调用。

1. 首选现有 MixinExtras0.3.5 `@WrapOperation`，`require=1/expect=1/allow=1`、保持remap；无需新增shadow scoreboard、无需捕获local/重写packet。其调用点天然处于forceMainThread正常返回之后且只在成员REMOVE分支，不额外HEAD取消或重复调度。若实际变换不命中/冲突/初始化不可用，**停报**，不悄悄改require0、加依赖或全局吞异常。硬Redirect只作为需另报的备选，不能未经核验切换。
2. 在新Mixin内部使用与 [现有 objective 身份门](<src/main/java/com/replaymod/replay/mixin/Mixin_ReplaceDuplicateReplayObjective.java#L59-L90>)等价的严格判定（可复制窄私有helper，不顺便重构旧文件）：当前MC主线程；mc当前handler===this；connection当前listener===this；现有NetworkManagerAccessor取channel；open/active EmbeddedChannel；当前ReplayModReplay/ReplayHandler存在；pipeline的packet_handler===connection、ReplayHandler.PACKET_HANDLER_NAME===当前active sender（Full或Quick）。只见“有ReplayHandler”或EmbeddedChannel不够，拒绝旧handler/旧sender/真实网络连接。
3. wrap收到**实际receiver scoreboard、成员String、目标Team**。当且仅当身份门通过且 `receiver.getScoreHolderTeam(member) != targetTeam` 时，略过**这一次成员移除**，不改任一map、不调用clearTeam、不移除其当前另一队、不尝试“先加回目标再移除”、不补造队。除此之外 `original.call(receiver, member, targetTeam)` **恰一次**，传播其原异常，不catch。查的是原调用同一board，而非可能已切换的global world scoreboard。
4. 同一包其他成员继续循环：当前属于目标队的成员正常移除；已无队/在另一队的成员保持。team属性、CREATE、UPDATE、成员ADD、unknown team原warn/return、整队REMOVE及包后续动作全部原样；不复制目标team集合“清残留”。特殊字串（空格/颜色码/sidebar holder）按原String，不当作在线玩家名过滤。
5. 兼容只定义“这个过期/不匹配REMOVE不终止回放”，**不保证团队状态绝对保真或消除生成源**。服务器/真实单人游戏连接仍走原版异常语义。两参remove其它调用点（包括add内清旧队）不受此wrap影响。
6. 当前工程没有其他生产WrapOperation使用，[历史 MixinExtrasInit](<src/main/java/com/replaymod/core/MixinExtrasInit.java>)只是注释。已有E二进制不等于实际Neo264完整launch成功；后续D须核loader自动初始化/最终产物实际可见性，不为此在本轮改bootstrap或依赖。保持旧objective Mixin和前序optional/UI/EOF/异步close全部原字节，除非另获带证据的明确扩围。

### 21.5 单人“继续游戏”卡死：静态风险清单，不冒充真实线程诊断

**当前没有卡死时线程dump、单人样本/时间点、CPU趋势或对应新日志，不能确认死锁、不可中断I/O、渲染busy-loop、暂停误认、内存压力，不能确定由旧修改引入。** 本轮没有启动或attach游戏。

- **先区分按钮。** [GuiHandler](<src/main/java/com/replaymod/replay/handler/GuiHandler.java#L76-L90>)打开GameMenuScreen时主动 `setReplaySpeed(0)`；[继续/暂停overlay](<src/main/java/com/replaymod/replay/gui/overlay/GuiReplayOverlay.java#L101-L113>)另有play按钮，暂停时才设非零speed。N的GameMenuScreen `RETURN_TO_GAME_TEXT`绑定bootstrap0→**method_19845(ButtonWidget)**，实测callback偏移5 `MinecraftClient.setScreen(null)`、15 `Mouse.lockCursor()`，没有直接endReplay/save/切sync/play调用。因此“Esc菜单继续游戏”不等同overlay“恢复回放”；若镜头/UI仍响应而时间不走，首先记录是否只是回放仍暂停，不冒称硬卡死。
- **取证纠错保留：** 首轮静态筛选曾查看 `method_19836(ButtonWidget)`，实际输出为新建NeoForge ModListScreen并setScreen，**不是**继续按钮；后续通过`javap -p -v` BootstrapMethods解析到19845，再取其真实bytecode，不按猜测方法名给结论。所有命令退出未报错，属于目标选择纠正而非生产故障，不编造不存在的失败raw文件。
- **Sender monitor跨慢动作（静态事实）。** [异步循环](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1267-L1421>)在sender监视器内读真实ReplayInputStream、暂停sleep、时间戳sleep、autoRead等待和EOF close，并可 [runSync pause/restart](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1770-L1806>)。实际Studio `readPacket→fillBuffer→InputStream/解压/转换`、`close→InputStream.close`没有非阻塞保证。锁持有时间风险存在，但“worker持锁＋Render thread同时等待同锁/依赖”仍要dump；正常暂停worker sleep本身不能判死锁。
- **sync切换/调度风险（静态事实）。** [setSyncModeAndWait](<src/main/java/com/replaymod/replay/FullReplaySender.java#L367-L380>)持续等原worker alive并泵MC/ReplayMod任务；[sendPacketsTill](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1507-L1549>)等sync任务完成也泵队列。异步时间戳sleep可很长；autoRead循环1337–1340只查terminal而不是普通mode/epoch停止。若卡在状态切换且autoRead不恢复，可能长等；**目前没有证据这两种方法由“继续”路径实际进入**，不擅改bundle/网络状态契约。
- **runSync并非无限Future.get。** [SchedulerImpl](<src/main/java/com/replaymod/core/versions/scheduler/SchedulerImpl.java#L20-L28>)非MC线程提交ReplayMod自有executor后 `get(30,TimeUnit.SECONDS)`；主线程直接执行。分析要同时看Render任务泵/worker/30秒timeout日志。不能把有界等待直接描述成已证实永久死锁，或删掉同步调度“修卡死”。
- **退出异步close不等于所有退出无阻塞。** [Handler.endReplay](<src/main/java/com/replaymod/replay/ReplayHandler.java#L278-L385>)仍同步执行closing callback、stop/terminate、`replayFile.save()`、mc.disconnect及closed callback；[TransactionalReplayFile](<src/main/java/com/replaymod/core/files/TransactionalReplayFile.java#L201-L225>)的save持adapter监视器publish真实I/O，真实stream read/close也持同adapter监视器。 [packet/file close worker](<src/main/java/com/replaymod/replay/FullReplaySender.java#L537-L622>)先等sender监视器退休reader，再锁外请求file.close并检查Managed receipt。若实际“继续”触发断连/重入退出，这些是查栈候选，**不是已证实该点击进入endReplay，更不是close worker一定阻塞Render**。不回退保源/锁引用合同来换表面不挂。
- **非锁高CPU候选。** [Full.channelRead](<src/main/java/com/replaymod/replay/FullReplaySender.java#L677-L701>)安排chunk/light update while循环，Render若重复停在它们或其他mod渲染/native，可出现无异常的busy-loop/慢任务。新报告列出的Connectivity/ModernFix/ModernUI/Ixeris/Iris/Sodium等仅是环境，不据名字定责、不本轮禁用/升级。
- [Full.channelRead已有catch](<src/main/java/com/replaymod/replay/FullReplaySender.java#L653-L708>)不能保证吞掉由forceMainThread排到Render task后发生的错误；新Team异常正是Render task路径。此次不扩大catch，也不把“无错误日志”当作无阻塞/无协议错的证明。

### 21.6 待确认最小文件范围与 B/C/D 所有权（当前全部未批准实施）

| 文件/范围 | B skeleton（获批后） | C content（获批后） | D review（获批后） |
| --- | --- | --- | --- |
| 新 `src/main/java/com/replaymod/replay/mixin/Mixin_IgnoreMismatchedReplayTeamRemoval.java`（候选名，尚未创建） | 核精确onTeam/INVOKE及0.3.5可见性，建最小wrap骨架；空hook不算完成 | 严格连接身份门＋不匹配仅skip一次remove，其余original一次；不catch/不修team | named/remap/user-base真实变换、实际变换后方法执行、身份/行为矩阵 |
| [replay Mixin清单](<src/main/resources/mixins.replay.replaymod.json#L7-L14>) | client数组新增上述一项、恰一次 | 不再扩大注册 | 检查最终打包/required/mapping；不改metadata/plugin |
| [本计划](<plan.md>) | 不由B覆盖历史 | 不由C覆盖历史 | Lead记实际结果/失败/局限；A本轮仅追加21节 |
| [原objective Mixin](<src/main/java/com/replaymod/replay/mixin/Mixin_ReplaceDuplicateReplayObjective.java>)、[FullSender](<src/main/java/com/replaymod/replay/FullReplaySender.java>)、[Handler](<src/main/java/com/replaymod/replay/ReplayHandler.java>) | **只读保护，无骨架变更** | **只读保护，无卡死预防性修补** | 回归旧功能/静态审查；发现新小缺陷也先报，不自动写 |
| [Scheduler](<src/main/java/com/replaymod/core/versions/scheduler/SchedulerImpl.java>)、[GUI handler](<src/main/java/com/replaymod/replay/handler/GuiHandler.java>)、[overlay](<src/main/java/com/replaymod/replay/gui/overlay/GuiReplayOverlay.java>)、[adapter](<src/main/java/com/replaymod/core/files/TransactionalReplayFile.java>)、[Managed](<src/main/java/com/replaymod/core/files/ManagedReplayFile.java>)及其他生产/资源/构建 | 全只读 | 全只读 | 卡死证据/回归读取，不升级依赖、不写仓库测试 |

**建议拆成两个独立决策：** (T) 用户是否批准上述“两实现/资源文件”team-only候选及隔离验证/一次build；(H) 单人卡死先提供线程证据，再由Lead确认根因和另列最小scope。不得用T授权顺带改Full/Handler/GUI或宣布H已修。当前T/H均没有本轮新实现授权。

### 21.7 后续验证矩阵和构建停点（本轮未执行）

| 层级/场景 | 必须观察的结果/停点 |
| --- | --- |
| 原版对照 | 真实Scoreboard：目标A，成员无队或已属B时原两参remove确实抛同类错误；不以手写复制实现代测。 |
| 正常/过期REMOVE | 当前replay：member属于A→original恰一次正常移除；无队→零调用/零状态变；已属B→零调用、B的映射和成员集合均保持；同名不同Team对象仍按身份不匹配，不用equals或name掩盖。 |
| 混合成员/重复成员 | 同一packet含合法、不合法、重复holder：合法后续成员仍处理；第一次合法删除后重复项只skip，不能取消整包、遗漏后续动作或改写packet。 |
| 其余team操作 | CREATE/UPDATE的display/prefix/suffix/flags/color/collision/name visibility、成员ADD跨队迁移、unknown队warn/return、整队REMOVE均原版行为；旧objective/scores/display不受新hook影响。 |
| replay强归属负例 | 真实SocketChannel联机/单人正常连接、无ReplayHandler、旧handler/listener、关闭EmbeddedChannel、旧pipeline sender/connection、Full↔Quick及重启旧channel均必须fail-closed至original；负例不匹配仍抛原异常，不能靠helper返回false后的catch吞掉。 |
| 主线程/真实变换 | N named82与最终remap后Mojang82及用户实例基础R各核唯一218调用、ensure先行、require/expect/allow1；**执行变换后onTeam**的off-thread调度→主线程重入/混合packet流程，不以直接调用callback/stub身份矩阵代替。完整Neo264＋全mod launch另外标未测。 |
| 原功能回归 | 旧objective身份门/CREATE退旧状态/后继恢复；Full/Quick、EOF继续/倒退/拖动、退出一次save＋实际close/Managed release与保源失败路径；UI/optional保持指纹。不得拿旧D的分项数充本次PASS。 |
| 实际录像（需后续允许读取样本） | 只读ZIP/纯流或独立副本审计team历史：队/成员、mode、timestamp/index、重配置/Join边界；原件hash/size/mtime前后不变，绝不对原路径构造可写Service/ZipReplayFile、不save/repair/cache清理。team-only通过不等于完整画面/颜色/sidebar保真。 |
| 卡死分层 | 先区分“时间暂停但UI可用”与进程真实无响应；三份dump比对Render/worker锁owner/队列/调用点进展。没有dump和可复现步骤时H保持UNVERIFIED，不实施线程修补。 |

- 若用户之后明确批准T实施和验证：B→C→D串行，证据应在Lead实际新建的上述新根/唯一子根，不复用旧脚本输出，不安装包/依赖，不生成仓库测试；失败原文/退出码/timeout保留，后续尝试另编号，不能删失败/重写为PASS。
- D唯一允许的最终构建候选是 `mc_gradle(projectDir="C:/Users/zhenz/Desktop/ReForgedPlay-1.21", task="build", args=["-Djava.io.tmpdir=C:\\Temp", "--console=plain"], timeoutMs=1200000)`；**当前不执行**。不额外tasks/help/clean/refresh、不跳test、不shell wrapper。先核继承Gradle JVM为Java21，缺JDK/解析失败就停报，不升级配置或手工改环境绕过。test NO-SOURCE不当JUnit通过。
- D需读最终non-dev产物大小/SHA、唯一client注册、remap descriptor、依赖实际可见性及保护文件指纹；真游戏回归需用户独立样本环境批准，不替换用户mod、不写原录像。跨21.1.82→264与全mod变换顺序必须作为独立风险列明。

### 21.8 需要用户补充的单人卡死证据与最后停点

1. **按钮和步骤：** 是Esc菜单“继续游戏”还是overlay播放三角？加载单人回放第几秒/是否到EOF、是否已拖动/Quick、是否退过回放；镜头/鼠标/菜单/音乐是否仍响应、进程CPU/内存/磁盘大致变化，等30–60秒是否恢复；提供卡死前后新命名日志、截图或短视频、所用mod JAR hash/游戏Java版本。不要求重录/覆盖唯一原件。
2. **最重要：同一冻结进程的三次完整线程dump，相隔5–10秒，带精确PID、各次时间和时区。** 不先强退/重启Minecraft，不删cache/tmp/锁。使用同用户的已有Java21诊断工具，对已核对的**单一Minecraft PID**运行 `jcmd <PID> Thread.print -l` 三次；每次stdout＋stderr及失败退出/timeout保存为不同新文件，目录交由Lead/用户建立，非本A已保存。不要用PID0/模糊主类批量attach；不要运行GC.run、heap_dump、JFR.start或改变JVM flags。手册标注Medium影响，可短暂停顿，并非完全零扰动；若attach失败/目标无响应，保留失败原文，不安装新JDK、不反复无限attach，转交Lead决定下一种取证。
3. dump应覆盖 `Render thread`、`replaymod-async-sender`、`replaymod-sync-sender`、`replaymod-packet-input-close`、集成Server thread、ForkJoin/resource reload/渲染相关线程；看实际stack/locked/waiting monitor地址形成等待关系。三份相同busy-loop、一次正常pause sleep、Future的30秒有界等待、真正monitor循环和native/GPU阻塞须分别定性；无Java deadlock提示不排除逻辑卡住。
4. **准确未确认项：** 哪名holder/当前null还是另一队、team错序生成源、实际安装JAR是否等于本机产物、完整264全mod WrapOperation启动/变换可用性、新兼容行为与画面保真、卡死按钮及真实阻塞栈，全部仍待补证。未声称旧问题全解决或此次卡死已定位。
5. **本轮终点：仅21节落盘，T为候选、H为取证请求；无新实现/构建/测试/游戏attach/录像写入。** Lead收到A结果后先核对本计划和工具原文，再问用户是否批准T以及如何提供H证据；不自动启动B。

### 21.9 Lead与只读packet-audit补充：真实新日志和单人错流证据，更新待批准范围

**本节在A结束且Lead回读21.1–21.8后追加；前述“无本轮日志/单人样本”“实际安装hash未核”“H仅等dump、Full必须只读”仅描述A当时的证据边界，现由本节明确更新。仍没有新实施授权，不启动B/C/D、不改生产/资源。** Lead本轮已实际加载minecraft-java-build与minecraft-neoforge-mod；A角色不加载技能的限制没有被伪称满足。

#### 21.9.1 独立真实输入，不误用旧日志

- 工作区 [latest.log](<latest.log>) 仍为11/17旧日志，273734B、SHA256 `e3bb8a418f3c8c4004c32afaec92bb3efeab40ece560b06c21bfde80fd226428`；工作区logs/latest.log亦旧测试输出，两者均不是此次卡死记录。新disconnect报告仍21.2的20:40:10团队异常。
- Lead以Windows原生Get-Item确认游戏logs是SymbolicLink指向 `R:/logs`，在工具不遍历链接的拒绝之后定位**确切目标**读取，未改ACL/链接。实际 [本次日志](<R:/logs/latest.log>) 为14,412,098B、71,288行、GB18030，SHA256 `9bae09c9239575f432dbb56b504b273bb63f80b01f00cd67824ca537c2c3b8bd`，末时间20:42:16，原件读前后mtime/大小稳定。独立 [解码副本](<C:/Temp/reforgedplay-team-hang-lead-20261118-204010/log-0-decoded-utf8.txt>) 严格转码无替代，原件未改；[原件收据](<C:/Temp/reforgedplay-team-hang-lead-20261118-204010/log-receipts.json>)、[Lead事实/工装问题](<C:/Temp/reforgedplay-team-hang-lead-20261118-204010/LEAD-FINDINGS.md>)均保留。
- 本机当时只有Gradle PID19172、没有Minecraft进程；没有attach或获得卡死时dump。用户 [实际安装包](<D:/Jerry's/MCJE/.minecraft/versions/1.21.1-NeoForge_21.1.264/mods/reforgedplaymod-1.21.1-0.3.1.jar>) 与 [交付包](<build/libs/reforgedplaymod-1.21.1-0.3.1.jar>) 重算hash均为 `3328e9958090a2d51c565eaea674cb19512af90c761a2f7d889a9aa2b843c42a`，排除“用户没换到该包”这一项，不排除全mod兼容问题。
- 两个新样本只读ZIP/包字节，不在原路径实例化可写ReplayFile：服务器 [20_39_23](<D:/Jerry's/MCJE/.minecraft/versions/1.21.1-NeoForge_21.1.264/replay_recordings/2026_11_18_20_39_23.mcpr>)；单人 [20_41_26](<D:/Jerry's/MCJE/.minecraft/versions/1.21.1-NeoForge_21.1.264/replay_recordings/2026_11_18_20_41_26.mcpr>)。后者singleplayer=true、duration6309ms、protocol767、ZIP160814B、packet流1,583,791B、SHA256 `206476db1ca5dd4c1c7b9d75013d726dcdc3283ecc7ec04f08b127c804bbf114`。[session-summary](<C:/Temp/reforgedplay-team-hang-lead-20261118-204010/session-summary.json>)记录两者原件hash/size/mtime及元数据。ZIP metadata为GB18030，本轮只按实际编码读取，未改变编码或生产JSON实现。

#### 21.9.2 已确证的错流特征与尚未确证的整个卡死因果

- 新日志1419行20:41:45.630打开上述单人样本；1504–1508行20:41:54.956真实堆栈显示 `GuiReplayOverlay$7.run -> ReplayHandler.doJump:913 -> FullReplaySender.sendPacketsTill:1535 -> executeTaskQueue`，证明至少有一次时间线跳转。该处voicechat的EmbeddedSocketAddress异常不等于此次错流首错，也不据此给voicechat定责。
- **首个解码错误**在1538行20:41:58.670：PLAY add_entity多726B；1557行ticking_step多877B；1595行sound_entity枚举115/10越界。之后把NUL分隔quickskin/voicechat通道注册串当成ResourceLocation。至20:42:16.235共**1667条顶层DecoderException**，extra最大1,155,910B。不能仅以“无独立crash报告”推断无日志错误。
- 只读packet-audit在新 [证据脚本根](<C:/Temp/reforgedplay-team-hang-packet-audit-20261118>) 核对原帧和实际MC基础Jar构造器/PLAY ID：单人4365帧、末6309ms，边界完整、无时间倒退；服务器99610帧、末20320ms，边界完整。**这仅是帧层完整，不是所有payload语义正确或可播证明。** 单人#0是LOGIN success28B；#1 offset36是CONFIG `minecraft:unregister`779B；#2 offset823 CONFIG `minecraft:register`153B。
- 把这三个开头包错投PLAY decoder：#0的id02恰被add_experience_orb读28B，暂不报错；#1 id01被add_entity恰读53B，所以779−53=**726**；余量首ASCII r=0x72，追加下一153B后当ticking_step读2B，726+153−2=**877**；其后ASCII g=0x67被当sound_entity，i=105与s=115精确解释enum115/10。**前三错和原始LOGIN/CONFIG流误交PLAY逐字节吻合，已不只是泛泛怀疑死锁/内存/本地编码损坏。** [first_fault.py](<C:/Temp/reforgedplay-team-hang-packet-audit-20261118/first_fault.py>) 和 [bytecode.py](<C:/Temp/reforgedplay-team-hang-packet-audit-20261118/bytecode.py>)是该层证据，不冒称已运行变换后264游戏。
- 生产源码存在具体可达缺陷链：[同步EOF](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1626-L1637>)关闭真实reader后 `replayIn=null`，没有记录已耗尽/需要协议重启，保留target timestamp；[doJump结束](<src/main/java/com/replaymod/replay/ReplayHandler.java#L911-L917>)启用async；[异步入口](<src/main/java/com/replaymod/replay/FullReplaySender.java#L1271-L1279>)见null就从LOGIN读取文件开头，**没有先重建仍在PLAY态的通道**。原async自然EOF1361+本来有pause→等待→restart边界，但同步EOF转async没接入该合同。
- Lead已回读packet-audit最终 [证据报告脚本](<C:/Temp/reforgedplay-team-hang-packet-audit-20261118/evidence_report.py>) 和first_fault脚本。队员重算两原件size/mtime/hash与Lead基准一致（服务器SHA256 `45868993536906e999afd48766c05ff1d24b8fccd3d4e1edfe264a261fd5f688`）。另一个同类可达入口是async EOF已close/null、暂停等待中被mode/epoch切断，尚未走正常restart就进入后继worker；H须一并覆盖这种EOF交接，不能只补同步catch。队员建议首fatal decode隔离防止累积是独立防御建议，**本候选未授权改ReplayHandler通用exceptionCaught/所有异常策略**；不能趁本轮顺手扩大吞错/断连行为。
- 目前没有运行时EOF状态探针和卡死dump，因此**“本次入口必定就是sync EOF”与“全部UI无响应唯一由此造成”仍未最终签证**。字节级错流与源码可达缺陷已经足以提出定向H修复；不必等用户重新撞卡死才承认此缺陷。不以异常风暴代替真实锁关系证据；若修后仍真无响应，再抓同一进程间隔dump。

#### 21.9.3 更新后的最小候选范围：T＋H一起确认，批准后连续B→C→D

1. **T团队兼容维持21.4原方案**：新replay-only成员REMOVE局部wrap与client注册；只忽略不匹配成员的这一次remove，正确成员及同包其它动作继续，真实服务器/单人非回放连接仍原版。未知holder当前在哪队及团队包生成源仍待审计，不以原流帧完整冒称已知生成原因。
2. **H新增允许候选仅为 [FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>) 的EOF→模式/异步接续状态与协议边界修复（含同步EOF和异步EOF暂停被切换）。** 分清“尚未打开”与“已经耗尽”：实际耗尽流仍必须真实关闭；同步EOF后的async不可在旧PLAY通道直接重读LOGIN。保留末帧/暂停与一次恢复播放的原产品语义；需要从头时必须先完整重启连接LOGIN→CONFIG→PLAY，然后才从头送包。具体状态表示由B/C按实际代码制定，不强塞简单 `replayIn != null` 判断或在切async时无条件重启。
3. 必须保留：普通未耗尽mode/epoch交接合法prefetch、时间戳/快进/倒退、Quick Full互转、异步自然EOF暂停再继续、terminal/代际/lease与真实close/release/保源合同。不得回退“关实际reader”以避免EOF，不持旧closed流假装仍可读，不重新标记旧字节成新代际，不在暂停或末尾无限重开/自动循环；不吞DecoderException、强行把CONFIG包当PLAY跳过，或以只停止刷日志冒充恢复正确播放。
4. **本候选总实现/资源scope：** 新team Mixin、现有replay Mixin清单、FullReplaySender，共三项，另本计划记录。ReplayHandler、GUI、Scheduler、录制编码、adapter/Service/Workspace、旧objective Mixin、UI/语言/optional、依赖/构建/配置/仓库测试/原录像全部只读保护。若证据表明必须改scope外才能正确修复，先报精确原因，不顺手扩围。新文件尚未创建；本轮没有改这三项。
5. 原21.7 T验证矩阵保留；H额外验证**真实生产方法与真实Netty解码管线组合**：同步读至/越过EOF再async，暂停末帧不重开，恢复一次只产生一次完整协议重启；正好末尾、短单人样本、拖到末尾/越过、反复拖回开头/中间、自然async EOF、正常未耗尽prefetch交接、Full/Quick、终止竞态与释放均要覆盖。精确检查发包状态与payload读尽，不能只用stub decoder或flag计数签通过。原两份新样本只读/隔离副本重放对照，并前后hash/size/mtime保护。
6. 改前冻结本轮源/资源/原件指纹并取真实本轮前像diff；B/C按片回报真实差异，D在现有固定依赖上mc_gradle执行21.7指定build参数，保留失败/timeout；禁止包安装、Git写、改仓库测试或原录像/缓存。最终交付仍区分真实管线隔离层、264全mod游戏层与用户实际卡死验收，不再把旧D测试数当本轮通过。
7. **向用户仅确认两点：** 是否批准T＋H上述三项实现/资源的定向修复与隔离验证/构建；“继续播放”具体是左上角播放三角、ESC返回游戏还是路径播放，之前是否拖到录像末尾。日志已证实至少一次时间轴跳转，不再让用户重复提供已找到的日志/录像或hash；游戏已结束，不要求现在给不存在的卡死dump。

本节结论：两个新问题都有定向方案；团队REMOVE直接异常已确认，单人从头LOGIN/CONFIG错投PLAY的字节特征与可达EOF接续缺陷已定位，**不冒称整个卡死最终唯一根因/已经修复**。当前只读诊断＋计划落盘，待用户确认；无新构建或新的可安装修复包。

### 21.10 用户批准、实施冻结与阶段契约

- 用户对上一回复“确认按此方案修吗”回答“嗯”，现按**21.9.3的三项实现/资源范围**执行T＋H，逐阶段B→C→D连续推进，无需再逐小步批准；不扩围至通用异常隔离/ReplayHandler/GUI/配置/依赖/测试。用户尚未回答按钮类别/完整步骤，不代猜；证据边界和真实全游戏卡死仍未完全签证。
- Lead本轮重新加载两技能并回读构建/属性/wrapper/libs与21.9；固定MC1.21.1、Neo21.1.82（用户264）、Java21、Loom1.7、Gradle8.8，不scaffold既有项目，不升级。获批后新证据总根为 [本轮实施证据](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92>)；[baseline.json](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/baseline.json>)冻结567源码/资源/构建/libs及报告项、两份新录像与实际安装旧包的hash/size/mtime。before子树有当前两既有实现文件及计划前像，新Mixin确认不存在。不是累计HEAD diff；不覆盖前轮证据。
- **唯一实施白名单：** 新 `src/main/java/com/replaymod/replay/mixin/Mixin_IgnoreMismatchedReplayTeamRemoval.java`、既有 [replay Mixin清单](<src/main/resources/mixins.replay.replaymod.json>)、[FullReplaySender.java](<src/main/java/com/replaymod/replay/FullReplaySender.java>)；另本计划仅追加。其余源/资源/配置/构建/依赖/仓库测试/.env/真实游戏/录像/锁/cache/.whale-craft全部只读保护，无Git写/装包/自动安装。
- **B现在只做骨架：** 读取21.4、21.7、21.9及本节，核精确API并建team wrap骨架＋恰一次client注册；骨架先调用original（不得把空hook当功能完成），FullSender此阶段只读，EOF状态实现留C。不得创建/更改仓库测试；测试夹具/收据仅在本轮新B子根（若角色禁止外部文件则如实把原工具摘要追加计划，不能谎报路径）。B不构建，不启动C。
- **C分两片串行，Lead每片回读后再下片：** C1仅把team骨架补齐严格replay归属门/单次不匹配REMOVE语义，FullSender保持；C2仅FullSender EOF明确耗尽状态/统一协议重启边界，覆盖sync EOF与async EOF暂停中断，保留非EOF预读和close/release门。每片先读引用/API实际Jar，真实diff＋允许的新根外部验证收据，不能修改仓库测试；不跑Gradle，不碰前片冻结源，不自行启动下一片。
- **D接最终组合：** 回读C结果与21.7/21.9矩阵，按指定一次mc_gradle build任务/参数/timeout执行，允许三白名单内明确小修（改后实际重新验证；必须重构建时说明原因，不无谓重复build）。构建不得shell wrapper或升级依赖。隔离新夹具执行完整生产方法＋真实Netty/MC字节码变换与状态验证，声明所有MC/GUI/应用边界，不能把手写decoder/stub flags替代要求的真实协议层。当前真实用户游戏不启动、不安装。
- 正常pause/EOF保持末帧，继续一次才做一次完整LOGIN→CONFIG→PLAY重启；无条件模式切换重启、旧PLAY通道重开LOGIN、把旧prefetch重标新代际、放弃真实reader close或fake-release均禁止。T只跳过当前回放连接不匹配成员的一次remove，其他操作照常；live负例走original并保留异常。
- 验证失败/超时保留原文，修工装另编号，不篡改旧收据；原件只读ZIP/纯流或隔离副本，不在原路径构造可写ReplayFile，不清cache/tmp/锁或恢复材料。Lead最终检查567保护项中白名单外hash、新增文件列表和三输入hash/mtime，并交当前新JAR、真实三文件diff及独立报告。实际264全mod启动、完整视觉保真、无线程dump的全部卡死因果及旧跨进程隐藏现象仍不冒充已验收。

### 21.11 B骨架交付：透传WrapOperation与一次client注册，尚无功能实现

- 本B先读21.4/21.7/21.9/21.10、[实施基线](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/baseline.json>)及before前像，再回读构建/属性/wrapper/Neo元数据，确认既有MC1.21.1、Neo21.1.82、Java21、Architectury Loom1.7-SNAPSHOT、Gradle8.8、Yarn1.21+build.1；没有scaffold/迁移/升级。**上级B角色明禁读取skills，故B未加载技能文件**；Lead已加载两匹配技能的事实见21.10，不冒充本B完成。
- 精确API重新核对仅用已有 `C:/Program Files/Java/jdk-21.0.12.1/bin/javap.exe --system C:/Program Files/Java/jdk-21.0.12.1`；version及四类读取共5命令均exit0、无timeout。N仍21.3的named82本地Jar，本轮SHA256=`010b31beb568e6d3e2c558ac2940fbffe82689080279b1d00731e2ca1dd32c8f`、22686461B；E仍既有MixinExtras0.3.5，本轮SHA256=`c47a887a872884749efc7df8853931632eabd1635653565b15b8be10c810ffc9`、174799B。onTeam精确descriptor、offset6 forceMainThread、offset218唯一两参remove、Scoreboard两参remove/getScoreHolderTeam以及WrapOperation的method/at/remap/require/expect/allow、Operation.call(Object...)均实际读取确认；不新增依赖。完整输入路径/argv/退出码见[API收据](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/B-skeleton-01/api-commands.json>)，对应5组stdout/stderr和[onTeam摘录](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/B-skeleton-01/onTeam.excerpt.txt>)实际保存在该B新子根。
- 仓库实际修改仅两项：新增33行[team骨架](<src/main/java/com/replaymod/replay/mixin/Mixin_IgnoreMismatchedReplayTeamRemoval.java>)，绑定精确onTeam/INVOKE、显式remap=true、require/expect/allow均1；方法内只有TODO注释与无条件 `original.call(scoreboard, member, targetTeam)`，原receiver/参数恰一次透传，无gate/skip/catch/feature逻辑。既有[client清单](<src/main/resources/mixins.replay.replaymod.json#L14>)只插入一行新Mixin注册，恰一次，无plugin/metadata/其他字段改动。**这不是团队错误已修复；C1仍须实现身份门和窄skip。**
- 改前重新hash核对567个基线条目全相等，新Mixin不存在；改后567条目仅清单发生预期变化，其余566项全相等，src/libs/gradle新增仅该Mixin。[FullReplaySender](<src/main/java/com/replaymod/replay/FullReplaySender.java>)和旧objective/Handler/构建/依赖等未变，EOF实现留C2。新Mixin SHA256=`d562ed72b48fabc0b0502fda3835d3f1c47d9d3214c1d34348387c43b6bc7878`、1442B；清单SHA256=`84e883209157ba4e020379da23194401a6a4ab2a1faf884cf03f44af3346208d`、1731B。[本轮真实B差异](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/B-skeleton-01/actual-B.diff>)相对实施冻结前像而非累计Git HEAD；[静态检查收据](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/B-skeleton-01/static-receipt.json>)和两个检查脚本均为本轮新B证据，不覆盖旧根。
- 上级骨架角色要求至少一个test stub，因此仅在获准外部B新根创建[常量assert stub](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/B-skeleton-01/SkeletonTestStub.java>)，无依赖、未编译/未运行、不是生产行为验证；**未创建或更改任何仓库测试**。未执行Gradle/build/javac/游戏/安装/Git写/attach，未打开真实录像、实际安装包、游戏cache/锁/.whale-craft。静态源码检查不等于编译成功、MixinExtras初始化/变换成功、remap产物正确或Neo264全mod运行通过；这些留D。没有新可安装包，不能将历史测试数用于本轮。
- 本B结束并等待Lead回读，不启动C。计划绝对路径：[C:/Users/zhenz/Desktop/ReForgedPlay-1.21/plan.md](<C:/Users/zhenz/Desktop/ReForgedPlay-1.21/plan.md>)；本节仅追加，前文历史不覆写。

### 21.12 C1交付：严格当前replay身份门与mismatch-only成员移除skip，EOF留C2

- 本C1先读21.4/21.7/21.9/21.10/21.11、B骨架/清单/外部stub/实际API收据及旧objective身份门、Accessor、ReplayHandler当前Full/Quick选择与pipeline源；先核既有Loom1.7-SNAPSHOT、Java21、MC1.21.1/Neo21.1.82与neoforge.mods.toml，未迁移/升级/scaffold。上级C角色不允许构建及网络访问，故本片仅源码实现、只读javap与外部静态检查，**没有编译、行为运行或真游戏验收**，不以静态检查冒充21.7矩阵PASS。
- 在独立新[C1证据子根](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/C1-team-01>)保存本片前像与实测收据。使用既有JDK21.0.12.1重新执行6条javap，全部exit0、无timeout；读取本机named82、MixinExtras0.3.5与既有Netty4.1.97.Final，非仅引用B结论。核onTeam精确descriptor、offset6 forceMainThread、offset218唯一两参remove及其REMOVE循环位置；Scoreboard实际getScoreHolderTeam与if_acmpeq身份分支/原IllegalStateException；MC当前handler/getConnection/getPacketListener/isOnThread（含继承）和Netty channel/pipeline签名；WrapOperation与Operation.call签名。named与Extras的hash/size均与B相等。完整argv、退出码、输入hash见[API收据](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/C1-team-01/api-commands.json>)；[API断言](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/C1-team-01/api-checks.json>)及6组stdout/stderr均为本片新读取，不覆盖B输出。
- 唯一生产变更是[新team Mixin](<src/main/java/com/replaymod/replay/mixin/Mixin_IgnoreMismatchedReplayTeamRemoval.java#L37-L78>)：保留B注解/descriptor/remap/require=expect=allow=1与callback签名，复制窄@Unique helper，其源码与只读旧objective门完全一致：MC主线程、当前MC handler===this、connection listener===this、Accessor channel为open/active EmbeddedChannel、当前ReplayHandler及sender非null，pipeline的packet_handler===connection且ReplayHandler.PACKET_HANDLER_NAME===当前Full或Quick sender。没有只凭有ReplayHandler或EmbeddedChannel就放行。
- 仅在该门通过且**实际receiver** `scoreboard.getScoreHolderTeam(member) != targetTeam` 时直接return这一次wrapper，不改映射/成员表/当前另一队，不按队名或equals比较、不改String、不cancel包、不catch异常。其余路径唯一的 `original.call(scoreboard, member, targetTeam)` 保持原receiver/参数恰一次；由原onTeam循环继续后续成员/动作。此为代码审查结论，尚非实际变换后方法执行证据。用户提供的raw纯流参考“index2501/t0 Qantas1迁往9998_d26e；index5867/t820ms旧队9599_8b96再REMOVE”只作为外部模拟线索，**本C1未重新读录像/重跑模拟，不给MC运行签证**。
- [真实C1差异](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/C1-team-01/actual-C1.diff>)相对本片读入的33行B骨架，不是累计HEAD diff；[静态收据](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/C1-team-01/static-receipt.json>)确认567个已冻结条目在C1前后全相等（包括B清单、FullReplaySender、旧objective、Handler、构建/依赖/已有测试），src/libs/gradle路径集合未增删。相对21.10原始基线仍仅B清单这一既有变更，另新Mixin由B骨架补至79行、3284B，SHA256=`335870c8d5f05d379c8c1ef52a3089a4606e06f19aa7897af8e9952cff3eb47d`。计划仅追加本节；片前像、片后像、两个Python脚本与原始工具输出均在新C1子根。
- 本片未读写真实录像、实际安装包、游戏cache/锁/.whale-craft；两录像/安装包的本片未变独立重算留Lead，不冒充本C1已核其当前hash/mtime。未运行Gradle/javac/测试构建工具/游戏/attach/安装/Git写，未改仓库测试。外部Python只执行字节指纹、精确源码/注解/helper比较及只读javap，**未用手写Scoreboard/decoder或stub门模拟冒充行为验证**。
- 待D按21.7/21.9分层实测：真实Scoreboard原版异常对照；当前replay合法/无队/另一队/同名异对象、混合/重复成员；live/off-thread/旧handler/listener/sender/关闭channel及Full↔Quick负例；真实变换后onTeam主线程重入与其余team操作；remap产物/Extras实际初始化可见性与唯一完整build。Neo264全mod启动、视觉保真和完整卡死因果仍未验收。**C1到此结束，等待Lead回读；不启动C2/D，不改FullReplaySender，EOF修复留下一片。**

### 21.13 Lead回读C1、真实team包与EOF反例，串行C2入口

- Lead已回读79行实源、75行C1-only差异、21.12与API摘要；独立 [C1后保护检查](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/lead-check-C1/receipt.json>)确认当前源/资源相对初始只变清单＋新Mixin，565白名单外保护项均相等，两录像和实际安装旧包hash/size/mtime全不变。新Mixin固定335870c8d5f05d379c8c1ef52a3089a4606e06f19aa7897af8e9952cff3eb47d；清单固定84e883209157ba4e020379da23194401a6a4ab2a1faf884cf03f44af3346208d，C2不得写它们。尚无C1编译或行为PASS，不把静态收据当实现已验收。
- 只读队员新 [team原帧报告](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/readonly-team-packets/REPORT.md>)、[事件全量](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/readonly-team-packets/team-events.json>)与[审计](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/readonly-team-packets/team-audit.json>)已由Lead回读。服务器177个team包仅一次member归属不匹配：2501/t0 Qantas1由ADD自动迁到9998_d26e，5867/t820旧队9599_8b96又REMOVE，确为另一队而非无队。之后62343/t10251对vqlxfy的合法REMOVE仍须正常执行。全部18个原REMOVE均单成员，**不能用原件证明同包先invalid后valid继续；D须显式合成混合/重复名单，用真实变换后路径验证**。无中途Join/respawn/reconfig边界；错误生成源仍未知，纯流模拟不是MC运行。
- Lead回读只读 [EOF十条审查](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/readonly-eof-review/READONLY-NOTES.md>)；提炼的 [C2/D约束](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/LEAD-C2-CONSTRAINTS.md>)现已冻结，C2/D均先读。这是21.9 EOF范围内的具体化：不额外授权通用decoder异常策略，不改Handler/Scheduler/GUI，不要求先再问用户已批准的EOF内部细节。
- 特别增加D确定性反例：原hurry完成需lastTimeStamp>desired，但EOF等待只在desired==-1时执行；目标恰等于/越过末包可保留desired后绕过等待、自动重放。修复必须消费**当次**forward EOF意图并保持末帧，不覆盖并发backward；不能简单EOF→restartPending导致同步same-time自动重启。暂停/普通mode/EOFclose本身不得提前advanceGeneration或使用terminal一次性close worker。
- 新EOF pause应区分初次确认与已有暂停/恢复意图；晚到pause不可吞一次resume。Handler的async=true后才setSpeed(0)，两句间旧正速不算新恢复；setReplaySpeed正速现在不增controlEpoch，不能仅靠旧control guard。runSync超时不cancel任务，新EOF pause/restart需有失败/超时后晚到票据防护；所有client回调不得新取sender长锁或在modeLock内wait。具体紧凑实现由C2定，不要求顺手重构旧非EOF路径。
- 此时才启动C2：**仅FullReplaySender的EOF状态/接续修复**与计划追加，外部证据在本轮新C2子根；先核源码/API、做真实片前像diff、保留失败原文和签名审计。若角色不允许编译/运行行为，明确留D，不伪称做过。C2结束先由Lead回读再启动D；D要求仍为21.7/21.9/21.10以及本节，不拿旧D/静态检查作通过。构建/依赖/配置/仓库测试/原件与全部表外源码保护继续有效。

### 21.14 C2续派交付：EOF保持末帧，明确恢复后先重建协议

- 续派重新核对片前 `FullReplaySender` 字节等于Lead冻结前像；C1 Mixin/清单保持21.13指纹。仅改 [FullReplaySender](<src/main/java/com/replaymod/replay/FullReplaySender.java>) 和追加本节；[真实C2片差异](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/C2-resumed/actual-C2.diff>)、[计划追加差异](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/C2-resumed/plan-append.diff>)、[范围/前后指纹](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/C2-resumed/receipt.json>)均与本次续派前像比较，不是Git累计差异。
- 两种发送方式均先记EOF，再真实关闭当代reader；关闭失败保留引用和待关闭状态，等同步查询、显式重启或终止走原真实close，不再读该流。EOF历史保留关闭前代际，等时/更晚同步查询保持末帧和请求时间；实际倒退、失败或Full/Quick/连接代际失效仍走必要边界。非EOF合法reader和prefetch不因普通模式切换被重标、丢弃或重开。
- 初次EOF暂停与显式恢复分开：未执行的pause也在异步UI上呈现可播放；新正速在短锁下替换EOF暂停意图，已有暂停跨worker沿用，切至异步时残留的正速不算恢复。EOF消费未达目标的forward hurry（含恰末包/越末包），不清掉新backward请求。重启复用Handler原重建；只有确认新活动Full通道和最终代际才清EOF、允许从LOGIN开流。EOF pause/restart的runSync票据在返回、失败或中断后作废；client回调不获取sender长锁，Handler重建不在modeLock内。EOF本身不推进资源包代际、不走terminal关闭完成位。
- D优先用生产方法＋真实Netty/MC：① sync到实际末包/越末尾、同/更晚目标重复采样、true→0停点、Play仅一次，断言原通道零LOGIN，新通道LOGIN→CONFIG→PLAY真实payload读尽；② 自然EOF在首次pause前、已pause后及mode/epoch替换时用barrier测一次resume；保留超时回调排队，在失效、supersede、terminal后放行检查零旧副作用；③ hurry恰末/越末、EOF后forward重复及并发backward/等时拖动，检查末帧、目标和唯一回放；④ LIVE预读交接及EOF Full→Quick→Full的0/中间/末尾；⑤ 注入EOF close异常、恢复后新reader以及终止竞态，计真实close、Managed release、未推进旧回调代际、无终止后发包/重启。团队矩阵及唯一完整构建仍按21.7/21.9/21.10。
- C2仅源码和范围核对，未编译、未运行测试/真实协议管线/游戏，不读写原录像或实际安装包/cache/恢复目录，不改依赖/配置/仓库测试/Git/whale；未复现完整卡死因果，也未验收Neo264全mod。结束等Lead回读，不启动D。

### 21.15 D交付核验：构建、新Jar、真实隔离矩阵及未验收项

- 共享 [D报告](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/D-resumed/REPORT.md>)、[证据索引](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/D-resumed/EVIDENCE-INDEX.md>)、[实际三目标差异](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/D-resumed/C2-readback-actual-current-round-source.patch>)。D未改生产、仓库测试、配置、依赖、Git状态或实际游戏；计划在已有空分隔行后仅追加。固定MC1.21.1、Neo21.1.82、Java21.0.12.1、Loom1.7.435、Gradle8.8。唯一指定 `mc_gradle build` 为 `BUILD SUCCESSFUL in 50s`，`test NO-SOURCE`；[实际工具输出](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/D-resumed/mc_gradle-build-tool-original.txt>) 保留既有目标以外的WorldRenderer/JOML remap诊断，区别成功任务和警告/错误行。未执行额外任务或shell wrapper。
- 新 [交付Jar](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/D-resumed/reforgedplaymod-1.21.1-0.3.1.jar>) 17631511B / SHA256 `7d4ca53931ee90d15829b9d193c6b85e4d589d805bacc6c92c95f3caa697b644`，全ZIP CRC、唯一client注册；真实Sponge＋Extras变换通过named82、最终remap82及用户264原版基，检查ensure先行、唯一wrapper及原调用bridge，82 Extras0.3.5/264实际内置0.5.5。264原版基执行真实变换后 `onTeam`，异线程→主线程、混合/重复/同名异对象、原版CRUD/迁移，以及活跃本地TCP NioSocketChannel等负例保留原异常。未冒称完整联机/单人游戏启动或全mod应用顺序。
- 原录像只读ZIP。服务器177个team帧以真实PLAY decoder加实际变换后onTeam处理：#5867保持Qantas1在另一队；#62343合法删除；18个显式成员REMOVE调用原实现17次。单人4个原始帧在正确LOGIN/CONFIG下实际解码，#1误投PLAY的对照复现add_entity DecoderException；不代表整段PLAY或原卡死已重现。原件与旧安装包 [保护收据](<C:/Temp/reforgedplay-team-eof-impl-20261118-fe92/D-resumed/final-protection.json>) 核hash/size/mtime。
- 最终Jar生产FullSender＋真实Netty及264基PacketDecoder的12条隔离用例通过：exact/overshoot、同/晚目标、末帧只在显式恢复后重建一次、旧PLAY零新增LOGIN、正常预读、forward hurry exact/overshoot、backward、EOF真实close失败重试/terminal、迟到pause/终止回调、未发布启动/重复失败后重试、回执前后换mode，以及真实Handler Full↔Quick管线回Full并从0到中间。工装明示边界：客户端/调度、Handler disconnect/setup、针对阶段包切入下一真实decoder，Quick索引和ReplayFile外壳；真实输入流、解码与FullSender非stub。迟到测试是注入短超时，不声称在真Scheduler计时30秒。失败和超时原始日志另留，不充作通过。
- **未启动真机/未安装。** 未验收完整Neo264全mod、真实Quick索引与Managed release/恢复持久层、完整录像/视觉与旧objective全矩阵、真实线程dump和卡死最终因果。新包可交用户另行复验，不是全游戏通过签证。
