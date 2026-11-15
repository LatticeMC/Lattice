# 任务文档：从 C2ME / Lithium 移植一致性可保证的微优化到 Lattice

- 创建日期：2026-11-08
- 状态：2026-11-15 已完成实现、回归验证与最终独立复审
- 关联版本：Lattice = Minecraft 1.21.11（Purpur/Paper 上游）；Lithium 已对齐 `1.21.11` 分支；C2ME 用默认分支（主干）。

## 1. 背景与目标

Lattice 是基于 JVM + JNI/native 的 Minecraft 服务端优化 fork，已自研：native 数学、DFC 密度函数编译、native chunk IO、allocations 减少。本次不改变上游 chunk 调度模型，不引入 C2ME 的多线程 worldgen 架构。

目标：从 C2ME 与 Lithium 中挑选**可独立移植**（不依赖其特有架构）且**一致性可保证**（bit-exact / 数学等价 / 纯分配减少）的微优化，移植进 Lattice。

## 2. 调研来源与版本对齐

- **C2ME**（默认分支）：排查 `c2me-opts-math`、`c2me-opts-allocs`、`c2me-opts-natives-math`、`c2me-opts-worldgen-general`、`c2me-opts-worldgen-vanilla`、`c2me-opts-scheduling`、`c2me-fixes-*`。结论：`natives-math` 已被 Lattice native 覆盖；`scheduling`/`fixes` 多为 FlowSched/多线程耦合，不可拿；真正可拿的是 math/allocs/worldgen 的小 mixin。
- **Lithium**（`new-repos/lithium-1.21.11`，branch `1.21.11` 与 Lattice 精确对齐）：排查 `common/`（约 600 个 java）。重要发现：该分支**已删除** `fastFloor/fastCeil/fastInverseSqrt/lerp` 重写（无"负数不等价"风险），也**已删除**噪声/DensityFunction 采样重写（Lattice 自研 DFC 本不需要）。现存优化多为语义保持的小改进。
- 本次核验基线：Lattice `ed3bbbf80419641ebf3f1b817134fd8cb04e527b`；Lithium `09dda7b0cfcf8e9978b594596061d48b6fb6129d`；C2ME `9ce74c82c9e6e088629e5a1dbd708413de06bc08`。邻仓仅作只读参考。

## 3. 核心结论

2026-11-15 核验修正：本清单是候选方案，不是对当前 Lattice 行为的证明。以当前补丁生成的代码为 oracle；不重复实现已覆盖项，不为移植改变序列化顺序、回调顺序、随机序列、位置对象所有权或可更新的 Holder 行为。

| # | 当前实现与执行决定 | 验收 |
|---|---|---|
| 1 | 新增 16385 项紧凑表，删除永久 65536 项表；保留 Lattice 的 double → long → 掩码入口，不采用 Lithium 的 int 强转 | 全部索引 raw bits 自校验，入口极值、NaN、Infinity 对照 |
| 2 | 已有 native bulk unpack、resize remap 缓存。pack 复用有界、可重入的 int 缓冲和身份映射；按首次出现顺序生成独占 palette/long[]。count 仅使用 fastIterator 消除逐条 entry 分配 | 位流、palette 顺序、默认值、未使用条目、重复引用、回调顺序、异常恢复与重入对照 |
| 3 | 已有 Heightmap.updateAll 和 LevelChunk 接入，不再移植 | 合并与独立更新回归 |
| 4 | 复用已有 SingleThreadedRandomSource，仅替换方法局部的 carver、初始生物生成、geode 与随机结构放置实例；WorldgenRandom 明确识别该实现的 next(bits) | 混合 API、seed 设置、fork、Gaussian、世界生成 seed API、实际结构坐标对照；不替换共享 RNG 或受保护 RNG |
| 5 | Aquifer 已使用 barrierNoiseValue 字段，并在 computeSubstance 的压力采样分支入口重置 NaN，不再重复实现 | 压力公式、NaN、复用次数、连续 computeSubstance 及提前返回后的自动重置；原文 234MB/s 不代表本机测量 |
| 6 | Block.isShapeFullBlock 已直接使用 Moonrise 的 VoxelShape 实例缓存，不添加线程本地 LRU | 保持上游行为，不引入新的形状量化 |
| 7 | CompoundTag/AttributeMap 已 fastutil；FlowingFluid 热路径已使用 Moonrise 直接索引缓存，不实例化旧 BlockStatePairKey | 保持已有实现。seaLevel 是 Holder.value().seaLevel() 字段读取，永久缓存会丢失 Holder 更新，故不采用。ServerLevel 明确要求独占 BlockPos（回调可保存），故不复用 MutableBlockPos |
| 8 | Aquifer x/z 的 floorDiv(64) 及 floorDiv(16) 改算术移位，保留 y 的除以 40；不为了源码内联重复 JIT 已可内联的 clampedMap/clampedLerp | 负数、整数极值、分界点与确定性随机坐标对照 |

`WorldgenRandom.next(bits)` 原本只直接调用 LegacyRandomSource.next；其他实现通过 nextLong 取高位。忽略此分派差异会改变 LCG 消耗次数，因此 RNG 替换与包装器适配必须同时落地。现有 Gaussian reseed 行为保持不变。

Lithium 的 palette 快路径在条目数量相同时保留旧 ID；当前 Lattice pack 按存储中首次出现重排 ID，两者不能直接认定为 bit-exact。Lithium count 还会改变回调顺序及零计数条目，不采用。这里只借鉴临时内存复用和身份哈希方案，不替换实时 HashMapPalette/Moonrise/Anti-Xray 契约。

两边的候选微优化不依赖近似精度，但是否语义保持必须在本 fork 的接口和调用链上逐项核验。正弦 LUT 压缩与 palette 临时内存复用主要减少常驻内存或分配，不能在没有测量时宣称 CPU/吞吐提升。

## 4. 最终交付范围

| # | 状态 | 交付或已有证据 |
|---|---|---|
| 1 | 已实现 | `0140` 接入 `CompactSineLut`。16385 个 int 表项及单独 midpoint 替代 65536 个 float 表项，表数据约减少 192 KiB（75%）。启动逐位校验，不保留旧表 |
| 2 | 已实现 | `0140` 优化 `PalettedContainer.pack/count`；保留原有 native unpack、resize remap、实时 palette、Moonrise 和 Anti-Xray，不直接移植 `LithiumHashPalette` |
| 3 | 已覆盖 | `Heightmap.updateAll` 与 `LevelChunk` 的合并更新已有实现；`0131` 已进一步优化索引。本次仅回归验证 |
| 4 | 已实现 | `0141` 在四处方法局部实例复用现有 `SingleThreadedRandomSource`，同时适配 `WorldgenRandom.next(bits)`；不全局替换 `RandomSource.create`，不改变 AES 受保护流 |
| 5 | 已覆盖 | 当前 `Aquifer.NoiseBasedAquifer` 已没有 `MutableDouble` 热路径分配；本次新增公式与生命周期回归，不新增字段优化 |
| 6 | 已覆盖 | `Block.isShapeFullBlock` 已调用 `CollisionVoxelShape.moonrise$isFullBlock`，形状对象自身有缓存；不再叠加线程本地 LRU，不改变 Moonrise 既有语义 |
| 7 | 已覆盖或不采用 | NBT、AttributeMap 已 fastutil；FlowingFluid 的墙面穿透热路径已采用 Moonrise unique-ID 直接索引缓存，旧 pair-key 不在热路径实例化。seaLevel 永久缓存与 MutableBlockPos 复用不符合当前契约，明确不采用 |
| 8 | 已实现必要部分 | `0141` 将 Aquifer x/z 的除以 16、64 改为算术移位。y 的除以 40 保留；小型 `Mth.clampedMap/clampedLerp` 交给 JIT 内联，不复制表达式 |

缓存代价：标准 64/4096 项容器的 scratch 按线程延迟分配，仅前四层重入缓存；返回的 palette 列表和 packed long[] 仍独占。超过缓存深度使用临时 scratch。正弦初始化包含表构建与 65536 项完整校验，不能将内存减少解读为正弦计算 CPU 提升。

## 5. 一致性验证方法（如何证明"可保证"）

- 正弦：运行时检查全部 65536 个索引的 raw bits；测试覆盖 double 入口的舍入边界、NaN、Infinity、饱和转换和确定性随机输入。
- palette：冻结优化前的 Lattice 打包算法作为 oracle，比较 palette 身份与首次出现顺序、bits 和 long[]；覆盖相等但不同身份的值、重复引用、未使用条目、真实零位/线性/HashMap/全局转换、Anti-Xray preset、延迟消费、六层重入和已有身份映射后的异常清理。
- RNG：LCG 公式相同只是必要条件；同时比较包装器消耗次数、混合 API、Gaussian reseed、fork、世界生成 seed API、NormalNoise、实际结构坐标及 AES 受保护分支。
- 移位：`Math.floorDiv(x, 1 << n) == x >> n` 对 int 的正数、负数和极值成立；测试覆盖边界和 100000 个确定性随机坐标。
- 已覆盖项：用既有 heightmap、native palette、Aquifer、噪声、随机 tick 与流体测试回归；新增 Aquifer 压力公式和自动重置生命周期测试。不把上游现有行为未经证明地称为与所有 vanilla 输入等价。

## 6. 雷区：一致性不可保证，不要拿

**C2ME**
- `Util.combine*` 改 asyncutil（异常/取消传播语义不同）
- `unloadChunks` 一次性排空（改变卸载节奏，非语义中立）
- Aquifer 的 BlockPos 打包 + 排序网络（大块耦合 vanilla 内部）
- 多线程 worldgen 正确性修复（非性能，且 Lattice 未重写 chunk system 不需要）

**Lithium**
- POI `getRandom`（注释自承"不返回与 vanilla 同一 POI"，选点不同）
- 随机 tick 优化（分布保持但同一 RNG 流被 tick 的具体方块变了，破坏作物/火蔓延可复现）
- 红石"更新减少"（类注释明说"冗余更新可在游戏内被检测到"，陷阱门/特定布局会错）
- 形状特化 Shapes.create 的非 1/8 对齐形状（`Math.round(…*8)/8` 量化偏离 vanilla 插值）
- 实体支撑块缓存（Lithium 自己标 experimental，失效不完全会变落地判定）
- 碰撞单轴 box 缩小（位移 bit-exact，但返回碰撞列表枚举变少，下游若依赖完整列表则语义变）

**规律**：所有"缓存型"优化隐含"失效路径必须完全正确"——漏一个失效点就悄悄改变行为，与 bit-exact 目标冲突。优先拿纯算法/数学/数据结构替换，慎拿缓存型。

## 7. License 注意

C2ME 不是统一 LGPL：本次检查的 RNG、Aquifer、StructureWeightSampler 文件带 **MIT** 头，作者 ishland，2021-2026；仓库采用文件级许可，应逐文件核对。Lithium 使用 **LGPL-3.0**。紧凑正弦算法保留 coderbot16 与 jellysquid3 署名、来源及 `LGPL-3.0-only` 标识；许可文本复用仓库已有的 [LGPL-3.0](licenses/LGPL-3.0.txt)、[GPL](licenses/GPL.md) 和 [MIT](licenses/MIT.md)。已有平台 RNG 的复用和独立实现的 palette 临时内存管理不复制 C2ME/Lithium mixin。分发时还须遵守 Paper/Purpur/Lattice 各自许可，不能把本节理解成统一再许可或法律合规保证。

## 8. 执行记录

- [x] 源码和调用链逐项核验，原候选清单已由 §3/§4 的最终裁决取代。
- [x] 在当前 `ver/1.21.11` 分支完成 `0140`、`0141` 及回归测试；未修改 ignored 生成源码。
- [x] 独立执行 paperweight 重建和补丁应用，编译成功；14 个相关套件共 58 项测试通过，无失败、错误或跳过。
- [x] 运行分配/墙钟耗时微基准，功能与基准工具分开提交；测量边界见 §10。
- [x] 最终独立只读复审核对当前 diff、最新 XML、需求覆盖及测量结论，未发现 blocker；将非阻断的“CPU 中位数”措辞修正为墙钟耗时。

## 9. 源文件索引（便于执行时定位）

**C2ME**
- `c2me-opts-worldgen-general/**/SimplifiedAtomicSimpleRandom.java`（+ `MixinRedirectAtomicSimpleRandom.java`）
- `c2me-opts-math/**/MixinPerlinNoiseSampler.java`、`NoisePacking.java`、`CachedArrays.java`
- `c2me-opts-worldgen-vanilla/**/MixinAquiferSamplerImpl.java`、`MixinBlock.java`、`MixinStructureWeightSampler.java`
- `c2me-opts-allocs/**/MixinNbtCompound*.java`、`MixinIdentifier.java`、`ObjectCachingUtils.java`、`MixinOreFeature.java`、`MixinCombinedBlockPredicate.java`、`MixinMaterialRuleContext.java`

**Lithium（lithium-1.21.11）**
- `common/util/math/CompactSineLUT.java` + `mixin/math/sine_lut/MthMixin.java`
- `common/world/chunk/LithiumHashPalette.java` + `mixin/chunk/serialization/{SimpleBitStorageMixin,PalettedContainerMixin}.java` + `common/world/chunk/CompactingPackedIntegerArray.java`
- `common/world/chunk/heightmap/CombinedHeightmapUpdate.java` + `mixin/world/combined_heightmap_update/LevelChunkMixin.java`
- `mixin/gen/cached_generator_settings/NoiseBasedChunkGeneratorMixin.java`
- `mixin/shapes/specialized_shapes/VoxelShapeMixin.java`
- `mixin/alloc/nbt/CompoundTagMixin.java`
- `mixin/cached_hashcode/FlowingFluid$BlockStatePairKeyMixin.java`
- `mixin/alloc/chunk_random/ServerLevelMixin.java`
- `mixin/collections/attributes/AttributeMapMixin.java`
- `common/block/redstone/RedstoneWirePowerCalculations.java`（仅功率函数可搬）

## 10. 本地验证与限制

环境：Windows 11 x86_64，Dragonwell `21.0.12.0.12+8-GA`，Gradle 9.2.0。native palette 使用现有本地 DLL，CPU 自动选择 AVX2/BMI2，禁止验证期间下载库。

补丁重建必须分三次调用，不能将 apply 与 compile/test 放在同一个任务图：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Dragonwell\dragonwell-21.0.12.0.12+8-GA'
.\gradlew.bat :lattice-server:setupMacheSources --rerun
.\gradlew.bat :lattice-server:applyMinecraftPatches
.\gradlew.bat -I lattice-server/src/test/gradle/optimization-porting-tests.init.gradle -I lattice-server/src/test/gradle/palette-bulk.init.gradle :lattice-server:test --console=plain
```

结果：补丁实际应用成功，编译成功。`lattice-server/build/test-results/test/TEST-*.xml` 的 14 份最新报告合计 58 项通过、0 failures、0 errors、0 skipped。新增套件分别为：正弦 2、palette 5、RNG 5、Aquifer barrier 2；其余 44 项为相关已有回归。native 套件中的 JVM fallback 警告来自刻意隔离 JNI/Mock 失败的测试，不是正常 native 调用失败。

首轮验证遇到两类运行器错误，未以生产代码降级掩盖：

- 同一 Gradle 命令运行 apply 与 test 时，`compileJava` 报 `uses this output of task ':lattice-server:applyMinecraftFeaturePatches' without declaring an explicit or implicit dependency`；source patches 同类报错。按项目既有分步构建规则恢复。
- `--tests` 筛选与已有 `@Suite/@SelectClasses` 组合让无关套件报 `NoTestsDiscoveredException`。新增 scoped init script 限定 class 文件集合，不修改默认全仓测试配置；结构测试补齐 Bootstrap 初始化后通过。

基准命令：

```powershell
.\gradlew.bat -I lattice-server/src/test/gradle/optimization-porting.init.gradle :lattice-server:optimizationPortingBenchmark --console=plain
```

ThreadMXBean 分配与墙钟耗时微基准采用冻结旧打包实现（包含原先已缓存的 remap）、相同同步锁、同一 fixture、交替测量、warmup 5000、rounds 2000、samples 7 中位数；输出与校验和用于避免死代码消除。完整测量记录随基准工具保存在 [OPTIMIZATION-PORTING-BENCHMARK.md](OPTIMIZATION-PORTING-BENCHMARK.md)。

未执行生产服务器/JFR 吞吐验收，也没有真实 carver 或初始生物生成端到端 A/B；这两处调用链只改变非逃逸的局部 RNG 构造，随机 API/seed 消耗与实际噪声/结构路径已有对照。不能把微基准外推为实际 tick、chunk 生成速度或生产净收益。未进行截图、部署、push 或其他外部状态写入。
