# ReForgedPlay 自定义目录与 RAMDisk 支持计划

> 状态：第一批兼容修订已完成 A2→B2→C2→D2，并经 Lead 回读日志与代码复核：Java 21 隔离验证 PASS=14 / SKIP=1 / FAIL=0，完整 build 成功。当前停在可供用户自行 commit 的“完整回放安全搬移基础”检查点；游戏内 cut/split/raw 播放及确定性故障注入尚未验收，不是完整 RAMDisk 功能或生产可用性结论。**第 11 节是本批范围；第 11.9–11.12 节为已实现修订，第 11.13 节为真实结果；第 11.3 节冲突算法及第 11.8 节失败结果保留为历史。第 3–10 节为尚未完成的长期路线，不自动进入下一批。** 代理未执行 Git 写操作。
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
