# Biology AI Internal Notes

## 目标

`biologyAI` 的定位不是复刻原版 `Goal/Brain/Behavior` 全栈，而是在 Java 已完成感知和上下文收集后，将热点决策压缩为一层 native 可执行的启发式决策核。

当前设计目标：

- 在 native 不可用时，Java fallback 与 C++ 决策语义保持一致。
- 优先覆盖热点、生物直觉明显、可通过少量状态映射表达的行为。
- 不在 mixin 里重写整套原版 AI，只补足缺失输入和明显偏差。

## 当前结构

- native 决策核：`lattice-native/src/world/entity/biological_ai.cpp`
- JNI 桥：`lattice-native/jni/biological_ai.cpp`
- Java fallback：`tentacles-server/src/main/java/com/latticemc/lattice/nativelib/NativeBiologicalAi.java`
- profile 常量：`tentacles-server/src/main/java/com/latticemc/lattice/nativelib/BiologicalAiProfiles.java`
- 物种接线：`tentacles-server/src/main/java/com/latticemc/lattice/mixin/*Mixin.java`

## 已完成能力

### 核心层

- 补齐 `OCELOT` / `WOLF` / `CAT` / `FOX` native species 映射。
- Java fallback 与 C++ 决策树对齐：支持 `prey`、`curiosity`、species profile fallback。
- 新增 `flee_threat_strength`，让胆小动物在近距离强威胁下直接逃跑。
- `NativeBiologicalAi` 增加模块级 ABI guard；Java 新代码遇到旧 native 库时自动 fallback。

### 输入层

- `HerbivoreAiSupport` 支持同时传 `THREAT + FOOD`，不再二选一。
- 草食动物 threat 扫描支持缓存和错峰刷新。
- `PredatoryAnimalAiSupport` 新增 nearby prey 扫描和缓存。

### 物种侧状态映射

- `Rabbit`：普通兔子 nearby threat 扫描；`EVIL` 兔子走单独攻击 profile，并可 nearby 扫描 `Player/Wolf` 进入攻击态。
- `Ocelot`：补 nearby prey 扫描，覆盖鸡和陆地幼龟。
- `Wolf`：新增 wild wolf 物种支持，nearby prey 扫描覆盖原版 `Sheep/Rabbit/Fox`，tamed wolf 和已有非 prey 目标直接跳过，避免干扰宠物/愤怒目标逻辑。
- `Cat`：新增 untamed cat 物种支持，nearby prey 扫描覆盖原版 `Rabbit/land baby Turtle`，tamed cat 直接跳过避免干扰宠物/owner/睡觉礼物逻辑。
- `Fox`：新增保守捕食和避让支持，仅在非 `sleeping/sitting/crouching/pouncing/faceplanted/defending/inLove` 状态下扫描 `Chicken/Rabbit/land baby Turtle`；避让扫描覆盖未信任玩家、野狼、北极熊；暂不接管捡物/浆果/鱼类目标。
- `MushroomCow`：新增 cow-like 支持，复用 `COW` species/profile，覆盖 food temptation、threat、inLove busy 输入，不新增 ABI。
- `AbstractHorse`：新增 horse-family 支持，复用 `CAMEL` species/profile，覆盖 food temptation、threat、tamed/baby/eating/standing/inLove 输入，骑乘中跳过，不新增 ABI。
- `Chicken`：补 `Fox/Ocelot` threat 扫描。
- `Bee`：愤怒未蜇时可扫描并锁定附近应仇恨玩家。
- `Bee`：携蜜但没蜂巢时不会 idle，危险感上调；有蜂巢时更强约束回巢。
- `Frog`：补 nearby prey 扫描，可主动追击 `Frog.canEat(...)` 猎物。
- `Frog`：怀卵时不再被 food temptation 吸走，按 `IS_PREGNANT` memory 降能量并禁止诱食。
- `Sheep`：吃草时低能量且不诱食。
- `Pig/Cow/Sheep/Chicken/Rabbit/Camel/Goat/Llama/Armadillo`：`isInLove()` 时不再被 food temptation 打断，按低能量繁殖态处理。
- `Sniffer`：`digging` / `happy` / `sniffing` 时不再把 `canIdleSafely` 视为 true，避免 busy 态误进 REST。
- `Panda`：`sitting` / `eating` / `rolling` / `sneezing` / `onBack` / `scared` 时不再把 `canIdleSafely` 视为 true，避免 busy 态误进 REST。
- `Axolotl`：`playingDead` 时不再把 `canIdleSafely` 视为 true，避免装死态误进 REST。
- `Turtle`：怀蛋、回家、产蛋、水中状态映射更接近原版。
- `Turtle`：`travelPos` 自由远行态会禁止诱食并优先朝旅行目标前进。
- `Bee`：`angry/hasNectar/hasStung/hasSavedFlowerPos` 显式映射。
- `Axolotl`：装死、缺水、水中状态映射。
- `Frog`：支持 nearby prey 扫描，`PREY` 进入追击分支。
- `Panda`：`scared/sitting/eating/rolling/sneezing/onBack` 忙碌状态不再被诱食打断。
- `Sniffer`：按 `State` 映射 `DIGGING/RISING/HAPPY/SNIFFING/SEARCHING`。
- `Armadillo`：scared 时停住，不再追食物。
- `Goat`：准备冲撞时不被诱食打断；可根据 `RAM_TARGET` memory 反推出附近冲撞目标并进入攻击态。
- `Camel`：坐下/起身过渡/冲刺时不诱食，坐下时可休息。
- `Llama`：吐口水后和 caravan 中不被诱食打断；面对狼时可 nearby 扫描目标，并走中距离压制而不是贴脸近战。
- `Pig/Cow/MushroomCow/AbstractHorse/Sheep/Chicken/Rabbit/Ocelot/Wolf/Cat/Fox/Llama/Camel/Goat/Armadillo` 等陆生物种已移除 `isInWaterOrRain()` 的整段硬跳过。

## 当前验证状态

- native `ctest` 已通过到 `biological_ai` 修复后的阶段。
- Java 编译和测试尚需统一人工跑一遍。

推荐统一验证命令：

```bash
./gradlew :tentacles-server:compileJava :tentacles-server:compileTestJava

cmake -S lattice-native -B lattice-native/build-test -DLATTICE_BUILD_TESTS=ON
cmake --build lattice-native/build-test --target test_biological_ai
lattice-native/build-test/tests/test_biological_ai
```

Windows 下用 `gradlew.bat`。

## 后续扩展原则

- 优先补“输入质量”，而不是盲目扩充 native 决策分支。
- 单个 mixin 内能解决的状态，不要过早抽象成大框架。
- busy 状态必须优先保证“不被 food 打断”。
- nearby scan 必须带缓存和错峰刷新，避免每 tick 扫实体列表。
- 若物种存在明显特例，例如 `EVIL Rabbit`，优先用专用 profile，而不是破坏普通物种 profile。
- 若 Java 侧改了 profile ABI，必须同步更新 JNI 字段数和 `NativeBiologicalAi` 的 ABI guard。

## 建议下一批目标

- `Bee/Frog/Axolotl` 再进一步补 threat/prey scan 的细节节流。
- `Cow/Pig/Sheep` 看是否需要补 `inLove`、`panic`、`sheared` 以外的状态。
- 视实测结果决定是否给 `PredatoryAnimalAiSupport` 增加更适合两栖/飞行动物的 approach 逻辑。

## 当前静态审查结论

- `Species` / `Profile` 映射目前已覆盖所有已接入 mixin 的物种，新增 `WOLF/CAT/FOX` 后 ABI 版本升到 `5`。
- Java fallback 测试已覆盖大部分新增 profile 语义，包括：
  - timid flee
  - weak threat not masking food
  - ocelot prey
  - frog prey pursuit
  - killer rabbit pursuit
  - wolf pursuit
  - cat pursuit
  - fox pursuit
  - fox strong threat flee
  - camel/llama/sheep/bee/axolotl/sniffer busy-state rest or ignore-food
- 仍需依赖实机验证的部分主要集中在：
  - `PredatoryAnimalAiSupport` 对 `Frog` 这类特殊攻击动物是否会过度贴脸
  - `Bee` 在 persistent anger / universal anger 下的 nearby player scan 是否过激

## 实机验收关注点

- busy 状态是否被 food 打断。
- 水中/雨中是否还会整段 AI 失效。
- nearby prey/threat scan 是否过激或过迟。
- 控制台是否出现 `biological_ai` fallback 或 ABI mismatch 警告。
