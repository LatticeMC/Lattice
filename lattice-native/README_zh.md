# lattice-native

`lattice-native` 是 Lattice 的 C++20 原生组件。它通过 JNI 将服务器的性能敏感
操作提供给 Java，同时保持边界上对 Java 可见的行为。

## 能力范围

该库按以下服务器数据路径组织原生能力：

- I/O：zlib 压缩和 NBT 相关数据路径；适用处使用 libdeflate。
- 区块数据：压缩调色板和光照传播。
- 模拟查询：实体查询、碰撞、寻路和 AI 采样。
- 世界生成：噪声、密度计算、地表规则、矿石选择和 tick。

JNI 边界负责转换、生命周期和错误映射。原生实现保持已定义的 Java 侧结果与
失败语义；优化不会把游戏行为或数据格式契约变成另一种语义。

## 架构原则

- 原生代码使用 C++20，JNI 是 Java/原生集成的唯一边界。
- CPU 专用代码与可移植基线隔离，并在运行时选择，而不是针对构建机器编译。
- 不使用 `-march=native` 或 `-ffast-math`。基线保持可移植，浮点敏感路径保留
  所需语义。
- libdeflate 固定为 `v1.20`，经由 CMake `FetchContent` 获取并静态链接，不从
  系统安装中选择。
- `LATTICE_ENABLE_LTO` 控制 Release 构建的链接时优化。

## 构建与测试

需要 CMake 3.20 或更高版本、C++20 工具链（GCC 11+、Clang 13+ 或 MSVC
19.30+）以及提供 `jni.h` 的 JDK：

```bash
cmake -S lattice-native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native
```

构建输出在 Windows 上为 `lattice.dll`，在 macOS 上为 `liblattice.dylib`，在
Linux 和 FreeBSD 上为 `liblattice.so`。平台打包负责将该库放到 Java 加载器所需
的位置。

用以下命令启用原生测试集合：

```bash
cmake -S lattice-native -B build/native-test -DCMAKE_BUILD_TYPE=Release -DLATTICE_BUILD_TESTS=ON
cmake --build build/native-test
ctest --test-dir build/native-test --output-on-failure
```

测试集合包含 31 个 CTest 测试。对于适用的 Java/原生路径，
`-Dlattice.verify=true` 会启用相对 Java 参考实现的影子/一致性诊断；这并不表示
所有模块都具有统一的一致性判定器。

## CMake 选项

| 选项 | 默认值 | 含义 |
|---|---:|---|
| `LATTICE_ENABLE_SIMD` | `ON` | 构建运行时分派的 SIMD 特化。 |
| `LATTICE_ENABLE_LTO` | `ON` | 为 Release 构建请求链接时优化。 |
| `LATTICE_BUILD_TESTS` | `OFF` | 构建 CTest 测试集合和差异验证工具。 |
| `LATTICE_WARNINGS_AS_ERRORS` | `OFF` | 将编译器警告视为错误。 |

## 运行时分派与语义约束

分派器仅在检查 CPU 与操作系统的运行时支持后，才会在 scalar、SSE2、AVX2、BMI2、
AVX512 和 NEON 实现之间选择。可通过 `LATTICE_CPU_FORCE_SCALAR=1` 或
`LATTICE_CPU_DISABLE=avx512,bmi2` 约束选择，以辅助诊断。

特化实现必须保持可移植路径的可观察结果，包括位压缩数据约定、JNI 错误行为，
以及光照和世界生成代码使用的非 fast-math 浮点规则。运行时选择改变的是执行
策略，不改变 Java API 或序列化数据的语义。

## Native PGO 设计

已实现的私有 PGO 构建
闭环，以及仍待完成的训练和发布工作。

| 选项 | 默认值 | 含义 |
|---|---:|---|
| `LATTICE_PGO_MODE` | `OFF` | `OFF`、`GENERATE` 或 `USE`；取值必须大写，PGO 模式仅供私有构建。 |
| `LATTICE_PGO_PROFILE` | 空 | 仅 `USE` 必填；必须是非空的 `.profdata` 文件。 |
| `LATTICE_PGO_STRICT` | `OFF` | 仅可与 `USE` 一同使用；把 LLVM 的过期/未覆盖 instrumentation 诊断提升为错误。 |

`OFF` 是默认值，保持普通 `lattice` 的 LTO-only 构建。公开 `native-latest`、Gradle
和 Java Loader 仍为 LTO-only；它们不会发现或发布任何私有 PGO 输出。`GENERATE`
生成 `lattice-pgo-generate`，`USE` 生成 `lattice-pgo-use`。两者均要求单配置
Release、Clang、64 位 x86_64 和启用 LTO。Linux 是正式 PoC 目标；Windows x86_64
LLVM-MinGW 仅提供本地 experimental smoke 覆盖。PGO build tree 会拒绝
`cmake --install`。

私有 DLL smoke 加载可向既有 Loader override 传入绝对库路径，例如
`-Dlattice.native.path=C:\absolute\lattice-pgo-generate.dll`。这不会使 PGO
产物公开，也不会改变正常加载行为。
