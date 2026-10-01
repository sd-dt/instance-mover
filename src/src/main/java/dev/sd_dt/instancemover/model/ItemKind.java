package dev.sd_dt.instancemover.model;

/**
 * 清单条目的类型：文件夹 / 文件。
 *
 * <p>对应 mc_transfer.py v1.1 里的 {@code "dir"} / {@code "file"} 字符串。</p>
 */
public enum ItemKind {

    /** 文件夹（py: "dir"）。 */
    DIR("dir", "文件夹"),

    /** 文件（py: "file"）。 */
    FILE("file", "文件");

    private final String pyName;
    private final String label;

    ItemKind(String pyName, String label) {
        this.pyName = pyName;
        this.label = label;
    }

    /** py 里用的字符串字面量。 */
    public String pyName() {
        return pyName;
    }

    /** 界面与日志里的中文名（「文件夹」/「文件」）。 */
    public String label() {
        return label;
    }

    public static ItemKind fromPyName(String pyName) {
        for (ItemKind kind : values()) {
            if (kind.pyName.equals(pyName)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知的条目类型：" + pyName);
    }
}
