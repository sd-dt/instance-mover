package dev.sd_dt.instancemover.engine;

/**
 * 用户取消转移时抛出，对应 mc_transfer.py v1.1 的 {@code TransferCancelled}。
 *
 * <p>在「每个文件 / 每个目录之前」检查取消标志，命中就抛出；已经复制的内容不回滚。</p>
 */
public class TransferCancelled extends Exception {

    private static final long serialVersionUID = 1L;

    public TransferCancelled() {
        super("已取消");
    }

    public TransferCancelled(String message) {
        super(message);
    }
}
