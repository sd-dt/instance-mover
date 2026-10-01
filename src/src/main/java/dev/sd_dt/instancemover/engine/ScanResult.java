package dev.sd_dt.instancemover.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次实例扫描的结果，对应 mc_transfer.py v1.1 的 {@code (present, missing, extras)}。
 *
 * <p>注意：{@code extras} 只包含「老实例里真实存在」的询问项；默认项里的
 * {@code .voxy} / {@code Distant_Horizons_server_data} 已按用户决定搬进默认项，
 * 所以它们只会出现在 {@code present} 里。</p>
 *
 * @param present 存在的默认项
 * @param missing 缺失的默认项（带原因）
 * @param extras  默认列表之外、老实例里发现的玩家数据
 */
public record ScanResult(List<ScannedItem> present, List<ScannedItem> missing, List<ScannedItem> extras) {

    public ScanResult {
        present = List.copyOf(present);
        missing = List.copyOf(missing);
        extras = List.copyOf(extras);
    }

    /** present + extras：可以直接加入转移列表的条目。 */
    public List<ScannedItem> transferable() {
        List<ScannedItem> all = new ArrayList<>(present.size() + extras.size());
        all.addAll(present);
        all.addAll(extras);
        return List.copyOf(all);
    }

    /** present + missing + extras，全部扫描到的条目。 */
    public List<ScannedItem> all() {
        List<ScannedItem> all = new ArrayList<>(present.size() + missing.size() + extras.size());
        all.addAll(present);
        all.addAll(missing);
        all.addAll(extras);
        return List.copyOf(all);
    }

    public boolean isEmpty() {
        return present.isEmpty() && missing.isEmpty() && extras.isEmpty();
    }
}
