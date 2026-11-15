# Loot Radar 开发文档

本文面向二次开发与维护，覆盖仓库结构、运行方式、核心算法、数据格式、真值/验证体系。

## 1. 这个项目在做什么

它不是通用世界模拟器，也不启动真实存档，而是：

1. 用**纯 Java 复刻**的随机源与结构放置算法，快速算出候选结构区块；
2. 对候选区块**重放原版 Minecraft 的结构生成代码**（Fabric Loom 引入的原生类），
   把写入拦截到一个内存里的 `WorldGenLevel` 代理；
3. 从拦截到的方块实体读出战利品表与 `lootTableSeed`，用原版 `LootTable` 抽奖，判断目标物品是否出现；
4. 宝库 / 远古守卫者等无坐标依赖的 `random_sequence` 来源，直接按「第几次开启/击杀」推演序列。

准确度依赖两条随机流是否逐位复现，因此**任何随机公式、装饰阶段下标、放置顺序的改动都会
影响全部结果**。这是本仓库大量「看似多余的补放/重放」代码存在的原因。

## 2. 仓库结构

| 路径                                                | 职责                                                                                                                        |
| --------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `core/`                                             | 不依赖 Minecraft 的纯逻辑：RNG 复刻、random_spread 定位、版本/物品目录加载、搜索 DTO。Gson + slf4j-api。                    |
| `engine/src/main/java/dev/lootradar/app/`           | `Main`（启动）、`ApiServer`（本地 HTTP + SSE）、`tools/*Main`（验证/普查/基准 CLI）。                                       |
| `engine/src/main/java/dev/lootradar/engine/`        | 原生运行时封装、放置重放、容器记录、战利品抽奖、结构/特征扫描器。                                                           |
| `engine/src/main/java/dev/lootradar/engine/search/` | `SearchEngine`（调度）、`ScanCache`（LRU）。                                                                                |
| `engine/src/mc26_1_2\|mc26_2\|mc26_3\|mc26_4/java/` | 每个游戏版本一套 `MinecraftVersionAdapter` / `VersionedMinecraftServer` / `SuppressingServerLevelBase`，隔离原生 API 变更。 |
| `engine/src/test/java/`                             | 与原生类对照的回归测试（多数会启动 `VanillaRuntime`）。                                                                     |
| `web/`                                              | React 18 + Vite 5 + Tailwind 前端；`pnpm build` 产物为 `web/dist`。                                                         |
| `data/<version>/`                                   | `structures.json`、`items.json`，以及 `loot_tables/**` 跨版本覆盖表。                                                       |
| `dump-mod/`                                         | **独立 Gradle 构建**的 Fabric mod，跑真实服务器产出「真值」JSON。四个版本子项目。                                           |
| `tools/`                                            | Python / PowerShell 编排脚本：真值采集、覆盖率统计、数据生成、基准、启动与打包。                                            |
| `package.ps1`、`run-app.ps1`                        | 发行包构建、开发态启动入口。                                                                                                |

构建关系：根 `settings.gradle.kts` 只 include `core`、`engine`；`dump-mod` 有自己的
`settings.gradle` / `gradlew`，`build.gradle.kts` 仅从它的 `gradle.properties` 读版本号。

## 3. 构建、运行与打包

### 环境

- JDK 25（`build.gradle.kts` 指定 toolchain 与 `options.release = 25`），运行需
  `--sun-misc-unsafe-memory-access=allow`（原生运行时用到了遗留 `sun.misc` 访问）。
- Node.js + pnpm（仅前端）。
- Gradle wrapper；首次构建需能取到 Fabric Loom 与对应版本的 Minecraft 制品（本机已有缓存，
  可用 `--offline`）。
- 游戏参考源码（可选，用于核对算法）：`D:/Scripts/ref/minecraft/<版本>/`，
  其中 `minecraft-common-*-sources/` 是 Mojang 映射的反混淆 Java 源码。

### 常用命令（PowerShell）

```powershell
.\gradlew.bat :core:test :engine:test --offline       # 全部 Java 单测
.\gradlew.bat spotlessCheck --offline                 # google-java-format (AOSP) 检查
.\gradlew.bat spotlessApply --offline                 # 格式化（改 Java 注释后建议执行）
.\gradlew.bat :engine:runApp --offline --args=8765    # 直接起后端
.\run-app.ps1                                        # 检测前端产物是否过期 → pnpm build → 起应用并开浏览器
.\package.ps1                                        # 打 dist/loot-radar-<version>.zip
python tools\verify-rng-reference.py                  # 独立 RNG/放置公式对照（见第 8 节）
```

切换原生版本编译：`-Pminecraft_version=26.3` 或 `-Pminecraft_version=26.4-snapshot-7`；
`engine/build.gradle.kts` 会把 `minecraft_version` 的 `.`/`-` 归一化后加入
`src/mc<版本>/java` 源集。快照版共用 `mc26_4` 适配器。`-PcoverageBuild=<label>` 可把构建目录
隔离到 `engine/build/native-<版本>-<label>`，便于多版本并行跑覆盖率。

### 启动流程

`Main.main`：

1. 探测 8765..8774 上是否已有 `/api/versions` 返回 200 的实例，有则直接打开浏览器；
2. 从 `data/` 加载各版本 `ItemCatalog` / `VersionRegistry`，校验物品引用的结构存在；
3. 扫描 `data/<version>/loot_tables/**.json` 作为该版本的战利品表覆盖；
4. `VanillaRuntime.load(0L)` 启动原生世界生成运行时；
5. 起 `ApiServer`（端口占用时最多顺延 9 个），自动开浏览器，主线程 `join()`。

数据根查找顺序：`-Dlootradar.data` → 当前目录 `data/` → `../data/` → 从代码位置向上最多 4 层；
前端根查找 `web-dist`（发行包）或 `web/dist`（开发态）。

### 发行包

`package.ps1` 把 `:engine:distZip` 解包，复制
`web/dist` → `web-dist`、`data/`，生成要求 JDK 25 的 `Loot Radar.bat`，
最后压缩并校验条目数。启动脚本里显式带 `--sun-misc-unsafe-memory-access=allow`。
`.\package.ps1 -SkipWebBuild -KeepClientLibs` 可跳过前端构建/裁剪，用于对照排查缺类问题。

## 4. HTTP API 与前端契约

`ApiServer` 只监听 `127.0.0.1`，使用虚拟线程 executor；`LootMethod` 通过 Gson 序列化为
枚举 `id` 字符串。

| 方法 | 路径                        | 说明                                                                        |
| ---- | --------------------------- | --------------------------------------------------------------------------- |
| GET  | `/api/catalog?version=<id>` | 返回 `{groups, items, structures}`，`structures` 是结构 id → 中文名。       |
| GET  | `/api/versions`             | `{versions: [...], active: <id>}`；`active` 取实际已加载版本（优先 26.2）。 |
| POST | `/api/search`               | 请求体为 `SearchRequest`，返回 `{searchId}`。                               |
| GET  | `/api/search/active`        | `{searchId, finished}`，用于刷新页面后恢复。                                |
| GET  | `/api/search/{id}/events`   | SSE，事件 `hit` / `progress` / `done` / `error`，每帧带 `id: <序号>`。      |
| POST | `/api/search/{id}/cancel`   | 只置 `cancelled` 标志，不强制中断线程。                                     |
| POST | `/api/shutdown`             | 300ms 后 `Runtime.halt(0)`。                                                |
| GET  | `/`                         | 静态前端；不存在的静态路径回退 `index.html`，未知 `/api/*` 返回 404。       |

会话把**全部事件缓存在内存**，因此「先 POST 再连 SSE」也能从 0 全量重放；浏览器断线可用
`Last-Event-ID` 续传；空闲 15s 发 `: keep-alive`。已完成会话 10 分钟后清理。

前后端字段契约是「Java record 名 = Gson 名 = TS 属性名」，没有代码生成：
`web/src/types.ts` 对应 `core/.../search/*` 与 `core/.../catalog/*`。所有耗时字段单位为纳秒，
前端统一 `/1e9`。前端 `skipDungeon` 恒为 `false`，实际的来源排除通过 `structures` 白名单完成。

## 5. 核心算法

### 5.1 随机源与种子链

`core/rng` 是原版 `net.minecraft.world.level.levelgen` 相关类的最小复刻，
`engine/DecorationStreams` 则在真正放置时直接使用原生类。两条路径由
`RngParityTest` 与 `tools/verify-rng-reference.py` 双向校验。

```
worldSeed
  └─ setLargeFeatureWithSalt(worldSeed, gridX, gridZ, salt)   → random_spread 网格候选
  └─ setLargeFeatureSeed(worldSeed, chunkX, chunkZ)           → 结构集权重选择 / legacy_type_3
  └─ setDecorationSeed(worldSeed, chunkMinX, chunkMinZ)       → decorationSeed
        └─ setFeatureSeed(decorationSeed, index, step)         → 该区块某装饰阶段的随机流
              └─ StructurePiece.createChest(..., random.nextLong()) → chest.lootTableSeed
                    └─ LootTable.fill → withOptionalRandomSeed → RandomSource.create(seed)
```

关键公式（均已对照 `D:/Scripts/ref/minecraft/26.2/minecraft-common-26.2-sources/`）：

| 方法                                   | 公式 / 行为                                                                                      |
| -------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `LegacyRandomSource`                   | 48 位 LCG：`(seed ^ 25214903917) & (2^48-1)`，步进 `* 25214903917 + 11`。                        |
| `Xoroshiro128PlusPlus`                 | 双 64 位状态；`result = rotl(s0+s1,17)+s0`；全 0 种子替换为两个魔数。                            |
| `WorldgenRandom.setDecorationSeed`     | `setSeed(worldSeed)` → 两次 `nextLong() \| 1` 作缩放因子 → `(x*xScale + z*zScale) ^ worldSeed`。 |
| `setFeatureSeed`                       | `setSeed(decorationSeed + index + 10000 * step)`。                                               |
| `setLargeFeatureWithSalt`              | `x*341873128712 + z*132897987541 + seed + salt`。                                                |
| `setLargeFeatureSeed`                  | 两次 `nextLong()` 作缩放因子，`(cx*xScale) ^ (cz*zScale) ^ seed`。                               |
| `BitRandom.nextInt(bound)`             | 2 的幂走乘法，其余走拒绝采样；`WorldgenRandom` 继承这套默认实现。                                |
| `XoroshiroRandomSource.nextInt(bound)` | Lemire 乘高 + 拒绝采样，**不能**用取模版替换。                                                   |
| `RandomSequence(seed, key)`            | `upgradeSeedTo128bitUnmixed(seed) xor seedFromHashOf(key)`，再 `mixed()`。                       |
| `seedFromHashOf`                       | MD5(UTF-8) 的 16 字节按**大端**（Guava `Longs.fromBytes`）拆成两个 long。                        |

`LootContext.create()` 的随机源优先级：显式 `withOptionalRandomSeed` / `withOptionalRandomSource`

> 战利品表的 `random_sequence` id > 关卡随机源。所以：

- 普通箱子 / 考古 / 陶罐：`VanillaLootOracle.roll(table, lootTableSeed)`；
- 宝库 / 不祥宝库 / 远古守卫者：`VaultSequencePredictor` / `ElderGuardianPredictor` 用
  `RandomSequence(worldSeed, tableId)` 连续推演。

抽奖统一用 `getRandomItemsRaw`（不洗牌、不拆堆），因为目标是判断「是否出现」，
而不是复刻箱子里的槽位分布。`VanillaLootOracle` 使用 `CHEST` 参数集与中心原点；
覆盖表里出现当前原生注册表没有的物品时，会临时替换成占位物品并在输出时映射回原 id。

### 5.2 结构定位

`core/structure/RandomSpreadLocator` 复刻原版 `RandomSpreadStructurePlacement`：

- 网格坐标 `floorDiv(chunk, spacing)`，`limit = spacing - separation`；
- `linear` 取一次 `nextInt(limit)`，`triangular` 取两次取平均（原版 `RandomSpreadType`）；
- 候选区块必须再过 `frequencyAllows`：`default` / `legacy_type_1`（前哨站）/
  `legacy_type_2`（埋藏宝藏，盐 10387320）/ `legacy_type_3`（矿井）四种历史 reducer，
  全部使用 Legacy LCG，参数位置不能互换；
- `exclusionZone` 会递归检查附近区块是否属于另一个结构集；
- 要塞是 `concentric_rings`，位置直接取原生 `ChunkGeneratorStructureState.getRingPositionsFor`；
- 沙漠水井等 `FEATURE` 型来源用 `FeatureScanner.rarityAllows` 复刻
  `RarityFilter.onAverageOnceEvery`（`nextFloat() < 1/rarity`）。

`data/<version>/structures.json` 里的 `decorationIndex/generationStep`、
`featureIndex/featureStep(...Deep)` 是**钉死的装饰阶段下标**。它们由
`DecorationIndexDumpTest` 从原生注册表导出、`tools/pin-decoration-indices.py` 写回，
避免不同版本注册表遍历顺序变化导致随机流错位。

### 5.3 放置重放与容器捕获

`VanillaRuntime`：`Bootstrap.bootStrap()` + `WorldLoader.load` 载入数据包与注册表，
为主世界/下界/末地各建一份 `ChunkGenerator` / `RandomState` / 高度信息；用 Objenesis 构造
`StubMinecraftServer` 与 `SuppressingServerLevel` 空壳，供战利品上下文使用；`ThreadLocalTemplates`
给并发任务提供互不干扰的结构模板副本；`NativeTerrainCache` 对原生地形做 LRU + single-flight。

`RecordingLevel` 是动态代理的 `WorldGenLevel`：

- 只记录放置过程中写入的方块与方块实体，写入限制在装饰中心 3×3 区块（与原版生成区一致）；
- 高度查询在原生地形之上叠加本次写入的方块（`overlayTop` / `overlayHeights`），
  供沉船、埋藏宝藏、沙漠神殿等 `HEIGHT_SENSITIVE` 结构使用；
- `lootSources()` 把 `RandomizableContainer`、`BrushableBlockEntity`、
  `DecoratedPotBlockEntity`、`VaultBlockEntity`、箱子矿车翻译成 `ContainerRecord`。
  宝库的 `lootSeed` 恒为 0（原版哨兵值），交给序列预测器。

`StructureScanner` 是常规入口：按包围盒覆盖的区块逐个建立装饰流并重放
`StructureStart.placeInChunk`。特殊路径：

- **沉船** `TemplateFastPath`：不逐区块放置，直接读模板 NBT 与结构方块数据标记，
  映射 `map_chest/treasure_chest/supply_chest`（对齐原版 `ShipwreckPieces.MARKERS_TO_LOOT`），
  并按区块内被消耗的 `nextLong()` 序号推导 `lootTableSeed`；搁浅船在起始区块先消耗一次
  `nextInt(3)`（`calculateBeachedPosition`），高度取最小 `WORLD_SURFACE_WG` 减半高再减抖动。
- **要塞** `StrongholdScanner`：原版 `StrongholdPieces` 有静态状态，需类锁串行；
  `selective` 模式只放置含箱子区块及其影响后续状态的前置区块。
- **刷怪房** `DungeonScanner`：`monster_room` 与 `monster_room_deep` 同阶段，按 index 顺序重放。
- **矿井**：`MineshaftReferences` 在 ±8 区块内反查会覆盖当前区块的起点；
  `PreStructureMineshafts` 维护走廊蜘蛛网（NBT `sc`）状态；`MineshaftBiomes` 只把落在
  `minecraft:sulfur_caves` 的走廊槽位视为可能产出 Bounce。
- `PreStructureFeatures` 补放目标阶段之前的所有特征：装饰流按阶段共享，少放一个特征
  就会让后续 `nextLong()` 序号整体前移。

`SearchEngine` 用 `ScanCache` 按 `(seed, version, dimension, structure, chunk)` 复用扫描结果；
矿井因跨区块引用不缓存。

### 5.4 序列型来源

- `VaultSequencePredictor`：`minecraft:chests/trial_chambers/reward[_ominous]`，
  一次开启 = 一次 `oracle.rollWithRandom`，`startOffset` 指定从第几次开始，
  最多推演 2000 次。原版 `VaultBlockEntity.resolveItemsToEject` 用
  `getRandomItems(params)` 且嵌套表沿用同一 `LootContext`，所以连续调用即可复现。
- `ElderGuardianPredictor`：手工按 `entities/elder_guardian` 的池顺序消耗随机数，
  顺序为「海晶碎片 0..2 → 抢夺加成浮点 → 权重 3/2/1 的鳕鱼/海晶砂粒/空 →
  抢夺加成 → 湿海绵 → 钓鱼表判定 `nextFloat()` → （命中则嵌套抽 `gameplay/fishing/fish`）
  → 权重 4/1 的潮汐模板」。已对照 `VanillaEntityLoot.elderGuardianLootTable()`、
  `EnchantedCountIncreaseFunction`（`Math.round(level * nextFloat())`）、
  `LootItemRandomChanceWithEnchantedBonusCondition` 与 `LootPool.addRandomItem` 核实。

这两类命中在尚未扫到真实宝库/海底神殿时，`estimated=true`，坐标退化为搜索中心；
扫到后由 `SearchState` 记录 anchor 再回填。

### 5.5 搜索调度

`SearchEngine.search`：

1. `planSources` 把目标物品展开成结构集 / feature / 序列型任务，并给出跳过设置的警告；
2. 先跑序列型阶段（宝库、远古守卫者）；
3. 按距离分带：首带 750 格，之后 `edge = bandEnd + max(750, bandEnd)` 逐带加倍；
   带内候选按距中心距离平方排序；
4. 固定线程池并行执行 `Work`（矿井/刷怪房因共享状态把并行度限制到 4）；
5. 每个 Work 产出 `RawHit`，`mergeResult` 扣减每个物品配额并实时推送 `hit` / `progress`；
6. 全部配额达标、单物品 `itemBudgetSeconds` 耗尽或被取消时结束，生成 `SearchReport`。

`itemBudgetSeconds = 0` 表示不限时；超时或序列 2000 次未命中会写入 `warnings`（UI 标 ⚠️）。
`elapsedNanosPerItem` 是达标时刻，`costNanosPerItem` 是累计投入，`itemDurationNanos` 是展示耗时。

## 6. 数据目录

### `data/<version>/structures.json`

```json
{
  "version": "26.2",
  "structureSets": [
    {
      "id": "minecraft:buried_treasures",
      "placement": {
        "type": "random_spread",
        "salt": 0,
        "spacing": 1,
        "separation": 0,
        "spreadType": "linear",
        "frequency": 0.01,
        "frequencyReductionMethod": "legacy_type_2",
        "locateOffset": [9, 0, 9]
      },
      "structures": [{ "id": "minecraft:buried_treasure", "weight": 1 }]
    }
  ],
  "structures": [
    {
      "id": "minecraft:buried_treasure",
      "zhName": "埋藏的宝藏",
      "dimension": "overworld",
      "set": "minecraft:buried_treasures"
    }
  ]
}
```

`VersionRegistry.parse` 会校验版本声明、spacing > separation、concentric_rings 必备字段、
权重为正、结构归属、维度与 skipKey 白名单。`PlacementSpec.locateOffset` 只用于坐标换算，
不参与起始区块计算。

### `data/<version>/items.json`

`groups[]`（含 `defaultSelected`）与 `items[]`；每个 source 有
`label / structures[] / method / pieces[] / skipKey`。`method` 白名单为
`chest, archaeology, pot_sherd, corridor_pot, vault, vault_ominous, entity`，
与 `LootMethod` 枚举一致。`ItemCatalog` 校验 group 引用、重复 id、空 sources 等。

### 战利品表覆盖

`data/<version>/loot_tables/**` 只保存**与 26.2 基线有差异**的表（由
`tools/extract-loot-table-diff.py` 生成）。`Main` 把相对路径转成
`minecraft:<path 去掉 .json>`，`VanillaLootOracle.parseOverride` 用
`LootTable.DIRECT_CODEC` + `RegistryOps` 解析；解析失败会退回原生表并计入
`overrideParseFailures`。`data/26.1.2` 与 `data/26.3/26.4` 的覆盖表用于修正版本间掉落差异。

### 数据生成与校准顺序

| 脚本                                                          | 作用                                                                  |
| ------------------------------------------------------------- | --------------------------------------------------------------------- |
| `tools/gen_version_data.py`                                   | 从 26.2 复制两份 catalog JSON 到 26.1.2 / 26.3（保留已有覆盖表）。    |
| `tools/gen-26.4-data.py`                                      | 从 26.3 生成 26.4，核对 jar 内 structure_set 并追加 Flurry 相关条目。 |
| `tools/extract-loot-table-diff.py`                            | 从各版本 client jar 提取差异战利品表。                                |
| `tools/extract-item-icons.py`                                 | 导出 `web/public/icons/*.png`。                                       |
| `tools/calibrate-all.ps1` → `tools/pin-decoration-indices.py` | 四版本跑装饰索引校准并写回目录。                                      |

建议顺序仍是先 gen 再 extract；现在 gen 只覆盖两个 catalog JSON，不会抹掉覆盖表。

### 版本适配层差异（引擎侧）

| 版本   | 主要差异                                                                                                                                                         |
| ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 26.1.2 | `Structure.generate` 带 age 参数；地形分 BIOMES/NOISE/SURFACE/CARVERS 四步；`StructureStart.isValid()` 可用。                                                    |
| 26.2   | 默认编译版本；`buildSurface` 参数换成 `possibleBiomes`；频率枚举仍在 `StructurePlacement`。                                                                      |
| 26.3   | `createResolver` 查群系、`FeaturePlacer` 放特征、`buildTerrain` 一次生成；频率枚举迁到 `AbstractSpreadingStructurePlacement`。                                   |
| 26.4   | `RandomState.createForTesting`、`StructureStart.load(context, tag, seed, structure)`、`PotDecorations` 改成 `Optional<Holder>`；`isValid()` 被移除，改为判非空。 |

`dump-mod/versions/*/src` 另有 `PotItems` / `VersionCompat` 两个小适配类，差异同上。

## 7. 真值体系与回归验证

### dump-mod（真实服务器侧）

`dump-mod` 是独立 Fabric 构建，通过 JVM 属性 + 生命周期事件驱动，跑完自动 `server.halt(false)`：

| 生产线          | 触发                                                             | 输出 schema                                     |
| --------------- | ---------------------------------------------------------------- | ----------------------------------------------- |
| 容器快照        | `/lootradar containers` 或 `-Dlootradar.autoDump=<r>`            | `loot-radar-dump/2`                             |
| 结构源普查      | `-Dlootradar.sourceRadius=<r>`                                   | `loot-radar-source-truth/1`                     |
| Bounce 矿井普查 | `-Dlootradar.coverageRadius=<r>`                                 | `loot-radar-bounce-truth/1`                     |
| 序列/抢夺校准   | `-Dlootradar.sequence=...`、`-Dlootradar.lootingCalibration=...` | `loot-radar-sequence/2`、`loot-radar-looting/1` |

`mod.gradle` 的 `runServer` 会把所有 `lootradar.*` 系统属性透传；`writeCoverageLaunch` 生成
可直接启动 `KnotServer` 的命令 JSON。`ContainerCollector` 从真实区块收集方块实体与箱子矿车，
并用 `lootTableSeed` 复算内容。宝库/远古守卫者需交互，标记为「交互验证」。

### 工具链对应关系（摘要）

| 脚本                                                             | Gradle / 入口                                                                              | 产物                                                  |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | ----------------------------------------------------- |
| `tools/run-source-tests.py`                                      | `:engine:writeRoomCoverageLaunch` → `SourceCoverageMain`；`:vXX:writeCoverageLaunch` → mod | `<out>/<v>_<seed>/<dim>/{prediction,truth}.json`      |
| `tools/run-bounce-census.py` + `run-bounce-3v.ps1`               | mod + `:engine:runBounceCoverage`                                                          | truth/prediction + `bounce-coverage.json`             |
| `tools/report-source-coverage.py` / `compare-bounce-coverage.py` | 纯 Python 统计                                                                             | 覆盖率/准确率表                                       |
| `tools/audit-source-census.py`                                   | 直接解析 `.mca` 复核真值                                                                   | `region-audit.json`                                   |
| `tools/run-quality-cycle.ps1`                                    | `:engine:runVerify`、`runBench`                                                            | `docs/verify-report.md`、`docs/bench-report.{md,csv}` |
| `tools/verify-scan-cache.py`                                     | 调 HTTP `/api/search`                                                                      | 冷/热缓存耗时对比                                     |

`tools/source_metrics.py` 定义 35 条业务口径（结构 × 方式），是各覆盖率脚本的共享库。

### Java 测试

- `core/src/test`：`RngStreamsTest`（与 `java.util.Random` 对照）、`RandomSpreadLocatorTest`
  （性质测试）、`VersionRegistryTest`（数据常量与非法 JSON）。
- `engine/src/test`：`RngParityTest` 直接把 core RNG / 候选区块 / 频率 reducer 与原生类逐值对照；
  其余多为固定种子 + 真实服务器坐标的黄金向量（沙漠神殿、雪屋、矿井、要塞、陶罐、宝库等），
  以及 `LootTableOverrideTest`、`NativeTerrainCacheTest`、`TemplateFastPathTest`。
- `DecorationIndexDumpTest` 不是断言测试，而是校准数据生成器，输出
  `engine/build/decoration-index-<版本>.json`。

多数 engine 测试会启动 `VanillaRuntime`，耗时较长；切换版本用
`-Pminecraft_version=<版本>`。IDE 若提示 `dev.lootradar.core.*` 无法解析，多半是 IDE 未导入
Gradle 项目；以 `gradlew :engine:test` 为准（本机已全绿）。

## 8. 已知问题与维护注意

- `dump-mod` 的 `SourceCoverageDump` 定义了并行/预取开关，但 `tick()` 未调用对应方法，
  目前总是同步生成；`parallelFeatures/prefetchTerrain` 只写进 JSON 描述。要真正启用需重写
  区块生成调度并重新采集全部真值。
- `ContainerCollector.collect` 按区块矩形收集真值、未做圆形半径过滤，且会强制生成区块；
  与 `VerifyMain` 的圆形口径不完全一致。修改会改变已有 truth 数据，需整条覆盖率链重跑。