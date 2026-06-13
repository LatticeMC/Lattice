# Lattice

[English](README.md) | [中文](README_zh.md)

基于 [Purpur](https://purpurmc.org) 构建的高性能 Minecraft 服务端分支，通过 JNI 引入原生 C++ 加速层，在保持与参考 Java 实现位精确兼容的前提下替换服务端热点路径。

## 环境要求

- Java 21（推荐使用 Temurin）
- Git
- CMake >= 3.20（仅在单独构建原生库时需要）

## 构建

### 完整服务端构建

```bash
./gradlew applyAllPatches
./gradlew build
```

此命令会应用所有上游补丁（Paper -> Purpur -> Lattice）并编译服务端 jar 包。

### 原生库（独立构建）

原生库位于 `lattice-native/` 目录下，可以独立编译：

```bash
cmake -S lattice-native -B lattice-native/build -DCMAKE_BUILD_TYPE=Release
cmake --build lattice-native/build --config Release --parallel
```

需要：C++20 工具链（GCC >= 11、Clang >= 13 或 MSVC 19.30+），以及 JDK（用于 JNI 头文件）。

CMake 选项：

| 选项 | 默认值 | 说明 |
|---|---|---|
| `LATTICE_ENABLE_SIMD` | `ON` | 运行时分派 SIMD 特化（AVX2、BMI2、NEON） |
| `LATTICE_ENABLE_LTO` | `ON` | 链接时优化 |
| `LATTICE_BUILD_TESTS` | `OFF` | 构建单元测试 |
| `LATTICE_WARNINGS_AS_ERRORS` | `OFF` | 将编译器警告视为错误 |

### 运行测试

```bash
# 服务端测试
./gradlew test

# 原生库测试（需要 -DLATTICE_BUILD_TESTS=ON）
cd lattice-native/build && ctest --output-on-failure
```

## 架构

Lattice 遵循标准的 Purpur 补丁系统。服务端修改以文件补丁的形式存储，而非完整源文件：

- `tentacles-api/paper-patches/` -- 在 Paper 之上的 API 新增
- `tentacles-server/paper-patches/` -- 在 Paper 之上的服务端修改
- `tentacles-server/purpur-patches/` -- 在 Purpur 之上的服务端修改

原生库以共享对象形式（`liblattice.so` / `lattice.dll` / `liblattice.dylib`）在启动时通过 JNI 加载。每个原生调用都受 `LatticeNative.isLoaded()` 保护，当原生库不可用时透明回退到 JDK 实现。在 `-Dlattice.verify=true` 模式下，每次原生调用都会被 JDK 参考实现并行执行，输出结果将被比对验证。

## 原生模块

`lattice-native` C++ 库加速了 16 个以上的 Minecraft 服务端系统：

| 模块 | 目标 |
|---|---|
| Zlib 编解码器 | 基于 libdeflate 的区块压缩 |
| NBT 解析器 | 二进制 NBT 反序列化 |
| 紧凑存储 | 位打包 `long[]` 操作（标量、BMI2、AVX2、NEON） |
| 光照等级传播器 | 基于 BFS 的光照等级传播 |
| 方块光照引擎 | 完整的方块光照引擎，通过 JNI 查询世界数据 |
| 高度图扫描 | 多段区块列高度图扫描器 |
| 随机刻筛选器 | 随机刻候选掩码筛选 |
| 生物 AI | 15 种动物物种的决策层 |
| 接近/逃跑/家/水目标采样器 | 本地导航评估 |
| 生成筛选器 | 实体生成资格判定 |
| 实体可见性 | O(N x M) 距离扫描 |
| AABB 查询 | O(Q x E) AABB 相交扫描 |
| 碰撞扫描 | 实体移动的扫描式 AABB 钳位 |
| 密度函数 | 批量网格填充与求值器 |
| 区块噪声采样器 | NoiseRouter 包装外观 |
| 矿脉采样器 | 基于 Xoroshiro128++ 的逐方块矿脉判定 |
| 材质规则 | 地表构建器规则树 |
| 插值噪声 | 旧版 1.16 风格混合噪声 |
| Perlin / 八度 / 双重 / Simplex 噪声 | 噪声生成基础原语 |

所有实体和噪声模块均包含 SIMD 特化（x86-64 使用 AVX2，AArch64 使用 NEON），并通过运行时 CPU 特性检测进行分派。构建基线为 x86-64-v1 + SSE2 或 armv8-a；不使用 `-march=native`，因此二进制文件在同架构的所有 CPU 上均可移植。

## 平台支持

原生库在 CI 中于以下平台构建和测试：

| 平台 | 编译器 | 架构 |
|---|---|---|
| Ubuntu (latest) | GCC | x86-64 |
| Ubuntu (latest) | Clang | x86-64 |
| Windows (latest) | MSVC | x86-64 |
| macOS 14 (ARM) | Clang | AArch64 |

Java 服务端可在任何拥有 Java 21 运行时的平台上构建和运行。

## 项目结构

```
Tentacles/
├── lattice-native/          C++ 原生加速库（CMake，C++20）
│   ├── jni/                 JNI 桥接层
│   ├── src/                 核心实现（io、world、core）
│   ├── include/lattice/     公共头文件
│   └── tests/               单元测试（doctest）
├── tentacles-api/           服务端 API 模块
│   └── paper-patches/       在 Paper 之上的 API 补丁
├── tentacles-server/        服务端实现模块
│   ├── paper-patches/       在 Paper 之上的服务端补丁
│   ├── purpur-patches/      在 Purpur 之上的服务端补丁
│   └── src/                 Java 源码（bootstrap、nativelib、mixin）
├── scripts/                 构建与上游同步脚本
├── build.gradle.kts         根构建文件（paperweight patcher）
├── settings.gradle.kts      项目设置
└── gradle.properties        版本与构建配置
```

## 参与贡献

参见 [Purpur 的贡献指南](https://github.com/PurpurMC/Purpur/blob/HEAD/CONTRIBUTING.md) 了解补丁工作流。

## 许可证

[MIT](LICENSE)
