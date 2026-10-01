package dev.sd_dt.instancemover.engine;

/**
 * 转移过程的进度 / 日志 / 结果回调，替代 mc_transfer.py v1.1 里的消息队列
 * （py 的 {@code ("log" / "phase" / "progress" / "done" / "cancelled" / "error")} 消息）。
 *
 * <p>所有回调都在 {@link TransferRunner} 的执行线程里触发（默认是后台守护线程），
 * 界面实现里需要自行切回渲染线程（例如 {@code Minecraft.getInstance().execute(...)}）。</p>
 */
public interface TransferListener {

    /** 一行日志（界面日志区 / 结果页）。 */
    default void onLog(String line) {
    }

    /** 阶段标题；{@code indeterminate=true} 表示进度不确定（统计 / 备份阶段）。 */
    default void onPhase(String text, boolean indeterminate) {
    }

    /** 进度：{@code done} / {@code total}，分母是 {@code max(total, 1)}。 */
    default void onProgress(long done, long total) {
    }

    /** 已写入字节数（只算转移阶段，不含备份）；引擎每完成一个文件后回调一次。 */
    default void onBytesWritten(long bytes) {
    }

    /** 正常结束。 */
    default void onDone(TransferReport report) {
    }

    /** 用户取消（已经复制的内容不会回滚）。 */
    default void onCancelled(TransferReport report) {
    }

    /** 未预期错误（不静默吞掉）。 */
    default void onError(Throwable error) {
    }
}
