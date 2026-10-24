MAA-Checker Desktop
===================

这是自带应用运行时的 JavaFX 桌面版。不需要安装 Maven、JDK，也不需要单独
配置 Java。另有不包含 JRE、要求本机已有 Java 21+ 的单文件轻量版。

启动
----
双击 MAA-Checker.exe。

桌面数据目录
------------
%LOCALAPPDATA%\MAA-Checker

日志、配置、历史索引和事务状态位于该目录，不在安装目录。
加载器图标按需从对应项目的公开地址获取，缓存于 cache\loader-icons-v1；
离线或获取失败时，实例卡片显示 Neo、For、Fab 等缩写。
替换程序目录不会删除这个数据目录；如需彻底清除，请先确认不再需要恢复资料，
再由用户手动删除。

与 Minecraft Java 的关系
------------------------
打包的 Java 21 运行时只运行 MAA-Checker。检验不同 Minecraft 版本时，工具仍会
读取实例版本 JSON 并选择该实例需要的 Java；不会因为桌面程序自带 Java 21 就把
所有整合包都强制到 Java 21。

当前能力
--------
- 实例扫描、启动检查、日志诊断
- 分类策略、多轮修复、轮次记录与还原
- 存储盘点、引用安全回收、实例移动后的恢复资料重新关联
- 兼容 CLI 的版本说明见项目 README / CLI-COMPATIBILITY.md

安全边界
--------
- 自动修复只处理日志和静态证据确认的问题。
- 文件动作有前后指纹、事务记录和恢复凭据。
- 世界、普通配置、账号资料、资源文件和共享 libraries 不在轮前快照内。
- 关闭任务中的窗口会先停止所属进程并等待资源释放。

许可证
------
MAA-Checker 使用 MIT License。JavaFX、Jackson、图标来源等第三方说明见
LICENSE 与 THIRD-PARTY-NOTICES.txt。
