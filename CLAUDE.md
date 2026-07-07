# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Vepegram

Форк Telegram для Android с интегрированным VPN-клиентом. Бренд: **Vepegram** / **VPN Naruzhu**.

## Проект

- Android-приложение на Java/Kotlin, сборка Gradle
- Базируется на [DrKLO/Telegram](https://github.com/DrKLO/Telegram) (TMessagesProj) — upstream ветки `master`
  подтягивается git-мёрджем: `upstream` remote = `https://github.com/DrKLO/Telegram.git`, ветка `master` — чистое зеркало upstream (fast-forward), форк-правки живут в `vepegram`
- Нативный сетевой слой: C++ (`TMessagesProj/jni/tgnet/`)
- VPN-модуль: Kotlin (`vpn/`) — SDK, tunnel (AmneziaWG/WireGuard), network (Ktor)
- applicationId: `click.vpgram.messenger`, базовый пакет кода: `org.telegram.messenger`
- Версия: 12.5.1 (6537), compileSdk 35, NDK 27.2.12479018, Gradle 8.6.1, JDK 17
- Docker-сборка: `Dockerfile` в корне (базовый образ: gradle:8.7.0-jdk17)

## Сборка

```bash
# Debug APK
./gradlew :TMessagesProj_App:assembleAfatDebug

# Release APK
./gradlew :TMessagesProj_App:assembleAfatRelease

# Release App Bundle
./gradlew :TMessagesProj_App:bundleBundleAfatRelease

# Docker-сборка (полная среда Android SDK/NDK)
docker build -t vepegram:latest .
docker run -v $(pwd):/home/source vepegram:latest

# Тесты (инструментальные, нужно подключённое устройство/эмулятор)
./gradlew :TMessagesProj_AppTests:connectedAndroidTest

# Только VPN-модуль
./gradlew :vpn:sdk:build
```

Артефакты после Docker-сборки копируются в `TMessagesProj/build/outputs/`.

### Выгрузка в Firebase App Distribution

Плагин `com.google.firebase.appdistribution` подключён для варианта `afat release`
(App ID `1:586938236076:android:746337b238b6b55f257309`, проект `vepegram`).

```bash
# Собрать и выгрузить релизный APK тестерам
FIREBASE_SERVICE_CREDENTIALS_FILE=/path/to/service-account.json \
FIREBASE_APP_DISTRIBUTION_GROUPS=qa \
./gradlew :TMessagesProj_App:assembleAfatRelease :TMessagesProj_App:appDistributionUploadAfatRelease
```

Переменные окружения (или ключи в `local.properties`, gitignored):
- `FIREBASE_SERVICE_CREDENTIALS_FILE` — путь к JSON-ключу сервис-аккаунта Firebase.
  Читается сначала из `local.properties`, затем из env. Роль в IAM: **Firebase App Distribution Admin**.
- `FIREBASE_APP_DISTRIBUTION_GROUPS` — группы тестеров через запятую (по умолчанию `qa`).

Release notes не публикуются (поле пустое).

Конфиг — блок `firebaseAppDistribution` в `buildTypes.release` (`TMessagesProj_App/build.gradle`).

**Подпись release-сборки** (иначе APK неподписан и App Distribution его не примет).
Значения берутся сначала из `local.properties` в корне (gitignored), иначе из env — для CI:
- локально: добавить в `local.properties` строки
  `RELEASE_STORE_FILE=/абсолютный/путь/к/ключу.jks`, `RELEASE_STORE_PASSWORD=...`,
  `RELEASE_KEY_ALIAS=...`, `RELEASE_KEY_PASSWORD=...`;
- CI: env-переменные `RELEASE_KEYSTORE_PATH` / `RELEASE_STORE_PASSWORD` /
  `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`.

Держите один и тот же ключ между сборками, иначе обновление у тестеров упадёт
с `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Структура VPN-модуля

`vpn/` — composite Gradle build (включён через `includeBuild('vpn')` в `settings.gradle`).

```
vpn/
├── sdk/       — VpnSDK (синглтон, точка входа): setup(), toggleConnection(), startProxy(), getTunnelState()
├── tunnel/    — WireGuard через AmneziaWG (com.zaneschepke:amneziawg-android v2.1.0)
├── proxy/     — xray-core (VLESS+XTLS) как локальный SOCKS5 127.0.0.1:17808. См. vpn/proxy/README.md
├── network/   — HTTP API (Ktor 2.3.13 + OkHttp), конфиг с fallback (GitHub → GCS → Yandex Cloud)
├── base/      — MVVM-фреймворк (VpnViewModel, VpnUiState, VpnEvent, VpnnNavigator)
└── utils/
```

**VpnSDK (основные методы):**
- `setup(context)` — инициализация при старте приложения
- `updateConfig()` — фоновое обновление конфига (вызывается при выходе на передний план)
- `toggleConnection()` — подключить/отключить AWG VPN-туннель
- `getTunnelState()` — возвращает `VpnTunnelState`: UP / DOWN / CONNECTING
- `startProxy()` / `isProxyRunning()` / `getProxySocksHost()` / `getProxySocksPort()` — локальный xray SOCKS5-прокси для MTProto и звонков
- `fetchAppUpdate()` — проверить доступность обновления приложения

API: `https://api.vpnnaruzhu.online/client-api/v1/anonymous-awg-key`

**Интеграция с Telegram:**
- `ApplicationLoader.java` — `VpnSDK.setup()` + `updateConfig()` при старте, `VpnSDK.startProxy()` синхронно + запись proxy-настроек в `mainconfig` SharedPrefs до инициализации `ConnectionsManager`
- `VoIPService.java` — при `initiateActualEncryptedCall()` подставляет xray SOCKS5 в `Instance.Proxy` для `tgcalls`
- `VpnConnectionService.java` — foreground service (подписывается на состояние, останавливается при VPN DOWN)
- `VpnConnectionHelper.java` — запрос разрешения VPN (REQUEST_CODE_VPN_PERMISSION=100) и уведомлений (101)
- `DialogsActivity.java` — иконка статуса VPN в ActionBar
- `LoginActivity.java` — авто-подключение на экране логина

## Xray SOCKS5-прокси для MTProto и звонков

Весь трафик Telegram (сообщения и звонки) может роутиться через локальный xray (VLESS+XTLS) на `127.0.0.1:17808` **без VpnService**, параллельно с AWG-туннелем или вместо него.

- MTProto: стандартный SOCKS5-клиент `ConnectionsManager`, prefs пишет `ApplicationLoader`.
- Звонки: собственный `AsyncSocksProxyUdpSocket` в `TMessagesProj/jni/voip/webrtc/rtc_base/socket_adapters.*` реализует SOCKS5 UDP ASSOCIATE (RFC 1928 §7). `WrappedBasicPacketSocketFactory::CreateUdpSocket` в `TMessagesProj/jni/voip/tgcalls/v2/NativeNetworkingImpl.cpp` подменяет обычный UDP-сокет обёрткой когда proxy задан.

Полное описание архитектуры: [`vpn/proxy/README.md`](vpn/proxy/README.md).

⚠️ **VLESS-ключ сейчас захардкожен** в `vpn/proxy/src/main/kotlin/vpn/proxy/XrayConfig.kt` — сознательно для экспериментов. В production нужен отдельный API-эндпоинт выдачи VLESS-конфига (по аналогии с AWG-ключом из `vpn/network/`).

`vpn/proxy/libs/libXray.aar` хранится через git LFS (95 МБ), при клонировании нужен `git lfs install && git lfs pull`. Пересобрать AAR через `make aar` (корневой Makefile, нужен Go + gomobile).

## Структура Gradle-модулей

```
settings.gradle:
  :TMessagesProj          — библиотека (ядро Telegram)
  :TMessagesProj_App      — основное приложение (зависит от TMessagesProj + vpn:sdk)
  :TMessagesProj_AppTests — инструментальные тесты (minSdk 26, flavor afat)
  includeBuild('vpn')     — composite build VPN-модуля
```

Сборочный flavor `afat` — arm64-v8a + armeabi-v7a.

## Инструменты (`tools/`)

- `tools/tg-config/get_config.py` — получает `help.getConfig` из Telegram API через telethon, выводит список DC и пишет `config.json`. Требует `TG_API_ID` и `TG_API_HASH` в окружении.
- `tools/apkdiff.py` — сравнение содержимого APK
- `tools/apkfrombundle.py` — извлечение APK из App Bundle

## PR #1 — Vepegram (`vepegram` ← `master`)

https://github.com/ulta-plus/vepegram/pull/1

Основной PR с полной кастомизацией Telegram:
- VPN SDK интеграция (tunnel, network, подключение)
- Авто-подключение VPN на экране логина
- Ребрендинг: иконки, цвета, строки (Vepegram)
- UI/сборка фиксы
- Копирование debug-информации в настройках

## PR #2 — Force IPv4-only (`fix/force-ipv4-only` ← `vepegram`)

https://github.com/ulta-plus/vepegram/pull/2

Патч для предотвращения утечки трафика мимо VPN через IPv6.

**Проблема:** при `AllowedIPs = 0.0.0.0/0` (только IPv4) чаты работают, а файлы не загружаются — клиент выбирает IPv6-эндпоинты для media-соединений, трафик идёт мимо VPN напрямую через провайдера.

**Изменения:**
- `ConnectionsManager.cpp` — `getIpStratagy()` всегда возвращает `USE_IPV4_ONLY`
- `ConnectionsManager.cpp` — `setIpStrategy()` заглушен (блокирует `force_try_ipv6` с сервера)
- `Connection.cpp` — убран fallback на `USE_IPV4_IPV6_RANDOM` при reconnect
- `VoIPService.java` — IPv6 обнулён в VoIP-эндпоинтах
