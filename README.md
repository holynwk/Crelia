<div align="center">
    <img src="./folia.png" alt="Crelia" width="720">
    <h1>Crelia</h1>
    <p>在 Folia 区域多线程架构上整合 NeoForge 服务端能力的 Minecraft 服务端实验项目。</p>
</div>

> [!WARNING]
> Crelia 目前仍处于早期开发阶段。请不要直接用于正式服务器，也不要在没有备份的情况下加载重要存档。

## 项目简介

Crelia 基于 [Folia](https://github.com/PaperMC/Folia) 开发。

项目目标是在保留 Folia 区域多线程能力的同时，逐步接入 NeoForge 服务端所需的加载流程、事件系统和运行时组件，让服务端可以探索同时运行 Folia 插件与 NeoForge 模组的可能性。

## 当前状态

- 基于 Minecraft `26.1.2`
- 保留 Folia 的区域多线程架构
- 接入 FancyModLoader 和部分 NeoForge 服务端代码
- 提供可直接启动的独立 JAR 构建任务
- 模组兼容性仍在测试中，不保证所有 NeoForge 模组都能运行
- Folia 插件仍然需要适配区域多线程模型

## 构建

### 环境要求

- JDK 21
- Git

### 构建独立 JAR

```bash
git clone https://github.com/yuchendingUy/Crelia.git
cd Crelia
./gradlew applyAllPatches
./gradlew :folia-server:creliaStandaloneJar
```

构建完成后，项目上一级目录会生成：

```text
creliatest2.jar
```

### 开发环境启动

```bash
./gradlew applyAllPatches
./gradlew :folia-server:runServerFml
```

## 启动

把构建出的 JAR 放进单独的服务器目录，然后运行：

```bash
java -jar creliatest2.jar
```

第一次启动时会生成 `eula.txt`。阅读并同意 [Minecraft EULA](https://aka.ms/MinecraftEULA) 后，把文件中的内容改为：

```properties
eula=true
```

再次启动即可。

## 使用提醒

- 先备份世界存档和配置文件。
- 不要默认认为普通 Paper 插件可以在 Folia 上正常运行。
- 不要默认认为所有 NeoForge 模组都已经兼容。
- 遇到问题时，请附上完整日志、使用的模组列表和复现步骤。

## 目录说明

- `neoforge/`：当前接入的 NeoForge 服务端代码和资源。
- `build-data/crelia-launcher/`：独立 JAR 使用的启动器。
- `folia-server/`：Folia 服务端补丁和生成后的服务端源码。

## 上游项目

- [PaperMC/Folia](https://github.com/PaperMC/Folia)
- [PaperMC/Paper](https://github.com/PaperMC/Paper)
- [NeoForged/NeoForge](https://github.com/neoforged/NeoForge)

## 许可证

本项目基于多个上游开源项目开发。不同目录中的代码可能适用不同许可证和版权声明。

Folia 和 Paper 相关补丁请参阅 [`PATCHES-LICENSE`](./PATCHES-LICENSE)；NeoForge 相关代码请同时保留并遵守对应文件中的上游版权与许可证声明。
