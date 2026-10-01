package dev.sd_dt.instancemover.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引擎自检（{@code -Dinstance_mover.selftest=true} 走的就是这里），
 * 结论必须是 {@code SELFTEST PASS n/n}。
 */
class EngineSelfTestTest {

    @Test
    @DisplayName("自检全部通过，结论为 SELFTEST PASS n/n")
    void selfTestPasses() {
        EngineSelfTest.Result result = EngineSelfTest.run();

        assertTrue(result.passed(), "自检必须全通过：" + result.lines());
        assertTrue(result.totalCount() >= 10, "检查项应有 10 项以上，实际 " + result.totalCount());
        assertEquals(result.totalCount(), result.passedCount());
        assertTrue(result.summary().matches("SELFTEST PASS \\d+/\\d+"), result.summary());
        assertEquals("SELFTEST PASS %d/%d".formatted(result.totalCount(), result.totalCount()),
                result.summary());
        assertTrue(result.lines().getLast().equals(result.summary()), "最后一行是结论");
    }

    @Test
    @DisplayName("逐项检查的输出都是 [通过] / [失败] 前缀")
    void linesAreReadable() {
        EngineSelfTest.Result result = EngineSelfTest.run();

        assertFalse(result.lines().isEmpty());
        for (String line : result.lines()) {
            assertTrue(line.startsWith("[通过] ") || line.startsWith("[失败] ")
                            || line.startsWith("SELFTEST "),
                    "意外的一行：" + line);
        }
        assertTrue(result.lines().stream().anyMatch(line -> line.contains("清单数量")), result.lines().toString());
        assertTrue(result.lines().stream().anyMatch(line -> line.contains("备份")), result.lines().toString());
        assertTrue(result.lines().stream().anyMatch(line -> line.contains("取消")), result.lines().toString());
        assertTrue(result.lines().stream().anyMatch(line -> line.contains("覆盖")), result.lines().toString());
    }

    @Test
    @DisplayName("自检不碰玩家实例：只在临时目录里跑，跑完自己清理")
    void selfTestUsesTempDirectoryOnly() {
        String before = System.getProperty("user.dir");
        EngineSelfTest.Result result = EngineSelfTest.run();
        String after = System.getProperty("user.dir");

        assertTrue(result.passed());
        assertEquals(before, after, "自检不该切换工作目录");
    }
}
