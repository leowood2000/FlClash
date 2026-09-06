---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '63efd61f-3bfb-4d80-8d27-32ee48d9021a'
  PropagateID: '63efd61f-3bfb-4d80-8d27-32ee48d9021a'
  ReservedCode1: '482b6ab9-6182-4a8c-9ffd-c8318bf4dd9b'
  ReservedCode2: '482b6ab9-6182-4a8c-9ffd-c8318bf4dd9b'
---

<div>

[**简体中文**](README_zh_CN.md)

</div>

## FlClash

[![Downloads](https://img.shields.io/github/downloads/leowood2000/FlClash/total?style=flat-square&logo=github)](https://github.com/leowood2000/FlClash/releases/)[![Last Version](https://img.shields.io/github/release/leowood2000/FlClash/all.svg?style=flat-square)](https://github.com/leowood2000/FlClash/releases/)[![License](https://img.shields.io/github/license/leowood2000/FlClash?style=flat-square)](LICENSE)

[![Channel](https://img.shields.io/badge/Telegram-Channel-blue?style=flat-square&logo=telegram)](https://t.me/FlClash)

A multi-platform proxy client based on ClashMeta, simple and easy to use, open-source and ad-free.

This is a personal fork with Android VPN lifecycle recovery fixes for N1 box (Android 9).

### Fork Changes

- **VPN lifecycle recovery**: auto restartTun on SCREEN_ON / network change (5s debounce, 5min cooldown, isRecovering anti-loop guard)
- **Protect socket counter**: tcp4/tcp6/udp4/udp6 atomic counters in Go core for diagnostics
- **NetworkCallback diagnostics**: log onAvailable/onLost/onLinkPropertiesChanged
- **route-exclude-address**: config pipeline from profile to VpnOptions (Android)
- **CI**: added armeabi-v7a 32-bit build for N1 compatibility
- **Clash.Meta submodule**: fork at [leowood2000/Clash.Meta](https://github.com/leowood2000/Clash.Meta)

on Desktop:
<p style="text-align: center;">
    <img alt="desktop" src="snapshots/desktop.gif">
</p>

on Mobile:
<p style="text-align: center;">
    <img alt="mobile" src="snapshots/mobile.gif">
</p>

## Features

✈️ Multi-platform: Android, Windows, macOS and Linux

💻 Adaptive multiple screen sizes, Multiple color themes available

💡 Based on Material You Design, [Surfboard](https://github.com/getsurfboard/surfboard)-like UI

☁️ Supports data sync via WebDAV

✨ Support subscription link, Dark mode

## Use

### Linux

⚠️ Make sure to install the following dependencies before using them

   ```bash
    sudo apt-get install libayatana-appindicator3-dev
    sudo apt-get install libkeybinder-3.0-dev
   ```

### Android

Support the following actions

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

1. Update submodules
   ```bash
   git submodule update --init --recursive
   ```

2. Install `Flutter` and `Golang` environment

3. Build Application

    - android

        1. Install `Android SDK`, `Android NDK`

        2. Set `ANDROID_NDK` environment variable

        3. Run build script

           ```bash
           dart setup.dart android
           ```

    - windows

        1. Requires a Windows client

        2. Install `GCC`, `Inno Setup`

        3. Run build script

           ```bash
           dart setup.dart windows
           ```

    - linux

        1. Requires a Linux client

        2. Dependencies are auto-installed by setup script, or manually:
           ```bash
           sudo apt-get install -y libayatana-appindicator3-dev libkeybinder-3.0-dev
           ```

        3. Run build script

           ```bash
           dart setup.dart linux
           ```

    - macOS

        1. Requires a macOS client

        2. Run build script

           ```bash
           dart setup.dart macos
           ```

## Star

The easiest way to support developers is to click on the star (⭐) at the top of the page.

<p style="text-align: center;">
    <a href="https://api.star-history.com/svg?repos=leowood2000/FlClash&Date">
        <img alt="start" width=50% src="https://api.star-history.com/svg?repos=leowood2000/FlClash&Date"/>
    </a>
</p>

> AI生成