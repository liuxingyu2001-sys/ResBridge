package com.resbridge;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerBCommand implements CommandExecutor, TabCompleter {

    private final ResBridge plugin;
    private final boolean handleRes;
    private final boolean handlePlot;

    private final Map<UUID, CacheEntry> resCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 30_000;

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
        if (!(sub.equals("home") || sub.equals("h") || sub.equals("visit") || sub.equals("v") || sub.equals("tp"))) {
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
        if (redis == null || !redis.setPendingTeleport(player.getUniqueId(), type, target)) {
            player.sendMessage(plugin.msg("redis-error"));
            return true;
        }

        String server = getTargetServer(player, type);
        if (server == null) {
            player.sendMessage(plugin.msg("redis-error"));
            return true;
        }

        player.sendMessage(plugin.msg("switching"));
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        return true;
    }

    private String getTargetServer(Player player, String type) {
        List<String> servers = plugin.getConfig().getStringList(type + ".target-servers");
        if (servers.isEmpty()) return null;

        RedisManager redis = plugin.getRedisManager();
        if (redis != null) {
            String last = redis.getLastServer(player.getUniqueId(), type);
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
                for (String s : new String[]{"home", "h", "visit", "v", "tp"}) {
                    if (s.startsWith(sub)) completions.add(s);
                }
            }
        } else if (args.length == 2 && cmdName.equals("res") && handleRes && args[0].equalsIgnoreCase("tp")) {
            String prefix = args[1].toLowerCase();
            for (String name : getCachedResList(player.getUniqueId())) {
                if (name.toLowerCase().startsWith(prefix)) completions.add(name);
            }
        }

        return completions;
    }

    private List<String> getCachedResList(UUID uuid) {
        CacheEntry entry = resCache.get(uuid);
        if (entry != null && !entry.expired()) return entry.items();
        List<String> list = List.of();
        RedisManager redis = plugin.getRedisManager();
        if (redis != null) list = redis.getResList(uuid);
        if (list.isEmpty()) {
            DatabaseManager db = plugin.getDatabaseManager();
            if (db != null && db.isEnabled()) list = db.getResidenceNames(uuid);
        }
        resCache.put(uuid, new CacheEntry(list, System.currentTimeMillis()));
        return list;
    }
}
