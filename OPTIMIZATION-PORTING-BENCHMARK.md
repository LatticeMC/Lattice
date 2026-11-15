# 微优化移植基准记录

- 日期：2026-11-15
- 对应任务：根目录 `OPTIMIZATION-PORTING.md`，功能补丁 `0140`、`0141`。
- 环境：Windows 11 x86_64；Dragonwell `21.0.12.0.12+8-GA`；Gradle 9.2.0；本地 native DLL；自动 AVX2/BMI2。
- 结论：在这组固定输入上，palette 打包和局部 RNG 的分配均减少，墙钟耗时中位数也降低；不代表生产吞吐验收。

## 方法

工具：`PortingAllocationBenchmark`，通过 `optimization-porting.init.gradle` 注册 `optimizationPortingBenchmark`。

ThreadMXBean 测量当前线程分配，`System.nanoTime()` 测量墙钟耗时（不是线程 CPU 时间）；每组 warmup 5000 次，rounds 2000 次，7 个样本交替测量旧/新实现，分别取中位数。baseline 保留优化前已有的 ThreadLocal remap，不将既有收益记入本次；两边持有相同 container monitor，输出消费在锁外。native palette 开启、阈值为 0，加载仓库现有 DLL，不下载库。

使用相同 fixture，两个尺寸分别是 biome 64 项与 block 4096 项，palette 输入规模 3/17/65。fixture 包含相等但身份不同的对象、重复引用和未使用条目，规模表示输入 palette 大小，不等于最终序列化条目数。每次打包仍生成独占结果列表与 packed long[]，测量包含其消费。volatile sink 与 checksum 防止死代码消除。正确性由同版本测试的逐位/身份对照验证，不把耗时相似当作正确性证据。

RNG 测量包含创建 `WorldgenRandom` 与底层 RNG，以及 100 次 `nextLong`；结果不能等同于所有世界生成 RNG 工作负载。正弦 LUT 的价值是常驻表数据减少，未测其 CPU 吞吐。

## 结果

每项单位为 ns/次与 B/次，2026-11-15 17:22 本机运行：

| 工作负载 | baseline ns | optimized ns | baseline B | optimized B |
|---|---:|---:|---:|---:|
| pack/64/3 | 938.3 | 735.6 | 968.0 | 440.0 |
| pack/64/17 | 1052.4 | 591.8 | 1400.0 | 440.0 |
| pack/64/65 | 2773.9 | 1691.8 | 3336.0 | 936.0 |
| pack/4096/3 | 8107.8 | 7071.2 | 18800.0 | 2280.0 |
| pack/4096/17 | 8026.5 | 6235.1 | 19312.0 | 2360.0 |
| pack/4096/65 | 8414.6 | 6859.5 | 22768.0 | 4376.0 |
| local-worldgen-rng/100-nextLong | 784.7 | 210.1 | 168.0 | 144.0 |

校验和：`6508939628494173984`。Gradle 退出码 0；对应的最终 58 项相关回归通过，无失败、错误或跳过。

## 重放

在已按项目规则完成 source setup 与 patch apply 的当前工作区执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Dragonwell\dragonwell-21.0.12.0.12+8-GA'
.\gradlew.bat -I lattice-server/src/test/gradle/optimization-porting.init.gradle :lattice-server:optimizationPortingBenchmark --console=plain
```

可用 `-PportingBenchmark.warmup=5000`、`-PportingBenchmark.rounds=2000`、`-PportingBenchmark.samples=7` 明确覆盖默认参数。请保留相同 JDK/native/fixture 条件；墙钟耗时受 JIT、GC 和宿主负载影响。

## 边界

临时分配减少以 ThreadLocal 常驻 scratch 为代价，标准容器前四层重入按需缓存，结果对象仍独占。没有生产服务器/JFR、真实 carver 或初始生物生成端到端 A/B，本记录不证明实际 tick、GC 停顿或 chunk 生成速度改善。
