# ReForgedPlay 自定义目录与 RAMDisk 支持计划

> 状态：源码只读审查后的待批准方案。本轮仅新增本文档，不实施功能，不修改代码、测试、配置或依赖。
> 用户目标：独立配置 cache、raw、temp、tmp、record 等目录，支持绝对路径及相对于游戏目录的路径；将可丢弃缓存和临时文件放在 RAMDisk，用完清理，减少实体硬盘写入。没有已报告故障，本次属于预防性兼容与容错设计。

## 1. 项目基线与证据边界

- Minecraft 1.21.1、NeoForge 21.1.82：[gradle.properties](<gradle.properties#L6-L20>)。
- Architectury Loom 1.7-SNAPSHOT、Java 21：[build.gradle](<build.gradle#L1-L11>)；Gradle 8.8：[gradle-wrapper.properties](<gradle/wrapper/gradle-wrapper.properties#L1-L7>)。
- 不更换加载器、映射、Java、Gradle 或依赖版本，不处理与存储无关的问题。
- 本轮完成主代理源码检查及一名只读审查队员的源码/JAR 字节码检查。当前工具目录未提供 Minecraft 技能加载器、专用阶段子代理及 mc_gradle，没有声称加载技能或执行构建。
- 初始 `git status --short` 为空；未发现已有计划；`src/test/**/*` 未找到文件，不据此宣称整个项目绝无测试。

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
- 当前没有 mc_gradle；未来仍不可用则明确报告构建未执行，不自行用 shell gradlew 绕过。
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
