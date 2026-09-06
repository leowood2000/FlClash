---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '964b5a2c-78c3-4c11-aa66-a1bc054053ea'
  PropagateID: '964b5a2c-78c3-4c11-aa66-a1bc054053ea'
  ReservedCode1: 'db37f606-70bb-45b7-8459-eb339232ca95'
  ReservedCode2: 'db37f606-70bb-45b7-8459-eb339232ca95'
---

<div>

[**English**](README.md)

</div>

## FlClash

[![Downloads](https://img.shields.io/github/downloads/leowood2000/FlClash/total?style=flat-square&logo=github)](https://github.com/leowood2000/FlClash/releases/)[![Last Version](https://img.shields.io/github/release/leowood2000/FlClash/all.svg?style=flat-square)](https://github.com/leowood2000/FlClash/releases/)[![License](https://img.shields.io/github/license/leowood2000/FlClash?style=flat-square)](LICENSE)

[![Channel](https://img.shields.io/badge/Telegram-Channel-blue?style=flat-square&logo=telegram)](https://t.me/FlClash)

基于ClashMeta的多平台代理客户端，简单易用，开源无广告。

这是个人 fork 版本，针对 N1 盒子（Android 9）增加了 Android VPN 生命周期恢复修复。

### Fork 改动

- **VPN 生命周期恢复**：熄屏唤醒 / 网络变化时自动 restartTun（5s 去抖、5min 冷却、isRecovering 防循环）
- **Protect 计数器**：Go core 中按 tcp4/tcp6/udp4/udp6 分类统计，用于诊断
- **NetworkCallback 诊断日志**：记录 onAvailable/onLost/onLinkPropertiesChanged
- **route-exclude-address**：从 profile 配置到 VpnOptions 的完整传递链路
- **CI**：增加 armeabi-v7a 32 位构建（N1 兼容）
- **Clash.Meta 子模块**：fork 于 [leowood2000/Clash.Meta](https://github.com/leowood2000/Clash.Meta)

on Desktop:
<p style="text-align: center;">
    <img alt="desktop" src="snapshots/desktop.gif">
</p>

on Mobile:
<p style="text-align: center;">
    <img alt="mobile" src="snapshots/mobile.gif">
</p>

## Features

✈️ 多平台: Android, Windows, macOS and Linux

💻 自适应多个屏幕尺寸,多种颜色主题可供选择

💡 基本 Material You 设计, 类[Surfboard](https://github.com/getsurfboard/surfboard)用户界面

☁️ 支持通过WebDAV同步数据

✨ 支持一键导入订阅, 深色模式

## Use

### Linux

⚠️ 使用前请确保安装以下依赖

   ```bash
    sudo apt-get install libayatana-appindicator3-dev
    sudo apt-get install libkeybinder-3.0-dev
   ```

### Android

支持下列操作

   ```bash
    com.follow.clash.action.START
    
    com.follow.clash.action.STOP
    
    com.follow.clash.action.TOGGLE
   ```

## Download

<a href="https://github.com/leowood2000/FlClash/releases"><img alt="Get it on GitHub" src="snapshots/get-it-on-github.svg" width="200px"/></a>

### Homebrew

```bash
brew tap chen08209/tap
brew install --cask flclash
```

## Build

1. 更新 submodules
   ```bash
   git submodule update --init --recursive
   ```

2. 安装 `Flutter` 以及 `Golang` 环境

3. 构建应用

    - android

        1. 安装  `Android SDK` ,  `Android NDK`

        2. 设置 `ANDROID_NDK` 环境变量

        3. 运行构建脚本

           ```bash
           dart setup.dart android
           ```

    - windows

        1. 你需要一个windows客户端

        2. 安装 `GCC`，`Inno Setup`

        3. 运行构建脚本

           ```bash
           dart setup.dart windows
           ```

    - linux

        1. 你需要一个linux客户端

        2. 依赖会由 setup 脚本自动安装，也可以手动安装：
           ```bash
           sudo apt-get install -y libayatana-appindicator3-dev libkeybinder-3.0-dev
           ```

        3. 运行构建脚本

           ```bash
           dart setup.dart linux
           ```

    - macOS

        1. 你需要一个macOS客户端

        2. 运行构建脚本

           ```bash
           dart setup.dart macos
           ```

## Star

支持开发者的最简单方式是点击页面顶部的星标（⭐）。

<p style="text-align: center;">
    <a href="https://api.star-history.com/svg?repos=leowood2000/FlClash&Date">
        <img alt="start" width=50% src="https://api.star-history.com/svg?repos=leowood2000/FlClash&Date"/>
    </a>
</p>

> AI生成