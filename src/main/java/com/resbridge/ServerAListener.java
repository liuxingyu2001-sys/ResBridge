package com.resbridge;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ServerAListener implements Listener {

    private final ResBridge plugin;
    private final boolean handleRes;
    private final boolean handlePlot;

    public ServerAListener(ResBridge plugin, boolean handleRes, boolean handlePlot) {
        this.plugin = plugin;
        this.handleRes = handleRes;
        this.handlePlot = handlePlot;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        RedisManager redis = plugin.getRedisManager();
        if (redis == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 记录玩家所在服务器
            String serverName = plugin.getConfig().getString("server-name", "");
            if (!serverName.isEmpty()) {
                if (handleRes) redis.setLastServer(player.getUniqueId(), "res", serverName);
                if (handlePlot) redis.setLastServer(player.getUniqueId(), "plot", serverName);
            }

            // 处理待传送请求
            String data = redis.getPendingTeleport(player.getUniqueId());
            if (data != null) {
                int sep = data.indexOf(':');
                if (sep > 0) {
                    String type = data.substring(0, sep);
                    boolean shouldHandle = ("res".equals(type) && handleRes) || ("plot".equals(type) && handlePlot);
                    if (shouldHandle) {
                        redis.deletePendingTeleport(player.getUniqueId());
                        int delay = plugin.getConfig().getInt("teleport-delay", 40);
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (!player.isOnline()) return;
                            executeWithRetry(player, data, 3);
                        }, delay);
                    }
                }
            }

            // 写入领地列表供B服补全
            if (handleRes) {
                List<String> resNames = getResidenceNames(player);
                if (!resNames.isEmpty()) {
                    redis.setResList(player.getUniqueId(), resNames);
                    DatabaseManager db = plugin.getDatabaseManager();
                    if (db != null && db.isEnabled()) {
                        db.saveResidenceNames(player.getUniqueId(), resNames);
                    }
                }
            }
        });
    }

    private void executeWithRetry(Player player, String data, int retries) {
        if (!player.isOnline()) return;

        int sep = data.indexOf(':');
        if (sep < 0) return;

        String type = data.substring(0, sep);
        String target = data.substring(sep + 1);

        boolean success = switch (type) {
            case "res" -> {
                if (!handleRes || Bukkit.getPluginManager().getPlugin("Residence") == null) {
                    player.sendMessage(plugin.msg("tp-error"));
                    yield false;
                }
                String args = target.isEmpty() ? "tp" : "tp " + target;
                boolean ok = Bukkit.dispatchCommand(player, "residence:res " + args);
                if (!ok) ok = Bukkit.dispatchCommand(player, "res " + args);
                yield ok;
            }
            case "plot" -> {
                if (!handlePlot || Bukkit.getPluginManager().getPlugin("PlotSquared") == null) {
                    player.sendMessage(plugin.msg("tp-error"));
                    yield false;
                }
                String plotArgs = target.isEmpty() ? "home" : target;
                boolean ok = Bukkit.dispatchCommand(player, "plotsquared:plot " + plotArgs);
                if (!ok) ok = Bukkit.dispatchCommand(player, "plot " + plotArgs);
                yield ok;
            }
            default -> {
                player.sendMessage(plugin.msg("tp-error"));
                yield false;
            }
        };

        if (!success && retries > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                executeWithRetry(player, data, retries - 1);
            }, 20);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getResidenceNames(Player player) {
        // 方式1：反射（使用 Residence 的 ClassLoader 解决 Paper 类加载隔离）
        List<String> names = getResidenceNamesByReflection(player);
        if (!names.isEmpty()) return names;

        // 方式2：MySQL 查询（之前反射成功时存入的数据）
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isEnabled()) {
            names = db.getResidenceNames(player.getUniqueId());
            if (!names.isEmpty()) return names;
        }

        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> getResidenceNamesByReflection(Player player) {
        try {
            Plugin resPlugin = Bukkit.getPluginManager().getPlugin("Residence");
            if (resPlugin == null) return List.of();
            ClassLoader cl = resPlugin.getClass().getClassLoader();

            Class<?> resClass = Class.forName("com.bekvon.bukkit.residence.Residence", true, cl);
            Object instance = resClass.getMethod("getInstance").invoke(null);

            Object manager = null;
            for (String name : new String[]{"getResidenceManager", "getResManager"}) {
                try {
                    manager = instance.getClass().getMethod(name).invoke(instance);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (manager == null) return List.of();

            // getByOwner —— 用声明类型匹配
            Class<?>[] types = {OfflinePlayer.class, Player.class, String.class, java.util.UUID.class};
            Object[] values = {player, player, player.getName(), player.getUniqueId()};
            for (int i = 0; i < types.length; i++) {
                try {
                    Object result = manager.getClass().getMethod("getByOwner", types[i]).invoke(manager, values[i]);
                    if (result instanceof Map<?, ?> m && !m.isEmpty()) {
                        return new ArrayList<>(m.keySet().stream().map(Object::toString).toList());
                    }
                } catch (Exception ignored) {}
            }

            // 回退：ResidencePlayer
            try {
                Object pm = instance.getClass().getMethod("getPlayerManager").invoke(instance);
                Object rp = null;
                for (Class<?> t : new Class<?>[]{OfflinePlayer.class, Player.class, String.class}) {
                    try {
                        Object v = t == String.class ? player.getName() : player;
                        rp = pm.getClass().getMethod("getResidencePlayer", t).invoke(pm, v);
                        if (rp != null) break;
                    } catch (NoSuchMethodException ignored) {}
                }
                if (rp != null) {
                    for (String m : new String[]{"getResList", "getResidences"}) {
                        try {
                            Object list = rp.getClass().getMethod(m).invoke(rp);
                            if (list instanceof java.util.Collection<?> coll && !coll.isEmpty()) {
                                return coll.stream().map(res -> {
                                    try {
                                        return res.getClass().getMethod("getName").invoke(res).toString();
                                    } catch (Exception e) {
                                        return res.toString();
                                    }
                                }).toList();
                            }
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            plugin.getLogger().warning("反射获取领地列表失败: " + e.getMessage());
        }
        return List.of();
    }
}
