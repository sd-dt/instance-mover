package dev.sd_dt.instancemover.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 25 项迁移清单：默认 11 项 + 询问 14 项。
 *
 * <p>逐字对齐 mc_transfer.py v1.1 的 {@code DEFAULT_ITEMS} / {@code OPTIONAL_ITEMS}，
 * 并按用户决定把 {@code .voxy}、{@code Distant_Horizons_server_data} 从询问项搬进默认项
 * （py 原文是 9 + 16）。</p>
 *
 * <p>文案不许改写、不许杜撰——界面直接显示这里的 {@link MigrateItem#description()}。</p>
 */
public final class MigrateCatalog {

    /**
     * 覆盖后需要重启游戏才生效的条目（配置类文件在游戏启动时已读进内存，
     * 不重启就退出游戏，改动会被游戏反向覆盖回去）。
     */
    public static final Set<String> RESTART_REQUIRED_NAMES = Set.of(
            "config",
            "options.txt",
            "optionsshaders.txt",
            "iris.properties",
            "optionsof.txt",
            "defaultconfigs");

    /** 默认直接迁移（11 项，不询问）：v1.1 的 9 项 + .voxy + Distant_Horizons_server_data。 */
    public static final List<MigrateItem> DEFAULT_ITEMS = List.of(
            item("config", ItemKind.DIR,
                    "所有模组的配置文件：模组开关、按键绑定、画面/音效选项、机器与玩法设置。"
                            + "转移后新实例的模组设置会变成老实例那一套"),
            item("resourcepacks", ItemKind.DIR,
                    "资源包文件本体（材质、贴图、字体、音效包）与已启用清单。"
                            + "转移后进游戏用的还是老实例那套材质"),
            item("saves", ItemKind.DIR,
                    "单人存档：地图、进度、背包、成就。存档里还带着远景数据——"
                            + "Distant Horizons 在 saves/存档名/data/DistantHorizons.sqlite，"
                            + "Voxy 在 saves/存档名/voxy"),
            item("schematics", ItemKind.DIR,
                    "投影 / 蓝图文件：Litematica、WorldEdit、投影模组用的建筑图纸"
                            + "（.litematic / .schem 等）"),
            item("screenshots", ItemKind.DIR,
                    "游戏截图：游戏里按 F2 保存的图片"),
            item("shaderpacks", ItemKind.DIR,
                    "光影包文件本体（Iris / OptiFine 加载的 .zip 光影）。"
                            + "注意「用哪个光影」记在 optionsshaders.txt 或 iris.properties 里"),
            item("xaero", ItemKind.DIR,
                    "Xaero 小地图 / 世界地图数据：已探索区域的地图缓存、路径点、地图显示设置"),
            item("options.txt", ItemKind.FILE,
                    "原版游戏主设置：语言、音量、按键绑定、视距、画面质量、"
                            + "资源包与数据包启用列表"),
            item("servers.dat", ItemKind.FILE,
                    "多人游戏服务器列表：添加过的服务器地址、名称、收藏状态（不含账号密码）"),
            item(".voxy", ItemKind.DIR,
                    "【Voxy 远景 LOD 缓存】多人服务器上已生成的超远视距地形，按服务器分目录。"
                            + "转移后不用重新跑图就能立刻看到远景，不转移只是需要重新生成；"
                            + "体积可能有好几个 GB（单人存档的 Voxy 数据在 saves 里，随 saves 一起转移）"),
            item("Distant_Horizons_server_data", ItemKind.DIR,
                    "【Distant Horizons 远景 LOD 缓存】多人服务器已生成的远景数据，"
                            + "按服务器名分目录（内部还有 dim_overworld 等维度子目录）。"
                            + "体积一般比 Voxy 小；单人存档的 DH 数据在 saves/存档名/data 里，随 saves 转移"));

    /** 询问项（14 项，勾选后才转移）：默认勾选 9 项，默认不勾 5 项。 */
    public static final List<MigrateItem> OPTIONAL_ITEMS = List.of(
            optional("journeymap", ItemKind.DIR,
                    "JourneyMap 地图数据：已探索区域地图缓存与路径点。"
                            + "它和 xaero 是两套不同的地图模组，看你实际用哪个", true),
            optional("XaeroPlus", ItemKind.DIR,
                    "XaeroPlus 增强数据：传送点、路径点分组、服务器切换记录等扩展内容", true),
            optional("optionsof.txt", ItemKind.FILE,
                    "OptiFine 专属视频设置：光影开关、视距、抗锯齿、性能选项"
                            + "（装了 OptiFine 才有这个文件）", true),
            optional("optionsshaders.txt", ItemKind.FILE,
                    "OptiFine 当前使用的光影包及光影内部参数（决定进游戏默认开哪个光影）", true),
            optional("iris.properties", ItemKind.FILE,
                    "Iris 光影设置：当前光影包、光影选项开关（装了 Iris 才有这个文件）", true),
            optional("replay_recordings", ItemKind.DIR,
                    "ReplayMod 录像文件（.mcpr）：以前的游戏回放，转移后可继续观看和导出", true),
            optional("CustomSkinLoader", ItemKind.DIR,
                    "自定义皮肤加载器：皮肤站加载顺序配置 + 已下载的皮肤缓存", true),
            optional("tacz", ItemKind.DIR,
                    "TaCZ 枪械包数据：自定义枪械、配件、皮肤与资源包索引", true),
            optional("command_history.txt", ItemKind.FILE,
                    "聊天框输入历史：按 ↑ 键能翻出来的那些命令记录", true),
            optional("essential", ItemKind.DIR,
                    "Essential 模组数据：好友列表、外观 / 披风、账号相关缓存", false),
            optional("mods", ItemKind.DIR,
                    "模组本体（.jar 文件）。转移后新实例会加载老实例的模组，"
                            + "若游戏版本或加载器不同可能直接启动崩溃，一般不建议勾选", false),
            optional("defaultconfigs", ItemKind.DIR,
                    "整合包默认配置模板：只在新建存档 / 首次生成配置时套用。"
                            + "覆盖新实例可能改变默认玩法，一般不必转移", false),
            optional("PCL", ItemKind.DIR,
                    "PCL 启动器自己的设置与缓存（启动器文件，不是游戏存档数据）", false),
            optional("hmcl.json", ItemKind.FILE,
                    "HMCL 启动器的设置文件（启动器自己的配置，不是游戏存档数据）", false));

    /** 全部 25 项（11 默认 + 14 询问），用于「像不像一个实例」打分。 */
    public static final List<MigrateItem> ALL_ITEMS = all();

    private MigrateCatalog() {
    }

    private static MigrateItem item(String name, ItemKind kind, String description) {
        return new MigrateItem(name, kind, description, true, RESTART_REQUIRED_NAMES.contains(name));
    }

    private static MigrateItem optional(String name, ItemKind kind, String description, boolean defaultOn) {
        return new MigrateItem(name, kind, description, defaultOn, RESTART_REQUIRED_NAMES.contains(name));
    }

    private static List<MigrateItem> all() {
        List<MigrateItem> items = new ArrayList<>(DEFAULT_ITEMS.size() + OPTIONAL_ITEMS.size());
        items.addAll(DEFAULT_ITEMS);
        items.addAll(OPTIONAL_ITEMS);
        return List.copyOf(items);
    }

    /** 按名字找条目（全部 25 项里找）。 */
    public static Optional<MigrateItem> find(String name) {
        return ALL_ITEMS.stream().filter(it -> it.name().equals(name)).findFirst();
    }

    /** 该名字是否属于默认直接迁移项。 */
    public static boolean isDefaultName(String name) {
        return DEFAULT_ITEMS.stream().anyMatch(it -> it.name().equals(name));
    }

    /** 该条目是否属于「覆盖后需重启才生效」。 */
    public static boolean isRestartRequired(String name) {
        return RESTART_REQUIRED_NAMES.contains(name);
    }

    /** 全部条目名，用「、」连接（对应 py 的 LIST_TEXT）。 */
    public static String listText() {
        return joinNames(DEFAULT_ITEMS);
    }

    /** 默认项里的文件夹名，用「、」连接（对应 py 的 LIST_DIRS）。 */
    public static String listDirs() {
        return joinNames(DEFAULT_ITEMS.stream().filter(it -> it.kind() == ItemKind.DIR).toList());
    }

    /** 默认项里的文件名，用「、」连接（对应 py 的 LIST_FILES）。 */
    public static String listFiles() {
        return joinNames(DEFAULT_ITEMS.stream().filter(it -> it.kind() == ItemKind.FILE).toList());
    }

    /** 对应 py 的 LIST_LINES：「    文件夹：…\n    文件：  …」。 */
    public static String listLines() {
        return "    文件夹：" + listDirs() + "\n    文件：  " + listFiles();
    }

    private static String joinNames(List<MigrateItem> items) {
        return String.join("、", items.stream().map(MigrateItem::name).toList());
    }

    /** 去重后的条目名集合（自检用）。 */
    public static Set<String> allNames() {
        return new LinkedHashSet<>(ALL_ITEMS.stream().map(MigrateItem::name).toList());
    }
}
