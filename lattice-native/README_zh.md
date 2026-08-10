# lattice-native

Minecraft 1.21.11 服务器热点的高性能原生优化，通过 JNI 暴露给 Java 端。

## 状态：精简骨架（T1 重写进行中）

本子项目此前包含约 47,000 行推测性 C++ 代码，其中仅有一个模块（Pathfinder，本身也是存根）可从 Java 调用。这些代码已归档至 [`attic/`](./attic) 目录，不再参与构建。完整的审计记录请参阅与本仓库一同归档的对话。

当前构建内容：

```
lattice-native/
├── CMakeLists.txt                     # 精简骨架，启用 LTO，x86-64-v1 + SSE2 基线
├── include/
│   └── lattice/
│       ├── config.hpp                 # 编译时标志
│       ├── dispatch.hpp               # 运行时 CPU 特性快照
│       └── lattice.hpp                # 总括头文件
├── jni/
│   ├── jni_helper.hpp                 # 无异常 JNI 辅助函数
│   └── loader.cpp                     # JNI_OnLoad + Java_…_nativeCpuSummary
└── src/
    └── core/
        └── cpu/
            └── detect.cpp             # CPUID / getauxval / sysctl 填充
```

生成的 `liblattice.so` / `lattice.dll` 目前仅暴露一个 Java 可见符号：

```java
// com.latticemc.lattice.nativelib.LatticeNative
static native String nativeCpuSummary();
```

返回类似 `lattice cpu: vendor=Intel tier=AVX2 +BMI2(fast) family=0x6 model=0x8E` 的字符串。

## T1 热点模块

已选择三个 Minecraft 1.21.11 热点进行原生重写，每个模块作为独立模块添加，其行为与参考 Java 实现保持精确一致（通过 `-Dlattice.verify=true` 验证）：

| 模块 | Java 包（lattice-server） | Minecraft 目标 | 状态 |
|---|---|---|---|
| **区块序列化器**（libdeflate zlib） | `com.latticemc.lattice.nativelib.NativeChunkSerializer` | `RegionFile` COMPRESSION_ZLIB 路径 + `Inflater/Deflater` | **阶段 1 ✓** |
| **调色板存储**（位压缩 `long[]` 操作） | `com.latticemc.lattice.nativelib.NativePaletteOps` | `PalettedContainer`、`PackedIntegerArray` | **阶段 1 ✓**（标量；BMI2/SIMD 计划中） |
| **光照引擎**（`LevelPropagator` + `ChunkLightProvider`） | `com.latticemc.lattice.nativelib.NativeLightEngine` | `ChunkBlockLightProvider`、`ChunkSkyLightProvider`、`LevelPropagator` | **阶段 1 ✓**（C++ 实现 BFS，世界查询通过 JNI 回调） |
| **ChunkNoiseSampler 门面**（NoiseRouter 捆绑 + 逐通道缓存） | `com.latticemc.lattice.nativelib.NativeChunkNoiseSampler` | `ChunkNoiseSampler`（路由器驱动的密度函数采样） | **Worldgen-6 ✓** |
| **OreVeinSampler**（逐方块矿脉判定 + Xoroshiro128++） | `com.latticemc.lattice.nativelib.NativeOreVeinSampler` | `net.minecraft.world.gen.OreVeinSampler` | **Worldgen-8 ✓** |
| **DensityFunction 批量网格填充**（NativeDensityFunction.evaluateGrid） | `com.latticemc.lattice.nativelib.NativeDensityFunction#evaluateGrid` | `DensityFunction.fill` / `DensityInterpolator` 单元格预填充 | **Worldgen-9 ✓** |
| **DensityFunction Spline 节点**（三次 Hermite + 递归子样条） | `com.latticemc.lattice.nativelib.NativeDensityFunction#addSpline` | `net.minecraft.util.math.Spline` / `DensityFunctionTypes.Spline` | **Worldgen-10 ✓** |
| **DensityFunction FindTopSurface 节点**（向下 y 扫描 density > 0） | `com.latticemc.lattice.nativelib.NativeDensityFunction#addFindTopSurface` | `DensityFunctionTypes.FindTopSurface` | **Worldgen-11 ✓** |
| **InterpolatedNoiseSampler**（1.16 风格混合噪声；`old_blended_noise` DF 节点） | `com.latticemc.lattice.nativelib.NativeInterpolatedNoise` + `NativeDensityFunction#addOldBlendedNoise` | `net.minecraft.util.math.noise.InterpolatedNoiseSampler` | **Worldgen-12 ✓** |
| **DensityInterpolator**（逐单元格三线性混合，驱动 `kInterpolated`） | `com.latticemc.lattice.nativelib.NativeDensityFunction.Cache#startInterpolation`、`prepareInterpolators`、`setStartDensity`、`setEndDensity`、`setStartDensityRow`、`setEndDensityRow`、`swapBuffers`、`onSampledCellCorners`、`interpolateY/X/Z`、`stopInterpolation` | `net.minecraft.world.gen.chunk.ChunkNoiseSampler.DensityInterpolator` | **Worldgen-13 ✓** |

### NativeChunkSerializer — 阶段 1 范围

用基于 libdeflate 的实现替换 `java.util.zip.Inflater` / `Deflater`，仅支持 zlib 格式。Java 端通过 `NbtIo` 进行的 NBT 解析保持不变。每次调用都受 `LatticeNative.isLoaded()` 保护，当原生库不可用时透明回退到 JDK 流。在 `-Dlattice.verify=true` 下，每次原生调用都会被 JDK 参考实现影子执行并比较输出（压缩使用往返验证，因为 zlib 输出字节在不同编码器间不要求位精确）。

后续 **阶段 2** 可能将 NBT 解析扁平化为原生实现，使用堆外树表示。这取决于测量结果是否表明 NBT 解析（而非解压）是阶段 1 后的主要开销。

压缩库：libdeflate，固定于标签 `v1.20`，通过 CMake `FetchContent` 引入（静态链接，无系统依赖）。

## 构建

需要 CMake ≥ 3.20、C++20 工具链（GCC ≥ 11、Clang ≥ 13、MSVC 19.30+）和 JDK（用于 `<jni.h>`）。

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

选项：

| 选项 | 默认值 | 描述 |
|---|---|---|
| `LATTICE_ENABLE_SIMD` | `ON` | 编译 SIMD 特化（运行时分派） |
| `LATTICE_ENABLE_LTO` | `ON` | Release 模式下启用链接时优化 |
| `LATTICE_BUILD_TESTS` | `OFF` | 构建单元测试 + 差异验证工具 |
| `LATTICE_WARNINGS_AS_ERRORS` | `OFF` | 将警告提升为错误 |

运行时环境变量：

| 变量 | 用途 |
|---|---|
| `LATTICE_CPU_FORCE_SCALAR=1` | 禁用所有 SIMD 特化 |
| `LATTICE_CPU_DISABLE=avx512,bmi2` | 禁用特定 ISA 扩展 |

世界生成 SIMD 覆盖范围：

- DensityFunction 已有独立的 AVX-512 内核（要求 `AVX512DQ`）。Perlin、
  DoublePerlin 和 Simplex 仍保留 AVX2/scalar：当前 permutation 查表和分支
  密集的拓扑尚没有通过验证、同时保持非 FMA/`floor` 语义的 8-lane 实现。
- Heightmap 扫描和压缩 palette 访问有意保留 AVX2/BMI2：扩大不规则扫描的
  向量宽度尚无已证实的安全收益，因此继续保留 scalar/NEON fallback。
- `-Dlattice.nativeCpu=avx2` 和 `scalar` 会限制分派，绝不会进入 AVX-512
  对象；`auto`/`avx512` 仍受 CPUID/XCR0 能力门控。

注意事项：

- 构建**有意**不使用 `-march=native`；生成的二进制文件在基线 ISA 级别（x86-64-v1 + SSE2 或 armv8-a）可移植，SIMD 特化通过运行时分派实现。
- **未**启用 `-ffast-math`——光照引擎浮点运算必须与 JVM 保持精确一致。
- 在非 MSVC 工具链上使用 `-fno-exceptions -fno-rtti` 构建库。`jni/jni_helper.hpp` 中的所有辅助函数均无异常。
