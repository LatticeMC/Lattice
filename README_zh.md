<div align="center">

# Lattice

[![Build](https://github.com/LatticeMC/Lattice/actions/workflows/build.yml/badge.svg)](https://github.com/LatticeMC/Lattice/actions/workflows/build.yml)
[![Native CI](https://github.com/LatticeMC/Lattice/actions/workflows/native.yml/badge.svg)](https://github.com/LatticeMC/Lattice/actions/workflows/native.yml)
[![GitHub release](https://img.shields.io/github/v/release/LatticeMC/Lattice?include_prereleases)](https://github.com/LatticeMC/Lattice/releases)

基于 Purpur 构建的高性能 Minecraft 服务端分支，为经过筛选的服务端热点提供原生 C++ 加速。

[English](README.md) | **中文**

</div>

> [!WARNING]
> Lattice 仍在持续开发。将现有服务端迁移到 Lattice 前，请备份世界与配置，并先在测试环境验证插件兼容性。

## 特性

- 基于 [Purpur](https://purpurmc.org/)，兼容 Paper 与 Purpur 插件生态。
- 为部分寻路、实体、世界生成、压缩与数据处理热点提供原生 C++ 实现。
- 在受支持的 x86-64 与 AArch64 处理器上运行时分派 SIMD，不要求使用 `-march=native`。
- JNI 边界明确；原生库不可用或请求不受支持时回退到 Java 实现。
- 提供验证模式与 parity 测试，用于比较加速路径和 Java 参考实现。
- 使用 Paperweight 源码补丁与 feature patch，便于审查上游变更。

## 下载

开发构建由 [GitHub Actions](https://github.com/LatticeMC/Lattice/actions/workflows/build.yml) 发布；带版本标签的构建可从 [GitHub Releases](https://github.com/LatticeMC/Lattice/releases) 获取。

请只从项目控制的渠道下载 Lattice。第三方构建可能包含不同的 patch、原生库或许可证条款。

## 文档

- 通过 [GitHub Issues](https://github.com/LatticeMC/Lattice/issues) 报告缺陷与兼容性问题。
- Paper 配置和管理方式参见 [Paper 文档](https://docs.papermc.io/paper/)。
- Purpur 专有配置参见 [Purpur 文档](https://purpurmc.org/docs/)。

## 构建

环境要求：

- Java 21
- Git
- CMake 3.20 或更新版本
- Ninja
- C++20 编译器

构建已应用 patch 的服务端与 Paperclip JAR：

```bash
./gradlew applyAllPatches
./gradlew :lattice-server:createMojmapPaperclipJar
```

Windows 使用 `gradlew.bat`。构建默认在 `C:/Program Files/llvm-mingw` 查找 LLVM-MinGW；需要时可通过 `-PlatticeLlvmMingwHome=<路径>` 覆盖。

<details>
<summary>独立构建并测试原生库</summary>

```bash
cmake -S lattice-native -B lattice-native/build -DCMAKE_BUILD_TYPE=Release -DLATTICE_BUILD_TESTS=ON
cmake --build lattice-native/build --config Release --parallel
ctest --test-dir lattice-native/build --output-on-failure
```

主要 CMake 选项：

| 选项 | 默认值 | 用途 |
| --- | --- | --- |
| `LATTICE_ENABLE_SIMD` | `ON` | 构建运行时分派的 SIMD 实现 |
| `LATTICE_ENABLE_LTO` | `ON` | 启用链接时优化 |
| `LATTICE_BUILD_TESTS` | `OFF` | 构建原生测试 |
| `LATTICE_WARNINGS_AS_ERRORS` | `OFF` | 将原生编译器警告视为错误 |

</details>

## 原生加速

原生库通过 JNI 加载为 `liblattice.so`、`lattice.dll` 或 `liblattice.dylib`。所有加速操作都保留明确的 Java fallback。`-Dlattice.verify=true` 会为受支持路径执行参考实现比对，适合测试，不适合作为生产性能基准。

目前的加速工作包括：

- 寻路与 PathType 分类；
- 实体可见性、AABB 查询与碰撞扫描；
- 紧凑存储、NBT、压缩与高度图；
- 密度函数、地形噪声、矿脉与材质规则；
- 光照传播与部分 AI 目标采样器。

加速路径是否可用、是否有收益，取决于请求形态、CPU、世界状态与运行时配置。当某类请求使用 Java 更快时，不会仅因为存在 native 实现就强制进入 native。

目标采样器默认通过共享 Java 门禁；只有在目标 CPU 上完成测量后才设置
`-Dlattice.nativeTargetSampler.minWork=N`。门禁工作量为
`candidateCount * max(1, obstacleCount)`。

## 私有 Native PGO 教程

<details>
<summary>展开私有 PGO 构建与训练教程</summary>

正常 release 以及未指定 `LATTICE_PGO_MODE` 时仍是仅 LTO 构建。Native PGO 是私有且
必须显式启用的构建流程：它不会改变公开 release，也不会让 PGO 产物通过正常 Loader
公开使用。

以下正式 PoC 示例面向 Linux x86_64。请安装包含 `llvm-profdata` 的 Clang 工具链，以及
CMake、Ninja 和 JDK。两个 PGO 阶段都要求 x86_64 上启用 LTO 的单配置 Release Clang 构建。

1. 从仓库根目录配置并构建带插桩的私有库：

   ```bash
   cmake -S lattice-native -B build/native-pgo-generate -G Ninja \
     -DCMAKE_BUILD_TYPE=Release \
     -DCMAKE_C_COMPILER=clang \
     -DCMAKE_CXX_COMPILER=clang++ \
     -DLATTICE_ENABLE_LTO=ON \
     -DLATTICE_PGO_MODE=GENERATE
   cmake --build build/native-pgo-generate
   ```

2. 每轮训练前都清空 raw profile 目录。使用生成库正常启动服务器，运行具有代表性的正常
   工作负载；随后正常停服，确保所有 profile 数据都已写入。

   ```bash
   rm -rf /absolute/path/to/pgo/raw
   mkdir -p /absolute/path/to/pgo/raw
   export LLVM_PROFILE_FILE='/absolute/path/to/pgo/raw/lattice-%m-%p.profraw'
   java '-Dlattice.native.path=/absolute/path/to/build/native-pgo-generate/liblattice-pgo-generate.so' \
     -jar /absolute/path/to/lattice-server.jar nogui
   ```

3. 正常停服后，合并收集到的 raw profile：

   ```bash
   llvm-profdata merge -o /absolute/path/to/pgo/lattice.profdata \
     /absolute/path/to/pgo/raw/*.profraw
   ```

4. 单独构建严格的 profile-use 库，再用它的绝对库路径启动服务器：

   ```bash
   cmake -S lattice-native -B build/native-pgo-use -G Ninja \
     -DCMAKE_BUILD_TYPE=Release \
     -DCMAKE_C_COMPILER=clang \
     -DCMAKE_CXX_COMPILER=clang++ \
     -DLATTICE_ENABLE_LTO=ON \
     -DLATTICE_PGO_MODE=USE \
     -DLATTICE_PGO_PROFILE=/absolute/path/to/pgo/lattice.profdata \
     -DLATTICE_PGO_STRICT=ON
   cmake --build build/native-pgo-use
   java '-Dlattice.native.path=/absolute/path/to/build/native-pgo-use/liblattice-pgo-use.so' \
     -jar /absolute/path/to/lattice-server.jar nogui
   ```

不得跨源码版本、编译器/工具链版本、操作系统或架构复用 `.profdata` 文件。如果 profile
合并或严格 `USE` 构建失败，请丢弃本轮训练结果，并在匹配的环境中重新训练。

Windows x86_64 LLVM-MinGW 可运行下列已验证的本地 smoke 检查：

```powershell
pwsh -File .\scripts\native-pgo\Invoke-NativePgoSmoke.ps1 `
  -LlvmMingwRoot 'C:\llvm-mingw' `
  -JdkRoot 'C:\Program Files\Zulu\zulu-25'
```

它不是具有代表性的训练，不能替代 Linux PoC。在 Windows 上应使用对应 DLL 的绝对路径，
例如 `lattice-pgo-generate.dll` 和 `lattice-pgo-use.dll`，而不是上面的 Linux `.so` 路径。

</details>

## 参与贡献

Lattice 使用 Paperweight patch 工作流。修改上游源码前，请先阅读 [Purpur 贡献指南](https://github.com/PurpurMC/Purpur/blob/HEAD/CONTRIBUTING.md)。引入或改编优化时，必须保留原作者、来源与许可证署名。

## 许可证

Lattice 原创代码以及派生的服务端与 API 发行物遵循 GNU GPL v3（GPL-3.0-only）。文件头明确声明其他上游许可证的引入文件或 patch 继续遵循其原许可证并保留署名，第三方依赖也不因集成而改变许可证。

详见 [LICENSE](LICENSE)、[GPL-3.0](licenses/GPL.md) 与 [LGPL-3.0](licenses/LGPL-3.0.txt)。

## 致谢

Lattice 建立在 Minecraft 服务端与性能优化社区的工作之上，包括：

- [Paper](https://papermc.io/) 与 [Purpur](https://purpurmc.org/)
- [Leaf](https://github.com/Winds-Studio/Leaf)
- [Luminol](https://github.com/LuminolMC/Luminol)
- [Moonrise](https://github.com/Tuinity/Moonrise)
- [libdeflate](https://github.com/ebiggers/libdeflate)

具体引入 patch 的原作者与来源以 patch 头中的署名为准。

## 特别感谢

- [YourKit](https://www.yourkit.com/) 为开源项目提供性能分析工具，帮助定位 Java 与 native 性能问题。
- [IntelliJ IDEA](https://www.jetbrains.com/idea/) 为 Lattice 的 Java 与 JVM 侧开发提供开发环境。
