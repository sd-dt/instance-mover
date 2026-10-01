package dev.sd_dt.instancemover.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sd_dt.instancemover.TestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「两个文件是否真的相同」的判定，对应 mc_transfer.py v1.1 的 {@code files_identical()}。
 *
 * <p>这是 v1.1 修过的真实缺陷：只比大小 + 时间会漏覆盖，所以 ≤ 4 MB 必须比内容、
 * &gt; 4 MB 一律不跳过。</p>
 */
class FilesIdenticalTest {

    private static final long FIXED_TIME = 1_700_000_000L;

    private static Path file(Path dir, String name, String text, long mtime) {
        Path path = dir.resolve(name);
        TestSupport.write(path, text);
        TestSupport.setMtime(path, mtime);
        return path;
    }

    @Test
    @DisplayName("大小、时间、内容都相同 → true")
    void identicalFiles(@TempDir Path dir) {
        Path a = file(dir, "a.txt", "完全相同的内容", FIXED_TIME);
        Path b = file(dir, "b.txt", "完全相同的内容", FIXED_TIME);
        assertTrue(CopyEngine.filesIdentical(a, b));
    }

    @Test
    @DisplayName("大小相同、时间不同 → false")
    void differentMtime(@TempDir Path dir) {
        Path a = file(dir, "a.txt", "内容", FIXED_TIME);
        Path b = file(dir, "b.txt", "内容", FIXED_TIME + 1);
        assertFalse(CopyEngine.filesIdentical(a, b));
    }

    @Test
    @DisplayName("时间相同、大小不同 → false")
    void differentSize(@TempDir Path dir) {
        Path a = file(dir, "a.txt", "内容", FIXED_TIME);
        Path b = file(dir, "b.txt", "内容 + 更多", FIXED_TIME);
        assertFalse(CopyEngine.filesIdentical(a, b));
    }

    @Test
    @DisplayName("大小与时间完全相同、内容不同（1024 B）→ false（必须覆盖）")
    void sameSizeSameTimeDifferentContentSmall(@TempDir Path dir) {
        byte[] a = new byte[1024];
        byte[] b = new byte[1024];
        java.util.Arrays.fill(a, (byte) 'A');
        java.util.Arrays.fill(b, (byte) 'B');
        Path pa = dir.resolve("a.bin");
        Path pb = dir.resolve("b.bin");
        TestSupport.writeBytes(pa, a);
        TestSupport.writeBytes(pb, b);
        TestSupport.setMtime(pa, FIXED_TIME);
        TestSupport.setMtime(pb, FIXED_TIME);
        assertFalse(CopyEngine.filesIdentical(pa, pb));
    }

    @Test
    @DisplayName("超过 4 MB 的文件一律不跳过（哪怕大小时间都相同）")
    void overVerifyLimitIsNeverSkipped(@TempDir Path dir) {
        int size = CopyEngine.VERIFY_LIMIT + 4096;
        byte[] content = new byte[size];
        java.util.Arrays.fill(content, (byte) 'A');
        Path pa = dir.resolve("a.bin");
        Path pb = dir.resolve("b.bin");
        TestSupport.writeBytes(pa, content);
        TestSupport.writeBytes(pb, content);
        TestSupport.setMtime(pa, FIXED_TIME);
        TestSupport.setMtime(pb, FIXED_TIME);
        assertFalse(CopyEngine.filesIdentical(pa, pb),
                "> 4 MB 必须保守判定为「不相同」，宁可重写一次");
    }

    @Test
    @DisplayName("正好 4 MB（等于阈值）仍然做内容比对")
    void exactlyAtLimitIsCompared(@TempDir Path dir) {
        byte[] a = new byte[CopyEngine.VERIFY_LIMIT];
        byte[] b = new byte[CopyEngine.VERIFY_LIMIT];
        java.util.Arrays.fill(a, (byte) 'A');
        java.util.Arrays.fill(b, (byte) 'B');
        Path pa = dir.resolve("a.bin");
        Path pb = dir.resolve("b.bin");
        TestSupport.writeBytes(pa, a);
        TestSupport.writeBytes(pb, b);
        TestSupport.setMtime(pa, FIXED_TIME);
        TestSupport.setMtime(pb, FIXED_TIME);
        assertFalse(CopyEngine.filesIdentical(pa, pb), "阈值内必须逐块比内容，发现不同");
    }

    @Test
    @DisplayName("空文件 + 相同时间 → true")
    void emptyFiles(@TempDir Path dir) {
        Path a = file(dir, "a.txt", "", FIXED_TIME);
        Path b = file(dir, "b.txt", "", FIXED_TIME);
        assertTrue(CopyEngine.filesIdentical(a, b));
    }

    @Test
    @DisplayName("文件不存在 → false（不抛异常）")
    void missingFile(@TempDir Path dir) {
        Path a = file(dir, "a.txt", "内容", FIXED_TIME);
        assertFalse(CopyEngine.filesIdentical(a, dir.resolve("does-not-exist.txt")));
        assertFalse(CopyEngine.filesIdentical(dir.resolve("nope-a"), dir.resolve("nope-b")));
    }

    @Test
    @DisplayName("256 KB 分块边界：多块文件内容一致 → true；末块不同 → false")
    void chunkBoundaries(@TempDir Path dir) {
        byte[] same = new byte[CopyEngine.CHUNK_BYTES * 2];
        java.util.Arrays.fill(same, (byte) 'X');
        Path a = dir.resolve("a.bin");
        Path b = dir.resolve("b.bin");
        TestSupport.writeBytes(a, same);
        TestSupport.writeBytes(b, same.clone());
        TestSupport.setMtime(a, FIXED_TIME);
        TestSupport.setMtime(b, FIXED_TIME);
        assertTrue(CopyEngine.filesIdentical(a, b), "整块 + 整块的内容必须判定相同");

        byte[] tailDifferent = same.clone();
        tailDifferent[tailDifferent.length - 1] = 'Y';
        TestSupport.writeBytes(b, tailDifferent);
        TestSupport.setMtime(b, FIXED_TIME);
        assertFalse(CopyEngine.filesIdentical(a, b), "只差最后一个字节也必须发现");
    }

    @Test
    @DisplayName("UTF-8 中文内容比对不受编码影响")
    void chineseContent(@TempDir Path dir) {
        Path a = file(dir, "a.txt", "老实例的配置：视角 90", FIXED_TIME);
        Path b = file(dir, "b.txt", "老实例的配置：视角 90", FIXED_TIME);
        assertTrue(CopyEngine.filesIdentical(a, b));
        Path c = file(dir, "c.txt", "老实例的配置：视角 91", FIXED_TIME);
        assertFalse(CopyEngine.filesIdentical(a, c));
    }
}
