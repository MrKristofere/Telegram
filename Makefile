LIBXRAY_REPO   := https://github.com/xtls/libxray
LIBXRAY_DIR    := /tmp/libxray
LIBXRAY_AAR    := vpn/proxy/libs/libXray.aar
ANDROID_API    := 21
GONOSUMDB      := *

# Android SDK / NDK — подхватываем из системных профилей если не заданы снаружи.
# JAVA_HOME по умолчанию целится в Arch Linux; на других дистрибутивах задайте переменную вручную.
export ANDROID_HOME     ?= /opt/android-sdk
export ANDROID_NDK_HOME ?= $(ANDROID_HOME)/ndk/20.1.5948944
export JAVA_HOME        ?= /usr/lib/jvm/java-17-openjdk
export PATH             := $(JAVA_HOME)/bin:$(PATH):$(ANDROID_HOME)/cmdline-tools/latest/bin:$(ANDROID_HOME)/platform-tools:$(HOME)/go/bin

SDKMANAGER := $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager

APK_DEBUG      := TMessagesProj_App/build/outputs/apk/afat/debug/TMessagesProj_App-afat-debug.apk
APK_RELEASE    := TMessagesProj_App/build/outputs/apk/afat/release/TMessagesProj_App-afat-release.apk

.PHONY: all aar apk apk-debug apk-release clean check-go check-android

all: aar apk-debug

# ---- AAR (libxray) ----

aar: check-go $(LIBXRAY_AAR)

$(LIBXRAY_AAR): $(LIBXRAY_DIR)
	@echo "→ Building libXray.aar..."
	cd $(LIBXRAY_DIR) && \
		GONOSUMDB=$(GONOSUMDB) go get golang.org/x/mobile/bind 2>/dev/null || true
	gomobile init
	@test -d $(ANDROID_HOME)/platforms/android-$(ANDROID_API) || { \
		LATEST=$$(ls $(ANDROID_HOME)/platforms/ | sort -V | tail -1); \
		echo "→ Symlinking platforms/$$LATEST → android-$(ANDROID_API)"; \
		sudo ln -sf $(ANDROID_HOME)/platforms/$$LATEST $(ANDROID_HOME)/platforms/android-$(ANDROID_API); \
	}
	cd $(LIBXRAY_DIR) && \
		GONOSUMDB=$(GONOSUMDB) gomobile bind \
			-target android \
			-androidapi $(ANDROID_API) \
			-o $(CURDIR)/$(LIBXRAY_AAR) \
			.
	@echo "✓ $(LIBXRAY_AAR) ($$(du -sh $(LIBXRAY_AAR) | cut -f1))"

$(LIBXRAY_DIR):
	git clone --depth=1 $(LIBXRAY_REPO) $(LIBXRAY_DIR)

# ---- APK ----

apk: apk-debug

apk-debug: check-android $(LIBXRAY_AAR)
	@echo "→ Building debug APK..."
	./gradlew :TMessagesProj_App:assembleAfatDebug
	@echo "✓ $(APK_DEBUG)"

apk-release: check-android $(LIBXRAY_AAR)
	@echo "→ Building release APK..."
	./gradlew :TMessagesProj_App:assembleAfatRelease
	@echo "✓ $(APK_RELEASE)"

# ---- setup (один раз перед первой сборкой) ----

setup: check-android
	@echo "→ Accepting SDK licenses..."
	yes | $(SDKMANAGER) --licenses > /dev/null 2>&1 || true
	@echo "→ Installing platforms;android-35..."
	$(SDKMANAGER) "platforms;android-35"
	@echo "→ Installing ndk;20.1.5948944..."
	$(SDKMANAGER) "ndk;20.1.5948944"
	@# Убираем кривой симлинк android-21 (нужен был только для gomobile)
	@sudo rm -f $(ANDROID_HOME)/platforms/android-21
	@echo "✓ setup done"

# ---- checks ----

check-go:
	@command -v go >/dev/null || (echo "ERROR: go not found"; exit 1)
	@command -v gomobile >/dev/null || { \
		echo "→ Installing gomobile..."; \
		GONOSUMDB=$(GONOSUMDB) go install golang.org/x/mobile/cmd/gomobile@latest; \
	}

check-android:
	@command -v java >/dev/null || (echo "ERROR: java not found"; exit 1)
	@test -n "$(ANDROID_HOME)" || (echo "ERROR: ANDROID_HOME not set"; exit 1)

# ---- clean ----

clean:
	rm -f $(LIBXRAY_AAR) vpn/proxy/libs/libXray-sources.jar
	./gradlew clean
