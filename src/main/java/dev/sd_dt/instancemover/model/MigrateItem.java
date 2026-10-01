package dev.sd_dt.instancemover.model;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 一条可迁移的玩家数据条目。
 *
 * <p>对应 mc_transfer.py v1.1 里的 {@code (名称, 类型, 说明)} / {@code (名称, 类型, 说明, 默认勾选)} 元组。</p>
 *
 * @param name            条目名，同时是相对实例根目录的路径（例如 {@code config}、{@code options.txt}）；
 *                        必须是**单个相对名字**，见 {@link #validateName(String)}
 * @param kind            文件夹还是文件
 * @param description     作用说明，逐字照抄 v1.1 的文案（界面直接显示）
 * @param defaultOn       是否默认勾选（默认项恒为 {@code true}）
 * @param restartRequired 覆盖后是否需要重启游戏才生效（config / options.txt / optionsshaders.txt /
 *                        iris.properties / optionsof.txt / defaultconfigs）
 */
public record MigrateItem(String name, ItemKind kind, String description, boolean defaultOn, boolean restartRequired) {

    public MigrateItem {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
        validateName(name);
    }

    /**
     * 条目名必须是「实例根目录下的单个相对名字」（审查 F3 的防御性校验）。
     *
     * <p>清单会拿 {@code name} 直接 {@code resolve} 到实例根目录上，所以必须挡住空名、绝对路径、
     * 带路径分隔符、{@code .} / {@code ..} 路径段、以及 {@code C:xxx} 这种盘符相对写法，
     * 否则一条脏条目就可能读到/写到实例外面去。</p>
     *
     * @throws IllegalArgumentException 名字不是合法的条目名（消息为中文，可直接显示）
     */
    private static void validateName(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("条目名不能为空。");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("条目名不能包含路径分隔符（/ 或 \\）：" + name);
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("条目名不能是路径段「.」或「..」（会跑到实例外面）：" + name);
        }
        if (name.length() >= 2 && name.charAt(1) == ':') {
            throw new IllegalArgumentException("条目名必须是相对名，不能带盘符：" + name);
        }
        try {
            if (Path.of(name).isAbsolute()) {
                throw new IllegalArgumentException("条目名必须是实例根目录下的相对名，不能是绝对路径：" + name);
            }
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("条目名不是合法的路径名：" + name, ex);
        }
    }

    /** 在某个实例根目录下解析出这一条的完整路径。 */
    public Path resolveIn(Path instanceRoot) {
        return instanceRoot.resolve(name);
    }

    /** 类型的中文名（「文件夹」/「文件」）。 */
    public String label() {
        return kind.label();
    }

    /** 是否为默认直接迁移项（对应 py 的 DEFAULT_ITEMS）。 */
    public boolean isDefaultItem() {
        return MigrateCatalog.isDefaultName(name);
    }
}
