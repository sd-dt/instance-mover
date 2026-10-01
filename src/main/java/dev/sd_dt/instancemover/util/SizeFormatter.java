package dev.sd_dt.instancemover.util;

import java.util.Locale;

/**
 * 体积格式化，对应 mc_transfer.py v1.1 的 {@code human_size()}（1024 进制）。
 */
public final class SizeFormatter {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private SizeFormatter() {
    }

    /**
     * 1024 进制的人类可读体积：{@code 0 B}、{@code 1.2 MB}、{@code 3.0 GB}。
     *
     * <p>对应 py：{@code "B"} 用整数、其余保留 1 位小数，到 TB 封顶。</p>
     */
    public static String humanSize(long bytes) {
        double n = bytes;
        for (String unit : UNITS) {
            if (n < 1024 || "TB".equals(unit)) {
                if ("B".equals(unit)) {
                    return "%d B".formatted((long) n);
                }
                return String.format(Locale.ROOT, "%.1f %s", n, unit);
            }
            n /= 1024.0;
        }
        return "%d B".formatted(bytes);
    }
}
