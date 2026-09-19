package com.resbridge;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class RedisManager {

    private final JedisPool pool;
    private final String tpPrefix = "resbridge:tp:";
    private final String resListPrefix = "resbridge:reslist:";
    private final String lastServerPrefix = "resbridge:lastserver:";
    private final int expireSeconds;
    private static final int LIST_EXPIRE = 3600;

    public RedisManager(ResBridge plugin) {
        String host = plugin.getConfig().getString("redis.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("redis.port", 6379);
        String password = plugin.getConfig().getString("redis.password", "");
        int database = plugin.getConfig().getInt("redis.database", 0);
        this.expireSeconds = plugin.getConfig().getInt("redis.expire", 60);

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);

        if (password != null && !password.isEmpty()) {
            this.pool = new JedisPool(poolConfig, host, port, 2000, password, database);
        } else {
            this.pool = new JedisPool(poolConfig, host, port, 2000, null, database);
        }

        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
        }
    }

    public boolean setPendingTeleport(UUID uuid, String type, String target) {
        try (Jedis jedis = pool.getResource()) {
            String value = type + ":" + (target != null ? target : "");
            jedis.setex(tpPrefix + uuid, expireSeconds, value);
            return true;
        } catch (Exception e) {
            ResBridge.getInstance().getLogger().warning("Redis 写入传送请求失败: " + e.getMessage());
            return false;
        }
    }

    public String getPendingTeleport(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(tpPrefix + uuid);
        } catch (Exception e) {
            return null;
        }
    }

    public void deletePendingTeleport(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(tpPrefix + uuid);
        } catch (Exception ignored) {
        }
    }

    public void setResList(UUID uuid, List<String> names) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(resListPrefix + uuid, LIST_EXPIRE, String.join(",", names));
        } catch (Exception ignored) {
        }
    }

    public List<String> getResList(UUID uuid) {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(resListPrefix + uuid);
            if (value == null || value.isEmpty()) return List.of();
            return Arrays.asList(value.split(","));
        } catch (Exception e) {
            return List.of();
        }
    }

    public void setLastServer(UUID uuid, String type, String server) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(lastServerPrefix + type + ":" + uuid, LIST_EXPIRE, server);
        } catch (Exception ignored) {
        }
    }

    public String getLastServer(UUID uuid, String type) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(lastServerPrefix + type + ":" + uuid);
        } catch (Exception e) {
            return null;
        }
    }

    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }
}
