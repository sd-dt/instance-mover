# Instance Mover · 实例酱的搬家服务

> **Minecraft 26.2 / Fabric client mod** — shows a GUI the first time you launch a modpack, letting you pick which player data to migrate from an **old instance** and merge it into the current one.

[中文说明见下方](#中文说明) ｜ Author: **sd_dt** & **deepseek** ｜ License: **AGPL-3.0** ｜ Requires **Fabric Loader ≥ 0.19.5** + **Fabric API** ｜ Client-side only

---

## What it does

When you switch to a new modpack (or upgrade a pack), your worlds, keybinds, mod configs, minimap waypoints, shader settings and LOD caches all live in the **old instance**. Copying them by hand is error-prone. This mod turns it into a few clicks inside the game:

1. On first launch it opens a **"choose old instance"** screen automatically;
2. It scans **every version instance** under the same `.minecraft\versions\` folder (that is how PCL2 / HMCL isolate versions) — you can also type or browse for a path;
3. Every item shows **what it is and how big it is**; defaults are migrated automatically, optional items are checkbox-driven;
4. Hit **Start migration** → data is merged over the current instance, with an **automatic backup** taken beforehand;
5. The result screen reports success / skipped / failed and warns which changes **require a game restart**.

## Migration list (25 items)

### Migrated by default (11)

| Name | Type | What it is |
|---|---|---|
| `config` | folder | All mod configs: mod toggles, keybinds, video/audio options, machine and gameplay settings |
| `saves` | folder | Single-player worlds: maps, progress, inventories, advancements (includes DH/Voxy single-player LOD data) |
| `resourcepacks` | folder | Resource pack files and the enabled list |
| `shaderpacks` | folder | Shader pack files (which one is active is stored in `optionsshaders.txt` / `iris.properties`) |
| `schematics` | folder | Schematics / blueprints (Litematica, WorldEdit, …) |
| `screenshots` | folder | In-game screenshots |
| `xaero` | folder | Xaero minimap / world map data and waypoints |
| `.voxy` | folder | Voxy multiplayer LOD cache |
| `Distant_Horizons_server_data` | folder | Distant Horizons multiplayer LOD cache |
| `options.txt` | file | Vanilla settings: language, volume, keybinds, render distance, enabled packs |
| `servers.dat` | file | Multiplayer server list (no credentials) |

### Ask-before-migrating (14)

**Checked by default (9)**: `journeymap`, `XaeroPlus`, `optionsof.txt`, `optionsshaders.txt`, `iris.properties`, `replay_recordings`, `CustomSkinLoader`, `tacz`, `command_history.txt`
**Unchecked by default (5)**: `essential`, `mods`, `defaultconfigs`, `PCL`, `hmcl.json`

> The full per-item description (what exactly happens when it is migrated) is available in-game via the **"View each item's purpose"** button, or in [`docs/迁移清单.md`](docs/迁移清单.md).

## Safety design

| Mechanism | Behaviour |
|---|---|
| **Merge-overwrite** | Files present in the source overwrite the target; **files unique to the new instance are kept** (worlds are never wiped) |
| **Automatic backup** | Taken before overwriting, into `<current instance>\_转移备份_YYYYMMDD_HHMMSS\` (suffix `_1`/`_2` on collision) |
| **Identical-content skip** | Compares size + mtime, then content in 256 KB chunks for files ≤ 4 MB; **files > 4 MB are always rewritten** (never silently skipped) |
| **Failure never aborts** | A locked or inaccessible file is recorded in the report; the rest continues |
| **Read-only unlock** | Read-only targets get their attribute cleared before overwriting |
| **Long paths** | Handled per Windows convention; verified with 280+ character deep paths |
| **Atomic replace** | Writes to a temp file in the same directory, then atomically replaces — a crash never leaves half a file |
| **Self-copy refusal** | Refuses when the old instance equals the current one, or sits inside an item that is about to be migrated |
| **Cancellable** | The progress screen has an **Abort** button; already-copied files are kept |

## Installation

1. Install **Fabric Loader ≥ 0.19.5** and **Fabric API**;
2. Drop `instance-mover-fabric-26.2-1.0.3.jar` into the instance's `mods\` folder;
3. Launch the game and **stay on the title screen for a few seconds** — the migration screen pops up on first run.

## Usage

1. **Pick the old instance** — the dropdown lists auto-detected candidates (version name / path / world count / size); you can also use **Browse…** or type a path, then hit **Detect**;
2. **Tick what to migrate** — 11 defaults are migrated directly; 14 optional items are up to you (items missing from the old instance are listed separately and skipped safely);
3. **Start migration** — the progress screen shows the current file, completed count and copied bytes, and can be aborted at any time;
4. **Read the result** — success/skipped/failed counts; if config-type files were touched it warns **"restart required"** and offers a **Quit game** button.

### Want to see the guide again?

- In game, open the **About** page and click **"Show the migration guide again (next launch)"**;
- Or simply delete `<current instance>\config\instance_mover.json`.

> After one **successful migration** the screen no longer pops up automatically on later launches (so it does not nag). Cancelling, failing or aborting writes **no** marker, so it will show again next time.

## FAQ

| Question | Answer |
|---|---|
| I migrated config but nothing changed | `config`, `options.txt`, `optionsshaders.txt`, `iris.properties` are read into memory at startup — a **restart is required**. The result screen tells you this |
| I quit without restarting — did I lose it? | Possibly: on a normal exit the game may write its in-memory settings back over your migrated files. **Quit and relaunch as prompted** |
| I cannot find my old instance | Make sure it lives under the same `.minecraft` (`versions\<name>\` or the `.minecraft` root); otherwise use **Browse…** |
| Size shows "not fully scanned" | Huge folders (multi-GB Voxy/DH caches) are only sampled (3000 files / 0.5 s cap). Not an error |
| The UI is Chinese only | Per the author's decision the interface is **Chinese only**. `en_us.json` mirrors `zh_cn.json` so English clients never show raw translation keys |
| Layout looks cramped in a small window | Keep the window at **≥ 300 logical pixels** tall (the default 854×480 does not trigger it) |

## Building from source

Requires **JDK 25** (Minecraft 26.2 minimum):

```bash
# Linux / macOS
./gradlew build

# Windows
gradlew.bat build
```

Output: `build/libs/instance-mover-fabric-26.2-1.0.3.jar`

> Toolchain (see `gradle.properties`): Minecraft 26.2 · Fabric Loader 0.19.5 · Fabric Loom 1.17.21 · Fabric API 0.159.0+26.2
> Machine-specific JDK paths and proxy settings were stripped from `gradle.properties`; add your own if needed.

## Tests

```bash
./gradlew test      # Windows: gradlew.bat test
```

**19 test classes / 160 cases, all green**, covering:

- copy engine: merge-overwrite, keeping target-only files, identical-content skip, >4 MB conservative rewrite, read-only unlock, long paths, atomic replace, cancellation, failure isolation;
- the **real defect once fixed in the v1.1 tool**: two files with identical size and mtime but different content must be overwritten (tested at 1024 B and 4 MB + 4096 B);
- backup: content equals the pre-overwrite state, visibility when backup fails, and that failure does not block the transfer;
- path validation: three cases (allow / refuse wholesale / skip that single item), including a junction-alias bypass hardening test;
- instance scanning: version-isolated folders, non-isolated root, mods-only folders are not selectable, size excludes `versions\`;
- first-run gate and marker file: later / never-ask-again / forced GUI / self-test switch, corrupt JSON fallback, atomic write;
- language files: identical key sets and values, consistent placeholders.

In-game self-test: add the JVM argument `-Dinstance_mover.selftest=true` and the log prints `SELFTEST PASS 19/19`.

## Known issues

| # | Issue | Notes |
|---|---|---|
| 1 | With a logical window height < 286 the paging row overlaps the options area; < 296 the summary row overlaps the bottom buttons | Keep the window **≥ 300 logical pixels** tall; not triggered at the default size, degrades safely |
| 2 | A few hard-coded Chinese strings remain outside the language files | `ResultScreen` 3, `DirectoryPickScreen` 1, `InstanceScanner` 11, `MigrateScreen` 2; display and behaviour unaffected |
| 3 | Some screens have no screenshot yet | Cancelled result, failure list, incomplete-backup warning, marker-written effect — covered by unit tests instead (migrations finish too fast to catch the abort frame) |
| 4 | Chinese-only UI | See FAQ above |
| 5 | No full real-modpack playthrough verification yet | End-to-end runs were done in the dev client (a real 526 MB migration, backup, restart prompt, quit button), and a real 336-mod instance was used to find and fix the "no popup on first launch" bug |

## Credits

- The migration list and all protection logic come from the author's earlier **MC实例数据转移工具 v1.1** (a Python + Tk tool);
- Thanks to **deepseek** for the collaboration on this port;
- Built in the [Fabric](https://fabricmc.net/) ecosystem under **AGPL-3.0** — redistributions must keep the full source and license.

---

<a id="中文说明"></a>
# 中文说明 · 实例酱的搬家服务

> **Minecraft 26.2 / Fabric 客户端模组** —— 第一次进入整合包时自动弹出界面，让你勾选要从**老实例**迁移过来的玩家数据，合并覆盖到当前实例。

本模组是把作者此前写的 Windows 小工具 **MC实例数据转移工具 v1.1**（Python + Tk）的完整逻辑，移植成**游戏内模组**：不需要外部程序、不需要手动改文件，进游戏点几下就搬完。

作者：**sd_dt** 与 **deepseek** ｜ 许可：**AGPL-3.0** ｜ 需要 **Fabric Loader ≥ 0.19.5** + **Fabric API** ｜ 仅客户端

## 一、它解决什么问题

换整合包（或升级整合包版本）时，玩家的存档、键位、模组配置、小地图路径点、光影设置、远景缓存等都在**老实例**里。手动复制容易漏、容易覆盖错。本模组把这件事做成**进游戏点几下**：

1. 第一次进入时自动弹出「**选择老实例**」界面；
2. 它自动扫描同一 `.minecraft` 下 `versions\` 里**所有版本实例**（PCL2/HMCL 的版本隔离目录），也可以手动填/选路径；
3. 逐项显示**每样东西是什么、有多大**，默认项直接搬，额外项由你勾选；
4. 点「开始迁移」→ 合并覆盖到当前实例，覆盖前**自动备份**；
5. 结果页告诉你成功/跳过/失败，并提示哪些改动**需要重启游戏才生效**。

## 二、迁移清单（25 项）

### 默认直接迁移（11 项）

| 名称 | 类型 | 作用 |
|---|---|---|
| `config` | 文件夹 | 所有模组的配置文件：模组开关、按键绑定、画面/音效选项、机器与玩法设置 |
| `saves` | 文件夹 | 单人存档：地图、进度、背包、成就（含 DH/Voxy 的单人远景数据） |
| `resourcepacks` | 文件夹 | 资源包文件本体与已启用清单 |
| `shaderpacks` | 文件夹 | 光影包文件本体（用哪个光影记在 `optionsshaders.txt` / `iris.properties`） |
| `schematics` | 文件夹 | 投影/蓝图文件（Litematica、WorldEdit 等） |
| `screenshots` | 文件夹 | 游戏截图 |
| `xaero` | 文件夹 | Xaero 小地图/世界地图数据、路径点 |
| `.voxy` | 文件夹 | Voxy 多人服务器远景 LOD 缓存 |
| `Distant_Horizons_server_data` | 文件夹 | 遥远的地平线（DH）多人服务器远景缓存 |
| `options.txt` | 文件 | 原版主设置：语言、音量、按键、视距、资源包启用列表 |
| `servers.dat` | 文件 | 多人服务器列表（不含账号密码） |

### 询问后按需勾选（14 项）

**默认勾选 9 项**：`journeymap`、`XaeroPlus`、`optionsof.txt`、`optionsshaders.txt`、`iris.properties`、`replay_recordings`、`CustomSkinLoader`、`tacz`、`command_history.txt`
**默认不勾 5 项**：`essential`、`mods`、`defaultconfigs`、`PCL`、`hmcl.json`

> 完整逐条说明（含每项「转移过去会怎样」）见界面的「查看每项作用」按钮，或 [`docs/迁移清单.md`](docs/迁移清单.md)。

## 三、安全设计

| 机制 | 说明 |
|---|---|
| **合并覆盖** | 源有则覆盖，**新实例独有的文件保留不删**（存档不会被清空） |
| **覆盖前自动备份** | 备份到 `<当前实例>\_转移备份_年月日_时分秒\`（重名自动加 `_1`/`_2`） |
| **相同内容跳过** | 大小、时间相同且 ≤4MB 时逐块比对内容；**>4MB 一律重写**（宁可多写也不漏覆盖） |
| **失败不中断** | 单个文件被占用/无权限只记进报告，其余照常搬运 |
| **只读解锁** | 目标文件是只读时先清只读属性再覆盖 |
| **超长路径** | 路径过长时按 Windows 约定处理，实测 280+ 字符深路径可正常读写 |
| **原子替换** | 写盘先写临时文件再原子替换，崩溃不会留半截文件 |
| **拒绝自复制** | 老实例等于当前实例、或老实例落在待迁移条目内部时拒绝执行 |
| **可随时中止** | 进度页有「中止」，已复制的内容保留不回滚 |

## 四、安装

1. 装好 **Fabric Loader ≥ 0.19.5** 与 **Fabric API**；
2. 把 `instance-mover-fabric-26.2-1.0.3.jar` 放进该实例的 `mods\` 目录；
3. 启动游戏，**停在标题界面等几秒** —— 第一次进入会自动弹出迁移界面。

## 五、使用流程

1. **选择老实例**：下拉里是自动探测到的候选（显示版本名/路径/存档数/体积），也可点「浏览…」选目录或直接填路径；选完点「检测」确认；
2. **勾选要迁移的内容**：默认 11 项直接搬；14 项可选项按需勾选（老实例里没有的项会单列并自动跳过）；
3. **开始迁移**：进度页显示当前文件、已完成数、已复制体积，可随时中止；
4. **看结果**：成功/跳过/失败计数；若涉及配置类文件会提示「**需要重启游戏才生效**」，并给出「退出游戏」按钮。

### 想重新看一次引导？

- 游戏里打开「**关于**」页 → 点「**重新显示迁移引导（下次启动生效）**」；
- 或直接删掉 `<当前实例>\config\instance_mover.json`。

> 完成一次**真实迁移**后，下次启动不再自动弹出（不想反复打扰）；取消/失败/中止都**不**写入标记，下次照样弹。

## 六、常见问题

| 问题 | 说明 |
|---|---|
| 配置文件覆盖了却不生效 | `config`、`options.txt`、`optionsshaders.txt`、`iris.properties` 在游戏启动时已读入内存，**必须重启游戏**才生效；结果页会提示 |
| 不重启直接退出会怎样 | 游戏正常退出时可能把内存里的旧配置写回去，覆盖你的迁移结果。**请按提示先退出再重开** |
| 选不到老实例 | 确认老实例在同一 `.minecraft` 下（`versions\<版本名>\` 或 `.minecraft` 根）；也可「浏览…」手选 |
| 体积显示「未扫完」 | 超大目录（如数 GB 的 Voxy/DH 缓存）只做有限扫描（3000 文件 / 0.5 秒封顶），不是错误 |
| 界面文字是中文 | 本项目按作者口径**只提供中文界面**；`en_us.json` 与 `zh_cn.json` 内容相同，仅为兼容英文语言环境 |
| 窗口太小时布局拥挤 | 建议窗口逻辑高度 **≥300 像素**（默认 854×480 不会触发） |

## 七、从源码构建

需要 **JDK 25**（Minecraft 26.2 最低要求）：

```bash
./gradlew build        # Linux / macOS
gradlew.bat build      # Windows
```

产物：`build/libs/instance-mover-fabric-26.2-1.0.3.jar`

> 版本组合（见 `gradle.properties`）：Minecraft 26.2 · Fabric Loader 0.19.5 · Fabric Loom 1.17.21 · Fabric API 0.159.0+26.2
> 开发机专有的 JDK 路径与代理配置已从 `gradle.properties` 移除，需要时请自行添加。

## 八、测试

```bash
./gradlew test      # Windows: gradlew.bat test
```

当前 **19 个测试类 / 160 个用例全绿**，覆盖：复制引擎九项保证、v1.1 工具版修过的真实缺陷（大小与时间相同但内容不同必须覆盖）、备份语义与失败可见性、路径校验三情形（含 junction 别名加固）、实例探测三布局、首启判定与标记文件、语言文件一致性。

游戏内自检：加 JVM 参数 `-Dinstance_mover.selftest=true`，日志会打印 `SELFTEST PASS 19/19`。

## 九、文档

| 文件 | 内容 |
|---|---|
| [`docs/使用说明.md`](docs/使用说明.md) | 完整使用说明（安装、四段界面、备份规则、需重启说明、FAQ、已知问题） |
| [`docs/迁移清单.md`](docs/迁移清单.md) | 25 项逐条说明（作用/类型/默认勾选/是否需重启） |
| [`docs/工作流.md`](docs/工作流.md) | 设计与决策记录（方案取舍、界面线框、里程碑） |
| [`docs/移植基线.md`](docs/移植基线.md) | 从工具版 Python 源码逐行提取的移植真源（行为/文案/边界条件） |
| [`docs/决策记录-路径校验语义.md`](docs/决策记录-路径校验语义.md) | 路径校验三情形的裁决与推演 |

## 十、已知问题

| # | 问题 | 说明 |
|---|---|---|
| 1 | 窗口逻辑高度 < 286 时分页行与选项区重叠，< 296 时统计行与底部按钮压叠 | 建议窗口高度 ≥300 逻辑像素；默认尺寸不触发，有兜底不会崩 |
| 2 | 仍有少量硬编码中文未走语言键 | `ResultScreen` 3 处、`DirectoryPickScreen` 1 处、`InstanceScanner` 11 处、`MigrateScreen` 2 处；不影响显示与功能 |
| 3 | 部分界面没有截图 | 已取消结果页、失败清单页、备份不完整警告、首启按钮落盘效果；均有单测覆盖 |
| 4 | 仅中文界面 | 见第六节说明 |
| 5 | 未做完整真实整合包游玩验收 | 开发客户端已完成端到端实跑（真实 526MB 迁移、备份、需重启提示、退出游戏按钮），并在真实 336 模组整合包实例里修复过「首次进入不弹窗」 |

## 十一、致谢

- 迁移清单与全部保护逻辑来自作者此前的 **MC实例数据转移工具 v1.1**（Python + Tk 版本）；
- 感谢 **deepseek** 在移植与实现过程中的协作；
- 本项目在 [Fabric](https://fabricmc.net/) 生态下开发，遵循 **AGPL-3.0**，再分发请保留完整源码与许可。

---

**作者**：sd_dt 与 deepseek ｜ 内置界面与文档中均含「该模组由 deepseek 和 sd_dt 编写」的声明。
