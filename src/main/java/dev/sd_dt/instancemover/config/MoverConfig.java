package dev.sd_dt.instancemover.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组标记文件 {@code config/instance_mover.json} 的读写。
 *
 * <p>记录：是否已经处理过首次提示（{@code firstRunDone}）、上次选择的老实例（{@code lastSource}）、
 * 两个开关（{@code backupEnabled} / {@code skipSameEnabled}）。</p>
 *
 * <p>纯 Java（只依赖 gson，MC 自带），可直接单测。写入用「同目录临时文件 + 原子替换」，
 * 文件损坏或缺失时一律回退默认值，绝不抛异常把游戏搞崩。</p>
 */
public final class MoverConfig {

    /** 文件名（放在实例的 config 目录下）。 */
    public static final String FILE_NAME = "instance_mover.json";

    /** 配置目录名。 */
    public static final String CONFIG_DIR_NAME = "config";

    private static final Logger LOGGER = LoggerFactory.getLogger("instance_mover");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean firstRunDone;
    private String lastSource = "";
    private boolean backupEnabled = true;
    private boolean skipSameEnabled = true;

    /** 标记文件默认位置：{@code <游戏目录>/config/instance_mover.json}。 */
    public static Path defaultFile(Path gameDirectory) {
        return gameDirectory.resolve(CONFIG_DIR_NAME).resolve(FILE_NAME);
    }

    /** 读取配置；文件不存在或损坏时返回默认值（不抛异常）。 */
    public static MoverConfig load(Path file) {
        MoverConfig config = new MoverConfig();
        if (file == null || !Files.isRegularFile(file)) {
            return config;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                LOGGER.warn("配置文件 {} 不是 JSON 对象，使用默认值。", file);
                return config;
            }
            JsonObject json = parsed.getAsJsonObject();
            config.firstRunDone = getBoolean(json, "firstRunDone", false);
            config.lastSource = getString(json, "lastSource", "");
            config.backupEnabled = getBoolean(json, "backupEnabled", true);
            config.skipSameEnabled = getBoolean(json, "skipSameEnabled", true);
        } catch (Exception ex) {
            LOGGER.warn("读取配置文件 {} 失败，使用默认值：{}", file, ex.toString());
        }
        return config;
    }

    private static boolean getBoolean(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return value.getAsBoolean();
    }

    private static String getString(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        return value.getAsString();
    }

    /** 写入配置：先写同目录临时文件，再原子替换。 */
    public void save(Path file) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("配置文件路径没有父目录：" + file);
        }
        Files.createDirectories(parent);

        JsonObject json = new JsonObject();
        json.addProperty("firstRunDone", firstRunDone);
        json.addProperty("lastSource", lastSource);
        json.addProperty("backupEnabled", backupEnabled);
        json.addProperty("skipSameEnabled", skipSameEnabled);

        Path temp = Files.createTempFile(parent, FILE_NAME + ".", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 保存但不抛异常（界面/首启逻辑用，失败只记日志）。 */
    public boolean saveQuietly(Path file) {
        try {
            save(file);
            return true;
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("保存配置文件 {} 失败：{}", file, ex.toString());
            return false;
        }
    }

    public boolean isFirstRunDone() {
        return firstRunDone;
    }

    /**
     * 设置「首次运行引导是否已被处理」。
     *
     * <p><b>不变量（t23）</b>：只有两条路径允许把它写成 {@code true} ——</p>
     * <ol>
     *   <li>玩家在迁移界面点了「不再提示」（{@code FirstRunGate.markNeverAskAgain}）；</li>
     *   <li>玩家完成了一次**真实的成功迁移**（{@code ProgressScreen.finish} 里
     *       {@code report != null && !cancelled && !hasError}）。</li>
     * </ol>
     * <p>绝不允许在「界面没弹过 / 玩家没做任何选择」时隐式写成 true；写回 {@code false} 则是
     * 开放操作（关于页的「重新显示迁移引导」按钮，见 {@link #resetFirstRun()}）。</p>
     */
    public MoverConfig setFirstRunDone(boolean firstRunDone) {
        this.firstRunDone = firstRunDone;
        return this;
    }

    /**
     * 把「首次运行引导」重置为「未处理」：下次启动会重新弹出迁移界面（t23，供关于页按钮使用）。
     *
     * <p>只改内存值，**落盘由调用方决定**（关于页会立刻 {@link #save(Path)}；保存失败时自己回滚）。</p>
     */
    public MoverConfig resetFirstRun() {
        return setFirstRunDone(false);
    }

    public String lastSource() {
        return lastSource;
    }

    public MoverConfig setLastSource(String lastSource) {
        this.lastSource = lastSource == null ? "" : lastSource;
        return this;
    }

    public boolean isBackupEnabled() {
        return backupEnabled;
    }

    public MoverConfig setBackupEnabled(boolean backupEnabled) {
        this.backupEnabled = backupEnabled;
        return this;
    }

    public boolean isSkipSameEnabled() {
        return skipSameEnabled;
    }

    public MoverConfig setSkipSameEnabled(boolean skipSameEnabled) {
        this.skipSameEnabled = skipSameEnabled;
        return this;
    }
}
