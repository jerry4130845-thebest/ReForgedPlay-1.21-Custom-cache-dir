# ReForgedPlay 自定义目录与 RAMDisk 支持计划

> 状态（第三批P：实现及授权验证层通过，进入用户自行提交源码检查点；实际游戏未验收）：D-final独立22/33/完整20/新33＋18个Quick/连接例、真实C/R、完整build23s及新最终JAR真实Sponge4/4全部通过。Lead已复核八实现文件实际范围/关键diff、亲核当前源码与JAR指纹、完整八文件patch及白名单；13.27为最终收据。只更新计划收尾，不再改生产源码，不代执行Git写/安装/游戏、不进入下一批。NeoForge实际launch/全模组/世界UI/render/game及OS权限不足项仍未签；历史13.22证据覆盖/13.23权限字段偏差完整保留，未借最终PASS洗掉。最后结束活跃目标，停等用户审查并自行commit。
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
