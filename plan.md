# ReForgedPlay 自定义目录与 RAMDisk 支持计划

> 状态（第三批重规划，Lead 已复核）：用户确认前两批已提交；本轮只读核实 HEAD=2903157，规划前工作区 clean。第三批**仅授权规划，未授权源码实施、测试或构建**，新第 13 节是待确认候选，不是实施白名单。此前被停止、无 closing report 的第三批委派不视为完成。本轮只更新本顶部与第 13 节，保留第 1–12 节为历史；第 12 节“等待提交”等文字不再代表当前提交状态。第二批真实 MC/native PNG 与游戏验收缺口依然保留。第三批完整目标因资源包真实释放确认尚未闭合而 BLOCKED，不能把 clear()/网络 close()/加载 future 当作 ZIP 已释放。未写源码、未构建、未编译测试、未 Git 写或启动游戏。
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
