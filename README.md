# HP Output Mod

轻量 Minecraft Mod - 监听玩家血量变化和受伤事件，通过 UDP 广播到本机回环地址。

**同时支持 Fabric 和 Forge 加载器**

- **作者**: yomo40
- **版本**: 1.2.0
- **许可证**: MIT

## 下载

| 版本 | Mod 加载器 | Minecraft 版本 | 文件 |
|------|-----------|---------------|------|
| 1.2.0 | Fabric | 1.14 ~ 1.21+ | `hp_output-fabric-1.2.0.jar` |
| 1.2.0 | Forge | 1.12 ~ 1.20.x | `hp_output-forge-1.2.0.jar` |

**注意：Fabric Mod Loader 仅支持 MC 1.14 +，1.12-1.13 只能使用 Forge。**

## 特性

-  **双平台** - 同时支持 **Fabric** 和 **Forge**
-  **多版本** - Fabric 1.14+, Forge 1.12+
-  **仅客户端** - 无需服务端安装
-  **事件监听** - 支持血量、受伤、治疗、死亡事件
-  **游戏内配置** - 可在游戏中自定义 UDP 端口
-  **调试日志** - 控制台输出所有发送的数据

## 安装

### Fabric 版本

1. 安装 [Fabric Loader](https://fabricmc.net/use/installer/)
2. 安装前置mod[Mod Menu] 模组(https://modrinth.com/mod/modmenu)
2. 安装 [Fabric API](https://modrinth.com/mod/fabric-api)
3. 将 `hp_output-fabric-1.2.0.jar` 放入 `.minecraft/mods/`

### Forge 版本

1. 安装 [Forge](https://files.minecraftforge.net/)
2. 将 `hp_output-forge-1.2.0.jar` 放入 `.minecraft/mods/`

## 游戏内配置端口

### Fabric（需要 Mod Menu）

1. 安装 [Mod Menu](https://modrinth.com/mod/modmenu) 模组
2. 启动游戏，进入**主菜单** → **Mods**
3. 找到 **HP Output** → 点击 **配置按钮**（⚙️）
4. 修改 **UDP Port** → 点击 **Save**

### Forge

1. 启动游戏，进入**主菜单** → **Mods**
2. 找到 **HP Output** → 点击 **Config**
3. 修改 **UDP Port** → 点击 **Save**

### 配置文件位置

- **Fabric**: `.minecraft/config/hp_output.json`
- **Forge**: `.minecraft/config/hp_output-client.toml`

## UDP 数据格式

向 `127.0.0.1:<port>`（默认 39571）发送 JSON 格式 UDP 数据包。

每次发送都是一个统一结构的 JSON 包：**基准字段常驻**（`alive`/`health`/`maxHealth`/`percentage`），**事件字段仅在触发时出现**（`damage`/`heal`/`death`/`source`）。通过字段存在性区分事件类型。

### 包类型

#### 1. 基准状态（周期心跳 / 首次进入）
```json
{"alive":true,"health":18.0,"maxHealth":20.0,"percentage":0.900}
```

#### 2. 受伤事件
```json
{"alive":true,"health":17.0,"maxHealth":20.0,"percentage":0.850,"damage":3.0,"source":"mob:Zombie"}
```

#### 3. 治疗事件
```json
{"alive":true,"health":19.0,"maxHealth":20.0,"percentage":0.950,"heal":2.0}
```

#### 4. 死亡事件
```json
{"alive":false,"health":0.0,"maxHealth":20.0,"percentage":0.000,"death":true,"source":"fall"}
```
> [!IMPORTANT]  
> 致命一击只发送死亡事件，不会同时发送受伤事件；每个游戏事件最多产生一个数据包。

### 字段说明

| 字段 | 类型 | 说明 | 出现时机 |
|------|------|------|----------|
| `alive` | boolean | 玩家是否存活 | 恒在 |
| `health` | float | 当前血量（半心=1.0，满血=20.0） | 恒在 |
| `maxHealth` | float | 最大血量 | 恒在 |
| `percentage` | float | 血量百分比（0.0 ~ 1.0） | 恒在 |
| `damage` | float | 受到的伤害值 | 受伤时 |
| `heal` | float | 治疗量 | 治疗时 |
| `source` | string | 伤害/死亡来源 | 受伤/死亡时 |
| `death` | boolean | 恒为 `true` | 死亡时 |

### 常见伤害来源

| 来源 | 说明 |
|------|------|
| `mob:Zombie` | 被僵尸攻击 |
| `mob:Skeleton` | 被骷髅射击 |
| `player:Steve` | 被其他玩家攻击 |
| `fall` | 摔落伤害 |
| `lava` | 岩浆伤害 |
| `drown` | 溺水 |
| `starve` | 饥饿 |
| `explosion` | 爆炸 |

## 接收数据示例

### Python
```python
import socket, json
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(('127.0.0.1', 39571))
while True:
    data, _ = sock.recvfrom(1024)
    event = json.loads(data.decode())
    line = f"alive={event['alive']} hp={event['health']}/{event['maxHealth']}"
    if 'damage' in event: line += f" damage={event['damage']} from {event['source']}"
    if 'heal' in event: line += f" heal=+{event['heal']}"
    if 'death' in event: line += " DEATH"
    print(line)
```

### Node.js
```javascript
const dgram = require('dgram');
const server = dgram.createSocket('udp4');
server.on('message', (msg) => {
  const data = JSON.parse(msg.toString());
  let line = `alive=${data.alive} hp=${data.health}/${data.maxHealth}`;
  if (data.damage) line += ` damage=${data.damage} from ${data.source}`;
  if (data.heal) line += ` heal=+${data.heal}`;
  if (data.death) line += ' DEATH';
  console.log(line);
});
server.bind(39571, '127.0.0.1');
```

### PowerShell
```powershell
$udp = New-Object System.Net.Sockets.UdpClient(39571)
$ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
while ($true) {
    $data = $udp.Receive([ref]$ep)
    [System.Text.Encoding]::UTF8.GetString($data)
}
```

## 项目结构

```
mc-mod/
├── mc-mod/           # Fabric 版本源码
│   ├── common/       # 共享代码 (UDP 广播器)
│   └── fabric/       # Fabric 入口和配置界面
├── mc-mod-forge/     # Forge 版本源码
└── README.md
```

## 构建

```bash
# 构建 Fabric 版本
cd mc-mod
.\gradlew.bat build

# 构建 Forge 版本
cd mc-mod-forge
.\gradlew.bat build
```

## License

MIT
