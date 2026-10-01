package dev.sd_dt.instancemover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M0 骨架冒烟测试：证明「JUnit 5 单测链路能跑」+「processResources 展开了 ${version}」+「资源进得了 classpath」。
 *
 * <p>刻意不加载任何 Minecraft / Loader 类，避免在没有游戏环境的测试 JVM 里踩坑。</p>
 */
class SkeletonSmokeTest {

    private static byte[] readResourceBytes(String path) throws IOException {
        try (InputStream in = SkeletonSmokeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 上找不到资源：" + path);
            return in.readAllBytes();
        }
    }

    private static String readResource(String path) throws IOException {
        return new String(readResourceBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("JUnit 5 链路可用")
    void junitChainWorks() {
        assertEquals(4, 2 + 2);
    }

    @Test
    @DisplayName("fabric.mod.json 已展开 version 且元数据正确")
    void modMetadataIsProcessed() throws IOException {
        String json = readResource("/fabric.mod.json");

        assertFalse(json.contains("${version}"), "processResources 没有展开 ${version}");
        assertTrue(json.contains("\"id\": \"instance_mover\""), "mod id 不对");
        // 刻意不写死版本号：版本随 gradle.properties 的 mod_version 走，
        // 这里只断言「已展开成 x.y.z 形式」，避免每次升版本都要改测试。
        assertTrue(json.matches("(?s).*\"version\"\\s*:\\s*\"\\d+\\.\\d+\\.\\d+\".*"),
                "mod 版本没有展开成 x.y.z 形式：" + json.replaceAll("(?s).*(\"version\"\\s*:\\s*\"[^\"]*\").*", "$1"));
        assertTrue(json.contains("实例酱的搬家服务"), "模组中文名不对（注意 UTF-8 过滤编码）");
        assertTrue(json.contains("\"environment\": \"client\""), "environment 必须是 client");
        assertTrue(
                json.contains("\"dev.sd_dt.instancemover.InstanceMoverClient\""),
                "entrypoints.client 指向的类不对");
    }

    @Test
    @DisplayName("入口类与资源都在编译产物里")
    void classAndAssetsArePresent() throws IOException {
        assertNotNull(
                SkeletonSmokeTest.class.getResourceAsStream(
                        "/dev/sd_dt/instancemover/InstanceMoverClient.class"),
                "InstanceMoverClient.class 没有进 classpath");

        String lang = readResource("/assets/instance_mover/lang/zh_cn.json");
        assertTrue(lang.contains("实例酱的搬家服务"), "zh_cn.json 内容不对");

        byte[] icon = readResourceBytes("/assets/instance_mover/icon.png");
        assertTrue(icon.length > 1000, "icon.png 太小，不像是真图");
        assertEquals((byte) 0x89, icon[0], "icon.png 魔数错误");
        assertEquals('P', icon[1], "icon.png 魔数错误");
    }
}
