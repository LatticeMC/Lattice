# 噪声内核与 JNI 边界基准（2026-08-16）

## 目标

本轮将两个容易混淆的问题分开测量：

1. Perlin、Octave、Double-Perlin、Interpolated、Simplex 的纯 Native 批量内核是否有收益。
2. Java 世界生成代码逐点调用 JNI 时，跨界成本是否低于 Java 实现本身。

这不是 `NativeDensityFunction` 的重复测试。DensityFunction 基准测的是整棵 density tree 的编译、调度、缓存和批量包装；本报告同时给出其下层噪声数学内核与单点 JNI wrapper 的独立数据。

## 环境与正确性

- CPU：AMD Ryzen 5 9600X，family `0x1A`，model `0x44`
- Java：Java 25.0.3
- Native baseline：x86-64 `SSE4.2 + POPCNT`，`-O3`，`-ffp-contract=off`
- 禁止 FMA 与 fast-math
- Native 正确性：Perlin、Octave、Double-Perlin、Interpolated、Simplex 共 5 个测试全部通过
- Java JNI 基准：所有操作均通过 parity；输出与 Java reference 逐位一致

测试期间发现并修复了一个独立正确性问题：Native Simplex 3D 错误地使用了对象保存的 `xo/yo/zo`，而 Minecraft 的 `SimplexNoise.getValue(x, y, z)` 不使用这些偏移。修复后增加了“保存的 origin 不影响 2D/3D”回归测试。

## 纯 Native 内核结果

下表为 P50 `ns/point`，括号为相对 scalar 的加速比。`49` 和 `245` 对应 NoiseChunk 常见的 Y 列和完整 slice-row 工作量；AVX-512 路径当前从 `count >= 129` 开始，较小批次仍走 AVX2。

| 操作 | count | scalar | AVX2 | AVX-512 |
|---|---:|---:|---:|---:|
| Perlin batch | 49 | 13.338 | 7.507（1.78x） | 7.379（实际 AVX2） |
| Perlin batch | 245 | 13.227 | 7.310（1.81x） | 6.538（2.02x） |
| Perlin Y column | 49 | 15.306 | 6.977（2.19x） | 6.974（实际 AVX2） |
| Perlin Y column | 245 | 15.276 | 6.737（2.27x） | 5.794（2.64x） |
| Octave batch | 245 | 99.987 | 57.317（1.74x） | 51.493（1.94x） |
| Octave Y column | 245 | 109.794 | 50.777（2.16x） | 42.530（2.58x） |
| Double-Perlin batch | 245 | 84.101 | 49.121（1.71x） | 44.727（1.88x） |
| Double-Perlin Y column | 245 | 93.046 | 42.397（2.19x） | 36.359（2.56x） |
| Interpolated batch | 245 | 416.827 | 416.194（1.00x） | 421.786（0.99x） |
| Interpolated Y column | 245 | 513.296 | 252.459（2.03x） | 231.765（2.21x） |
| Simplex 2D batch | 245 | 7.345 | 6.260（1.17x） | 5.917（实际 AVX2） |
| Simplex 3D batch | 245 | 13.273 | 9.286（1.43x） | 9.156（实际 AVX2） |

结论：

- Perlin、Octave、Double-Perlin 的批量和列内核有明确 SIMD 收益。
- Interpolated 的普通 `sample_batch` 仍是标量循环，没有独立 SIMD 收益；Y column 因复用 Perlin 批量路径而有收益。
- Simplex 只有 AVX2 实现；请求 AVX-512 时仍执行 AVX2。
- 这些结果证明噪声数学内核不是当前 worldgen 回退的根因。问题更可能在 density tree 调度、缓存状态、数据准备、JNI 次数和 Java/Native 间的数据搬运。

## Java 25 单点 JNI 结果

现有 Java wrapper 是逐点 JNI。基准中的 `count` 只是连续执行多少个点，并不会把这些点合成一次 JNI 调用。因此应看不同 count 下是否稳定，而不能把大 count 当作批量门槛。

| 操作 | Java P50 ns | Native P50 ns | 结论 |
|---|---:|---:|---|
| Improved Perlin | 约 12.0-13.0 | 约 15.2 | Native 慢约 15%-21% |
| Octave Perlin | 约 138-140 | 约 123-125 | Native 快约 11%-13% |
| Normal Noise | 约 276-279 | 约 250-253 | Native 快约 9%-11% |
| Double-Perlin direct | 约 276-280 | 约 248-249 | Native 快约 11%-12% |
| Blended per octave | 约 479-527 | 约 542-587 | Native 慢约 10%-13% |
| Interpolated direct | 约 489-617 | 约 455-576 | Native 快约 6%-8% |
| Simplex 2D direct | 约 10.7-12.6 | 约 13.4-14.8 | Native 慢约 12%-20% |
| Simplex 3D direct | 约 16.1-17.4 | 约 15.3-17.0 | P50 小幅收益，P95 不稳定 |

生产决策：

- `native.scalar-perlin` 在当前 9600X 上应继续默认关闭。
- 不应把 `BlendedNoise` 拆成多个 octave 单点 JNI；跨界次数抵消了 C++ 计算收益。
- Octave、Normal、Double-Perlin 和 Interpolated 的单点 wrapper 在当前 CPU 上有小幅稳定收益，但仍远低于真正批量 SIMD 的收益。
- 后续高价值方向是让 NoiseChunk 的同批坐标通过一次 JNI 进入现有 batch/Y-column 内核，而不是继续增加单点 JNI wrapper。

## SSE4.2 低端 CPU 的解释边界

当前所谓 `scalar` 是“标量算法使用 SSE4.2 baseline ABI 和编译目标”，不是手写的 SSE4.2 向量内核。9600X 的 scalar 数字只代表 Zen 5 执行该兼容路径的表现，不能代表老 Xeon E5 的缓存、分支、频率与 JNI 成本。

在只支持 SSE4.2 的机器上：

- AVX2/AVX-512 必须报告为 unsupported/skip，不能回退后仍标成 SIMD 结果。
- 应重点测 Java reference 与 Native scalar 的交叉点，以及绝对 `ns/point`。
- 如果老 E5 的 Java JIT 对复杂 Octave/Normal 路径优化较弱，Native scalar 可能比本机更有价值；反之，Improved Perlin 和 Simplex 2D 的 JNI 固定成本仍可能占主导。
- 在取得真实 SSE4.2-only 数据前，不修改自动门禁和默认值。已有显式配置可用于实机 A/B。

## 复测命令

Native 内核：

```powershell
cmake --build lattice-native/build-tests --target lattice_benchmark_noise --config Release --parallel 4
.\lattice-native\build-tests\tests\lattice_benchmark_noise.exe --tier=scalar --warmup=5 --samples=15 --counts=1,49,129,245,2048,24576
.\lattice-native\build-tests\tests\lattice_benchmark_noise.exe --tier=avx2 --warmup=5 --samples=15 --counts=1,49,129,245,2048,24576
.\lattice-native\build-tests\tests\lattice_benchmark_noise.exe --tier=avx512 --warmup=5 --samples=15 --counts=1,49,129,245,2048,24576
```

Java 25 单点 JNI：

```powershell
.\gradlew.bat :lattice-server:nativeNoiseJniBenchmark --no-daemon `
  -PlatticeJavaVersion=25 `
  -PnativeNoiseJniBenchmarkWarmup=5 `
  -PnativeNoiseJniBenchmarkSamples=15 `
  -PnativeNoiseJniBenchmarkCounts=1,8,49,245,2048
```

正确性：

```powershell
ctest --test-dir lattice-native/build-tests -C Release `
  -R '^(perlin_noise|octave_perlin_noise|double_perlin_noise|interpolated_noise|simplex_noise)$' `
  --output-on-failure
```
