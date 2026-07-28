package com.resbridge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DatabaseManager {

    private HikariDataSource dataSource;
    private String table;

    public DatabaseManager(ResBridge plugin) {
        if (!plugin.getConfig().getBoolean("mysql.enabled", false)) return;

        String host = plugin.getConfig().getString("mysql.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("mysql.port", 3306);
        String database = plugin.getConfig().getString("mysql.database", "resbridge");
        String username = plugin.getConfig().getString("mysql.username", "root");
        String password = plugin.getConfig().getString("mysql.password", "");
        this.table = plugin.getConfig().getString("mysql.table", "resbridge_residences");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8mb4");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(3000);
        config.setPoolName("ResBridge-MySQL");
        this.dataSource = new HikariDataSource(config);

        createTable();
    }

    private void createTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS `" + table + "` ("
                    + "`uuid` VARCHAR(36) NOT NULL, "
                    + "`residence_name` VARCHAR(255) NOT NULL, "
                    + "PRIMARY KEY (`uuid`, `residence_name`)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception e) {
            ResBridge.getInstance().getLogger().warning("MySQL 建表失败: " + e.getMessage());
        }
    }

    public boolean isEnabled() {
        return dataSource != null;
    }

    public void saveResidenceNames(UUID uuid, List<String> names) {
        if (dataSource == null || names.isEmpty()) return;
        try (Connection conn = dataSource.getConnection()) {
            // 先删旧数据
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM `" + table + "` WHERE `uuid` = ?")) {
                del.setString(1, uuid.toString());
                del.executeUpdate();
            }
            // 批量插入
            try (PreparedStatement ins = conn.prepareStatement("INSERT INTO `" + table + "` (`uuid`, `residence_name`) VALUES (?, ?)")) {
                for (String name : names) {
                    ins.setString(1, uuid.toString());
                    ins.setString(2, name);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        } catch (Exception e) {
            ResBridge.getInstance().getLogger().warning("MySQL 保存领地列表失败: " + e.getMessage());
        }
    }

    public List<String> getResidenceNames(UUID uuid) {
        if (dataSource == null) return List.of();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT `residence_name` FROM `" + table + "` WHERE `uuid` = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return names;
        } catch (Exception e) {
            ResBridge.getInstance().getLogger().warning("MySQL 查询领地列表失败: " + e.getMessage());
            return List.of();
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
