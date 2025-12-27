package com.hagds.tpa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

public class tpa implements ModInitializer {

    // =============================
    //        配置与冷却字段
    // =============================

    // 配置数据类
    private static class TpaConfig {
        public int tpaCooldownSeconds = 30;
        public int backCooldownSeconds = 60;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "hagds_tpa.json";
    private static TpaConfig CONFIG = new TpaConfig();

    // 冷却时间（毫秒），默认值，启动时由配置覆盖
    private static long TPA_COOLDOWN_MS = 30_000;   // /tpa 冷却 30 秒
    private static long BACK_COOLDOWN_MS = 60_000;  // /back 冷却 60 秒

    // 冷却记录：上次使用时间
    private static final Map<UUID, Long> LAST_TPA = new HashMap<>();
    private static final Map<UUID, Long> LAST_BACK = new HashMap<>();

    // 记录玩家最近一次死亡地点
    private static final Map<UUID, DeathLocation> LAST_DEATHS = new HashMap<>();

    // 死亡位置数据
    private static class DeathLocation {
        final ServerWorld world;
        final double x, y, z;
        final float yaw, pitch;

        DeathLocation(ServerWorld world, double x, double y, double z, float yaw, float pitch) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    // =============================
    //          配置读写方法
    // =============================

    private static Path getConfigPath() {
        Path configDir = Paths.get("config");
        return configDir.resolve(CONFIG_FILE_NAME);
    }

    private static void loadConfig() {
        try {
            Path path = getConfigPath();
            if (Files.exists(path)) {
                String json = Files.readString(path);
                CONFIG = GSON.fromJson(json, TpaConfig.class);
            } else {
                // 第一次启动：创建默认配置文件
                saveConfig();
            }
        } catch (JsonSyntaxException | IOException e) {
            System.err.println("[hagds_tpa] 读取配置失败，使用默认配置。");
            CONFIG = new TpaConfig();
        }

        // 同步到实际冷却时间变量
        TPA_COOLDOWN_MS = CONFIG.tpaCooldownSeconds * 1000L;
        BACK_COOLDOWN_MS = CONFIG.backCooldownSeconds * 1000L;
    }

    private static void saveConfig() {
        try {
            Path path = getConfigPath();
            if (!Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            String json = GSON.toJson(CONFIG);
            Files.writeString(path, json);
        } catch (IOException e) {
            System.err.println("[hagds_tpa] 写入配置失败。");
        }
    }

    // =============================
    //            初始化
    // =============================

    @Override
    public void onInitialize() {

        // 先加载配置文件，初始化冷却时间
        loadConfig();

        // 注册所有指令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /tpaconfig 管理指令（修改冷却并保存配置）
            dispatcher.register(
                    CommandManager.literal("tpaconfig")
                            .requires(src -> src.hasPermissionLevel(2)) // 仅 OP
                            .then(CommandManager.literal("tpa_cooldown")
                                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0))
                                            .executes(ctx -> {
                                                int sec = IntegerArgumentType.getInteger(ctx, "seconds");
                                                CONFIG.tpaCooldownSeconds = sec;
                                                TPA_COOLDOWN_MS = sec * 1000L;
                                                saveConfig();
                                                ctx.getSource().sendFeedback(
                                                        () -> Text.of("§a已将 /tpa 冷却设置为 " + sec + " 秒。"),
                                                        false
                                                );
                                                return 1;
                                            })))
                            .then(CommandManager.literal("back_cooldown")
                                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0))
                                            .executes(ctx -> {
                                                int sec = IntegerArgumentType.getInteger(ctx, "seconds");
                                                CONFIG.backCooldownSeconds = sec;
                                                BACK_COOLDOWN_MS = sec * 1000L;
                                                saveConfig();
                                                ctx.getSource().sendFeedback(
                                                        () -> Text.of("§a已将 /back 冷却设置为 " + sec + " 秒。"),
                                                        false
                                                );
                                                return 1;
                                            })))
            );

            // /tpa 指令
            dispatcher.register(
                    CommandManager.literal("tpa")
                            .then(CommandManager.argument("target", EntityArgumentType.player())
                                    .executes(context -> {
                                        ServerCommandSource source = context.getSource();
                                        ServerPlayerEntity sourcePlayer = source.getPlayer();
                                        ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "target");
                                        if (sourcePlayer == null) return 0;

                                        // 冷却检查
                                        long now = System.currentTimeMillis();
                                        Long lastUse = LAST_TPA.get(sourcePlayer.getUuid());
                                        if (lastUse != null) {
                                            long remain = TPA_COOLDOWN_MS - (now - lastUse);
                                            if (remain > 0) {
                                                long sec = remain / 1000;
                                                sourcePlayer.sendMessage(
                                                        Text.of("§c传送冷却中，还有 " + sec + " 秒。"),
                                                        false
                                                );
                                                return 0;
                                            }
                                        }
                                        LAST_TPA.put(sourcePlayer.getUuid(), now);

                                        // 不能传送自己
                                        if (sourcePlayer.equals(targetPlayer)) {
                                            sourcePlayer.sendMessage(Text.of("§c让你 tp 自己了吗？不给！"), false);
                                            return 0;
                                        }

                                        // 遍历所有世界寻找目标玩家所在世界（支持跨维度）
                                        net.minecraft.server.MinecraftServer server = source.getServer();
                                        ServerWorld targetWorld = source.getWorld(); // 默认当前世界
                                        for (ServerWorld world : server.getWorlds()) {
                                            if (world.getPlayerByUuid(targetPlayer.getUuid()) != null) {
                                                targetWorld = world;
                                                break;
                                            }
                                        }

                                        // 执行传送
                                        sourcePlayer.teleport(
                                                targetWorld,
                                                targetPlayer.getX(),
                                                targetPlayer.getY(),
                                                targetPlayer.getZ(),
                                                java.util.Collections.emptySet(),
                                                targetPlayer.getYaw(),
                                                targetPlayer.getPitch(),
                                                false
                                        );

                                        sourcePlayer.sendMessage(
                                                Text.of("§a已传送至 " + targetPlayer.getName().getString()),
                                                false
                                        );
                                        return 1;
                                    }))
            );

            // /back 指令
            dispatcher.register(
                    CommandManager.literal("back")
                            .executes(context -> {
                                ServerPlayerEntity player = context.getSource().getPlayer();
                                if (player == null) return 0;

                                // 冷却检查
                                long now = System.currentTimeMillis();
                                Long lastUse = LAST_BACK.get(player.getUuid());
                                if (lastUse != null) {
                                    long remain = BACK_COOLDOWN_MS - (now - lastUse);
                                    if (remain > 0) {
                                        long sec = remain / 1000;
                                        player.sendMessage(
                                                Text.of("§c/back 冷却中，还有 " + sec + " 秒。"),
                                                false
                                        );
                                        return 0;
                                    }
                                }
                                LAST_BACK.put(player.getUuid(), now);

                                // 读取最近死亡点
                                DeathLocation loc = LAST_DEATHS.get(player.getUuid());
                                if (loc == null) {
                                    player.sendMessage(Text.of("§c没有找到最近一次死亡地点。"), false);
                                    return 0;
                                }

                                player.teleport(
                                        loc.world,
                                        loc.x,
                                        loc.y,
                                        loc.z,
                                        java.util.Collections.emptySet(),
                                        loc.yaw,
                                        loc.pitch,
                                        false
                                );

                                player.sendMessage(Text.of("§a已回到最近一次死亡地点。"), false);
                                LAST_DEATHS.remove(player.getUuid());
                                return 1;
                            })
            );
        });

        // 死亡事件：记录死亡点
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity player) {
                ServerCommandSource source = player.getCommandSource();
                ServerWorld world = source.getWorld();

                LAST_DEATHS.put(
                        player.getUuid(),
                        new DeathLocation(
                                world,
                                player.getX(),
                                player.getY(),
                                player.getZ(),
                                player.getYaw(),
                                player.getPitch()
                        )
                );
            }
        });
    }
}
