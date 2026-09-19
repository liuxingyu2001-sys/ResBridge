package com.resbridge;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.UUID;

/** Redis 心跳不依赖在线玩家；关闭后禁止异步任务重新发布在线状态。 */
final class ServerPresence implements AutoCloseable {
    static final int TTL_SECONDS = 6;
    private final JedisPool pool;
    private final List<String> keys;
    private final String owner = UUID.randomUUID().toString();
    private boolean closed;

    ServerPresence(JedisPool pool, String server, List<String> types) {
        this.pool = pool;
        this.keys = types.stream().map(type -> key(server, type)).toList();
    }

    private static String key(String server, String type) {
        return "resbridge:online:" + type + ":" + server;
    }

    static boolean isOnline(JedisPool pool, String server, String type) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(key(server, type));
        }
    }

    synchronized void refresh() {
        if (closed || keys.isEmpty()) return;
        try (Jedis jedis = pool.getResource()) {
            for (String key : keys) jedis.setex(key, TTL_SECONDS, owner);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (keys.isEmpty()) return;
        try (Jedis jedis = pool.getResource()) {
            for (String key : keys) {
                // 旧实例关闭不能删除重启后的新实例心跳。
                jedis.eval("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0",
                        List.of(key), List.of(owner));
            }
        }
    }
}
