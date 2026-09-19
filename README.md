# ResBridge

跨服领地/地皮传送桥梁插件 —— 在未安装 Residence / PlotSquared 的服务器上接管传送指令，通过 Velocity + Redis 将玩家传送到安装了插件的目标服务器。

## 功能特性

- **按类型独立角色**：领地和地皮各自配置 A（接收端）或 B（发送端），同一台服可同时担任不同角色
- **领地传送**：`/res tp [名称]`，支持领地名称 Tab 补全
- **地皮传送**：`/plot home|visit|tp [参数]`，支持 `p` / `p2` / `plotme` 等别名
- **自动领取地皮**：`/plot auto`，切换到目标地皮服后由 PlotSquared 执行领取，支持 Tab 补全
- **多服务器路由**：按类型自动路由到对应服务器，优先送回玩家上次访问的服
- **MySQL 持久化**：可选 MySQL 存储领地名称列表，自动建表，表名可配置
- **智能回退**：反射获取领地列表（ClassLoader 修正）→ MySQL 历史数据 → Redis 缓存
- **指令转发**：A 模式自动将 `/res` `/plot` 转发给真实插件，不会覆盖
- **重试机制**：传送失败自动重试（最多 3 次，间隔 1 秒）
- **热重载**：`/resbridge reload` 无需重启，支持在线切换 A/B 角色

## 环境要求

- Paper 1.21+（Java 21）
- Velocity 代理
- Redis
- MySQL（可选，用于领地名称持久化）
- A 服需安装 Residence 和/或 PlotSquared

## 架构

```
玩家在B服输入 /res tp myhome
        │
        ▼
  B服: 写入 Redis ──→ resbridge:tp:{uuid} = "res:myhome"
        │
        ▼
  B服: 查询上次访问的领地服 → 通过 Velocity 切换玩家
        │
        ▼
  A服: PlayerJoinEvent → 读取 Redis → 命名空间指令执行 + 重试
```

## 部署

同一个 jar 安装到所有相关服务器，通过 `res.mode` / `plot.mode` 配置每台服的角色。

### 场景一：大厅 + 全装服

| 服务器 | res.mode | plot.mode | 说明 |
|--------|----------|-----------|------|
| 大厅 | B | B | 两个都发出去 |
| 全装服 | A | A | 两个都接收 |

### 场景二：领地、地皮在不同服务器

| 服务器 | res.mode | plot.mode | 说明 |
|--------|----------|-----------|------|
| 大厅 | B | B | 两个都发出去 |
| 领地服 | A | B | 接收领地，地皮转发 |
| 地皮服 | B | A | 领地转发，接收地皮 |

### 场景三：混合服（装了 Residence 没装 PlotSquared）

| 服务器 | res.mode | plot.mode | 说明 |
|--------|----------|-----------|------|
| 混合服 | A | B | 领地接收，地皮发到地皮服 |

## 指令

### B 模式（发送端）

| 指令 | 说明 | 权限 |
|------|------|------|
| `/res tp [名称]` | 传送到领地（不填 = 第一个领地） | `resbridge.res` |
| `/plot home [序号]` | 回自己的地皮 | `resbridge.plot` |
| `/plot visit <玩家>` | 访问他人的地皮 | `resbridge.plot` |
| `/plot tp <玩家>` | 传送到他人的地皮 | `resbridge.plot` |
| `/plot auto` | 自动领取目标服的空闲地皮 | `resbridge.plot` |
| `/resbridge reload` | 重载配置 | `resbridge.admin` |

`/plot` 别名：`/p`、`/p2`、`/plotme`；子指令别名：`h` = `home`，`v` = `visit`

`/plot auto` 同样支持 `/p auto` 等命令别名，优先前往上次访问的地皮服，否则前往 `plot.target-servers` 中的第一台服。领取以玩家身份执行，所需 PlotSquared 权限、地皮额度及费用由目标服检查；附加参数会原样转发给 PlotSquared。

### A 模式（接收端）

无额外指令。`/res` 和 `/plot` 自动转发给真实插件（Residence / PlotSquared），玩家正常使用不受影响。

## 配置说明

```yaml
# 本服在 Velocity 中的名称
server-name: "lobby"

# 领地角色
res:
  mode: B                      # A = 接收端, B = 发送端
  target-servers:              # mode=B 时有效
    - "survival"

# 地皮角色
plot:
  mode: B
  target-servers:
    - "plot-world"

# Redis
redis:
  host: "127.0.0.1"
  port: 6379
  password: ""
  database: 0
  expire: 60

# A 模式：加入后延迟传送（ticks）
teleport-delay: 40

# MySQL（可选，持久化领地名称供 Tab 补全）
mysql:
  enabled: false
  host: "127.0.0.1"
  port: 3306
  database: "resbridge"        # 需提前创建
  username: "root"
  password: ""
  table: "resbridge_residences" # 自动创建，可改名

# 消息（支持 & 颜色代码）
messages:
  prefix: "&6[ResBridge] &r"
  switching: "&a正在传送到目标服务器..."
  # ... 其他消息见默认配置
```

## 权限

| 权限 | 说明 | 默认 |
|------|------|------|
| `resbridge.res` | 领地传送 | `true` |
| `resbridge.plot` | 地皮传送与自动领取转发 | `true` |
| `resbridge.admin` | 管理指令（reload） | `op` |

## 构建

```bash
mvn clean package
```

产物：`target/Liu-ResBridge-1.0.2.jar`

运行时依赖通过 Paper `libraries` 自动下载：Jedis（及其依赖 gson、commons-pool2、slf4j-api）、HikariCP、MySQL Connector/J

## 项目结构

```
src/main/java/com/resbridge/
├── ResBridge.java         # 主类，按类型路由 A/B 模式，reload
├── RedisManager.java      # Redis 通信（传送请求、领地缓存、服务器记忆）
├── DatabaseManager.java   # MySQL 持久化（自建表，领地名称存储）
├── ServerAListener.java   # A服：监听加入 → 执行传送 + 反射/MySQL 获取领地列表
└── ServerBCommand.java    # B服：指令处理 + 跨服切换 + Tab 补全
```
