package com.resbridge;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.JedisPool;

import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 使用独立临时 Redis，不连接或清空用户的 Redis。运行需要 redis-server。 */
public class RedisIntegrationTest {
    private static Process server;
    private static JedisPool pool;

    @BeforeClass
    public static void startRedis() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new ProcessBuilder("redis-server", "--bind", "127.0.0.1",
                "--port", Integer.toString(port), "--save", "", "--appendonly", "no")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        pool = new JedisPool("127.0.0.1", port);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && server.isAlive()) {
            try (var jedis = pool.getResource()) {
                if ("PONG".equals(jedis.ping())) return;
            } catch (Exception ignored) {
                Thread.sleep(20);
            }
        }
        stopRedis();
        fail("临时 Redis 启动失败");
    }

    @AfterClass
    public static void stopRedis() throws Exception {
        if (pool != null) pool.close();
        if (server != null) {
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly();
        }
    }

    @Test
    public void shutdownRemovesPresenceAndCannotBeReactivated() {
        String name = UUID.randomUUID().toString();
        ServerPresence presence = new ServerPresence(pool, name, List.of("plot"));
        assertFalse(ServerPresence.isOnline(pool, name, "plot"));
        presence.refresh();
        assertTrue(ServerPresence.isOnline(pool, name, "plot"));
        assertFalse(ServerPresence.isOnline(pool, name, "res"));
        presence.close();
        presence.refresh();
        assertFalse(ServerPresence.isOnline(pool, name, "plot"));
    }

    @Test
    public void oldInstanceCannotRemoveNewInstancePresence() {
        String name = UUID.randomUUID().toString();
        ServerPresence old = new ServerPresence(pool, name, List.of("res", "plot"));
        try (ServerPresence current = new ServerPresence(pool, name, List.of("res", "plot"))) {
            old.refresh();
            current.refresh();
            old.close();
            assertTrue(ServerPresence.isOnline(pool, name, "res"));
            assertTrue(ServerPresence.isOnline(pool, name, "plot"));
        }
    }

    @Test
    public void crashedServerExpiresWithoutShutdown() throws Exception {
        String name = UUID.randomUUID().toString();
        try (ServerPresence presence = new ServerPresence(pool, name, List.of("plot"))) {
            presence.refresh();
            assertTrue(ServerPresence.isOnline(pool, name, "plot"));
            Thread.sleep(TimeUnit.SECONDS.toMillis(ServerPresence.TTL_SECONDS) + 100);
            assertFalse(ServerPresence.isOnline(pool, name, "plot"));
        }
    }

    @Test
    public void timeoutCleanupDoesNotDeleteNewRequest() {
        RedisManager redis = new RedisManager(pool, 60);
        UUID player = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        assertTrue(redis.setPendingTeleport(player, "plot", "auto", old));
        assertTrue(redis.setPendingTeleport(player, "plot", "home", current));
        redis.deletePendingTeleport(player, old);
        assertEquals("plot:home", redis.getPendingTeleport(player));
        redis.deletePendingTeleport(player, current);
        assertNull(redis.getPendingTeleport(player));
        try (var jedis = pool.getResource()) {
            assertFalse(jedis.exists("resbridge:tp:" + player + ":owner"));
        }
    }

    @Test
    public void arrivalDeletesRequestAndOwnership() {
        RedisManager redis = new RedisManager(pool, 60);
        UUID player = UUID.randomUUID();
        assertTrue(redis.setPendingTeleport(player, "plot", "auto", UUID.randomUUID()));
        assertEquals("plot:auto", redis.getPendingTeleport(player));
        redis.deletePendingTeleport(player);
        assertNull(redis.getPendingTeleport(player));
        try (var jedis = pool.getResource()) {
            assertFalse(jedis.exists("resbridge:tp:" + player + ":owner"));
        }
    }
}
