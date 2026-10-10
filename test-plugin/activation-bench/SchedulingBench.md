# 逐 tick 测量协议

启用测试插件后使用 `schedulingbench start ticks [settleTicks]`。旧用法的等待期为 0；进行性能对照时，先记录协议，例如固定等待 100 tick、正式采样 3000 tick。不能观察慢 tick 后再调整截取位置。

1. 建立负载，确认人数、实体、区块和 bot 驻留。
2. 启动 JFR，再进行预先约定的负载预热；不要预热之后才首次启动 JFR。
3. 在正式窗口外执行 census，随后调用 `schedulingbench start 3000 100`。
4. 等待 complete，核对真实 tick 连续性、CPU 可用、配置实际启用状态和负载完整性。
5. 保留 CSV、JFR、命令、配置、JAR 摘要及每轮结果；同一构建串行进行独立 JVM 的 ABBA。

CSV 保留等待期和正式窗口的全部行，以 phase 区分。主 mean/median/p95/p99 使用正式窗口，all 前缀指标使用全部记录；gt15/25/50/100 是严格大于对应毫秒阈值的数量。p95/p99 使用 nearest-rank，不能用阈值计数取代分位数。

processCpuNs 为首个正式 TickStart 到最后一个正式 TickEnd 的进程累计 CPU 差，elapsedNs 跨相同 tick 编号；不累加每 tick 的 Windows CPU 时钟。NA 表示不可用或倒退，不能当作零。记录和写盘在 CPU 终点之后完成。

startNanos/endNanos 为插件事件回调时间，配合 epochMillisAnchor/nanoTimeAnchor 可近似关联 JFR。插件 TickStart 晚于内部 tick duration 起点，墙钟也有毫秒量化和配对误差，不能将它们当成完整 tick 的无误差边界。

报告逐轮结果和两组离散，分别评估 CPU 与 p99。合并所有 tick 的 p99 与各轮 p99 均值是不同统计量，应明确标注。每组只有两个 JVM 时，不由组间/组内极差比值直接推导显著性，也不根据事后截尾解除性能门禁。
