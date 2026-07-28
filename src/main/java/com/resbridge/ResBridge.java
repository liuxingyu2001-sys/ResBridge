package com.resbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class ResBridge extends JavaPlugin {

    private static ResBridge instance;
    private RedisManager redisManager;
    private DatabaseManager databaseManager;
    private boolean resA, plotA, resB, plotB;

    public static ResBridge getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        String resMode = getConfig().getString("res.mode", "B").toUpperCase();
        String plotMode = getConfig().getString("plot.mode", "B").toUpperCase();
        resA = "A".equals(resMode);
        plotA = "A".equals(plotMode);
        resB = "B".equals(resMode);
        plotB = "B".equals(plotMode);

        try {
            redisManager = new RedisManager(this);
        } catch (Exception e) {
            getLogger().severe("Redis 连接失败: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            databaseManager = new DatabaseManager(this);
        } catch (Exception e) {
            getLogger().warning("MySQL 连接失败（领地补全将不可用）: " + e.getMessage());
        }

        // A模式：注册加入监听
        if (resA || plotA) {
            getServer().getPluginManager().registerEvents(new ServerAListener(this, resA, plotA), this);
        }

        // B模式：注册指令
        if (resB || plotB) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            ServerBCommand handler = new ServerBCommand(this, resB, plotB);
            if (resB) {
                getCommand("res").setExecutor(handler);
                getCommand("res").setTabCompleter(handler);
            }
            if (plotB) {
                getCommand("plot").setExecutor(handler);
                getCommand("plot").setTabCompleter(handler);
            }
        }

        // A模式：将本插件注册的指令转发给真实插件，避免覆盖
        if (resA) {
            getCommand("res").setExecutor((sender, cmd, label, args) ->
                    Bukkit.dispatchCommand(sender, "residence:res " + String.join(" ", args)));
        }
        if (plotA) {
            getCommand("plot").setExecutor((sender, cmd, label, args) ->
                    Bukkit.dispatchCommand(sender, "plotsquared:plot " + String.join(" ", args)));
        }

        getCommand("resbridge").setExecutor(this::onResBridgeCommand);
        getLogger().info("ResBridge 已启用 (res=" + resMode + ", plot=" + plotMode + ")");
    }

    private boolean onResBridgeCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("resbridge.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            reloadConfig();
            if (redisManager != null) redisManager.close();
            if (databaseManager != null) databaseManager.close();
            try {
                redisManager = new RedisManager(this);
                databaseManager = new DatabaseManager(this);
                sender.sendMessage(msg("reload-success"));
            } catch (Exception e) {
                sender.sendMessage(msg("redis-error"));
                getLogger().severe("Redis 重连失败: " + e.getMessage());
            }
            return true;
        }
        sender.sendMessage(colorize("&6/ResBridge reload &r- 重载配置"));
        return true;
    }

    @Override
    public void onDisable() {
        if (redisManager != null) redisManager.close();
        if (databaseManager != null) databaseManager.close();
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public Component msg(String key) {
        String prefix = getConfig().getString("messages.prefix", "&6[ResBridge] &r");
        String message = getConfig().getString("messages." + key, "");
        return colorize(prefix + message);
    }

    public static Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
