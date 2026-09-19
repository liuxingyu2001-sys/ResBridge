package com.resbridge;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerBCommand implements CommandExecutor, TabCompleter, Listener {

    private final ResBridge plugin;
    private final boolean handleRes;
    private final boolean handlePlot;

    private final Map<UUID, CacheEntry> resCache = new ConcurrentHashMap<>();
    private final Set<UUID> refreshingResCache = ConcurrentHashMap.newKeySet();
    private static final long CACHE_TTL = 30_000;
    private static final int CACHE_MAX_SIZE = 256;

    private record CacheEntry(List<String> items, long timestamp) {
        boolean expired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL;
        }
    }

    public ServerBCommand(ResBridge plugin, boolean handleRes, boolean handlePlot) {
        this.plugin = plugin;
        this.handleRes = handleRes;
        this.handlePlot = handlePlot;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("此指令只能由玩家执行");
            return true;
        }

        String cmdName = command.getName().toLowerCase();

        if (cmdName.equals("res") && handleRes) {
            return handleRes(player, args);
        } else if (cmdName.equals("plot") && handlePlot) {
            return handlePlot(player, args);
        }

        return false;
    }

    private boolean handleRes(Player player, String[] args) {
        if (args.length < 1 || !args[0].equalsIgnoreCase("tp")) {
            player.sendMessage(plugin.msg("invalid-args"));
            return true;
        }
        String resName = args.length >= 2 ? args[1] : "";
        return teleport(player, "res", resName);
    }

    private boolean handlePlot(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(plugin.msg("invalid-args"));
            return true;
        }
        String sub = args[0].toLowerCase();
        if (!(sub.equals("home") || sub.equals("h") || sub.equals("visit") || sub.equals("v") || sub.equals("tp") || sub.equals("auto"))) {
            player.sendMessage(plugin.msg("invalid-args"));
            return true;
        }
        // visit/v/tp 必须带玩家名
        if ((sub.equals("visit") || sub.equals("v") || sub.equals("tp")) && args.length < 2) {
            player.sendMessage(plugin.msg("invalid-args"));
            return true;
        }
        String fullArgs = String.join(" ", args);
        return teleport(player, "plot", fullArgs);
    }

    private boolean teleport(Player player, String type, String target) {
        RedisManager redis = plugin.getRedisManager();
        if (redis == null) {
            player.sendMessage(plugin.msg("redis-error"));
            return true;
        }
        // Redis 网络 IO 放到异步线程，避免阻塞主线程
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!redis.setPendingTeleport(player.getUniqueId(), type, target)) {
                sendIfOnline(player, "redis-error");
                return;
            }
            String server = getTargetServer(player.getUniqueId(), type);
            if (server == null) {
                sendIfOnline(player, "redis-error");
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(plugin.msg("switching"));
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("Connect");
                out.writeUTF(server);
                player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
            });
        });
        return true;
    }

    private void sendIfOnline(Player player, String msgKey) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.sendMessage(plugin.msg(msgKey));
        });
    }

    private String getTargetServer(UUID uuid, String type) {
        List<String> servers = plugin.getConfig().getStringList(type + ".target-servers");
        if (servers.isEmpty()) return null;

        RedisManager redis = plugin.getRedisManager();
        if (redis != null) {
            String last = redis.getLastServer(uuid, type);
            if (last != null && servers.contains(last)) return last;
        }
        return servers.get(0);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return List.of();

        String cmdName = command.getName().toLowerCase();
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String sub = args[0].toLowerCase();
            if (cmdName.equals("res") && handleRes) {
                if ("tp".startsWith(sub)) completions.add("tp");
            } else if (cmdName.equals("plot") && handlePlot) {
                for (String s : new String[]{"home", "h", "visit", "v", "tp", "auto"}) {
                    if (s.startsWith(sub)) completions.add(s);
                }
            }
        } else if (args.length == 2 && cmdName.equals("res") && handleRes && args[0].equalsIgnoreCase("tp")) {
            // 主线程上不做任何 IO：只读缓存（允许过期数据），过期则触发异步刷新
            String prefix = args[1].toLowerCase();
            for (String name : getCachedResListNoBlock(player.getUniqueId())) {
                if (name.toLowerCase().startsWith(prefix)) completions.add(name);
            }
        }

        return completions;
    }

    /**
     * Paper 异步补全事件：在异步线程做 Redis/MySQL 查询，避免卡主线程。
     */
    @EventHandler
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (!handleRes) return;
        String buffer = event.getBuffer();
        if (buffer.startsWith("/")) buffer = buffer.substring(1);
        String[] tokens = buffer.split(" ", -1);
        // 仅处理 /res tp <第二个参数>
        if (tokens.length != 3 || !tokens[0].equalsIgnoreCase("res")) return;
        if (!"tp".startsWith(tokens[1].toLowerCase())) return;
        if (!(event.getSender() instanceof Player player)) return;

        String prefix = tokens[2].toLowerCase();
        List<String> completions = new ArrayList<>();
        for (String name : fetchResList(player.getUniqueId())) {
            if (name.toLowerCase().startsWith(prefix)) completions.add(name);
        }
        event.setCompletions(completions);
        event.setHandled(true);
    }

    private List<String> getCachedResListNoBlock(UUID uuid) {
        CacheEntry entry = resCache.get(uuid);
        if (entry != null && !entry.expired()) return entry.items();
        refreshResCacheAsync(uuid);
        return entry != null ? entry.items() : List.of();
    }

    private void refreshResCacheAsync(UUID uuid) {
        if (!refreshingResCache.add(uuid)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                putResCache(uuid, fetchResList(uuid));
            } finally {
                refreshingResCache.remove(uuid);
            }
        });
    }

    /**
     * 阻塞式获取领地列表（Redis 优先，MySQL 回退）。只能在异步线程调用。
     */
    private List<String> fetchResList(UUID uuid) {
        RedisManager redis = plugin.getRedisManager();
        if (redis != null) {
            List<String> list = redis.getResList(uuid);
            if (!list.isEmpty()) return list;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isEnabled()) {
            return db.getResidenceNames(uuid);
        }
        return List.of();
    }

    private void putResCache(UUID uuid, List<String> items) {
        resCache.put(uuid, new CacheEntry(items, System.currentTimeMillis()));
        if (resCache.size() > CACHE_MAX_SIZE) {
            resCache.entrySet().removeIf(e -> e.getValue().expired());
        }
    }
}
