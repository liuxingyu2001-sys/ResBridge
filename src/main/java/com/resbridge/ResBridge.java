package com.resbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class ResBridge extends JavaPlugin {

    private static ResBridge instance;
    private RedisManager redisManager;
    private DatabaseManager databaseManager;
    private ServerAListener aListener;
    private boolean resA, plotA, resB, plotB;

    public static ResBridge getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

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
            databaseManager = null;
        }

        applyModes();

        getCommand("resbridge").setExecutor(this::onResBridgeCommand);
        getLogger().info("ResBridge 已启用 (res=" + modeName(resA, resB) + ", plot=" + modeName(plotA, plotB) + ")");
    }

    /**
     * 按配置注册 A 模式监听与 B 模式指令，reload 时可重复调用以切换角色。
     */
    private void applyModes() {
        String resMode = getConfig().getString("res.mode", "B").toUpperCase();
        String plotMode = getConfig().getString("plot.mode", "B").toUpperCase();
        resA = "A".equals(resMode);
        plotA = "A".equals(plotMode);
        resB = "B".equals(resMode);
        plotB = "B".equals(plotMode);
        if (!resA && !resB) getLogger().warning("res.mode 配置无效: " + resMode + "（应为 A 或 B）");
        if (!plotA && !plotB) getLogger().warning("plot.mode 配置无效: " + plotMode + "（应为 A 或 B）");

        // A模式：注册加入监听（先注销旧监听，支持 reload 切换角色）
        if (aListener != null) {
            HandlerList.unregisterAll(aListener);
            aListener = null;
        }
        if (resA || plotA) {
            aListener = new ServerAListener(this, resA, plotA);
            getServer().getPluginManager().registerEvents(aListener, this);
        }

        PluginCommand resCmd = getCommand("res");
        PluginCommand plotCmd = getCommand("plot");
        // 与真实插件指令名冲突时注册会 fallback，getCommand 可能拿不到
        if (resCmd == null) getLogger().severe("指令 res 注册失败（可能与其他插件冲突），领地功能不可用");
        if (plotCmd == null) getLogger().severe("指令 plot 注册失败（可能与其他插件冲突），地皮功能不可用");

        // B模式：注册指令
        if (resB || plotB) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            ServerBCommand handler = new ServerBCommand(this, resB, plotB);
            getServer().getPluginManager().registerEvents(handler, this);
            if (resB && resCmd != null) {
                resCmd.setExecutor(handler);
                resCmd.setTabCompleter(handler);
            }
            if (plotB && plotCmd != null) {
                plotCmd.setExecutor(handler);
                plotCmd.setTabCompleter(handler);
            }
        }

        // A模式：将本插件注册的指令转发给真实插件，避免覆盖
        if (resA && resCmd != null) {
            resCmd.setExecutor((sender, cmd, label, args) ->
                    Bukkit.dispatchCommand(sender, "residence:res " + String.join(" ", args)));
            resCmd.setTabCompleter(null);
        }
        if (plotA && plotCmd != null) {
            plotCmd.setExecutor((sender, cmd, label, args) ->
                    Bukkit.dispatchCommand(sender, "plotsquared:plot " + String.join(" ", args)));
            plotCmd.setTabCompleter(null);
        }
    }

    private static String modeName(boolean a, boolean b) {
        if (a) return "A";
        if (b) return "B";
        return "无效";
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
            redisManager = null;
            databaseManager = null;
            boolean redisOk = true;
            try {
                redisManager = new RedisManager(this);
            } catch (Exception e) {
                redisOk = false;
                getLogger().severe("Redis 重连失败: " + e.getMessage());
            }
            try {
                databaseManager = new DatabaseManager(this);
            } catch (Exception e) {
                getLogger().warning("MySQL 重连失败（领地补全将不可用）: " + e.getMessage());
                databaseManager = null;
            }
            applyModes();
            sender.sendMessage(msg(redisOk ? "reload-success" : "redis-error"));
            return true;
        }
        sender.sendMessage(colorize("&6/ResBridge reload &r- 重载配置"));
        return true;
    }

    @Override
    public void onDisable() {
        if (redisManager != null) redisManager.close();
        if (databaseManager != null) databaseManager.close();
        instance = null;
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
