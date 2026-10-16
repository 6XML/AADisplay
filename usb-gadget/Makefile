# aad-usb — fake iPhone USB gadget daemon (standalone C, NOT in the Gradle graph)
#
# Targets:
#   make host      host binary (macOS/Linux) — syntax + unit-test development
#   make test      host build + run the plist/identity unit tests
#   make android   arm64-v8a Android binary (needs an NDK; auto-discovered)
#   make module    Magisk module zip (build/aadusb.zip) from the android binary
#   make clean
#
# NDK discovery order: $NDK_DIR, $NDK_HOME, <sdk>/ndk/*, gradle transforms cache.
# If none found, `make android` prints where to get one (do NOT re-download
# the Gradle wrapper / SDK — install the NDK into the standard SDK location).

BUILD    := build
SRC      := src/util.c src/plist.c src/identity.c src/configfs.c src/ffs.c src/usbmux.c src/lockdown.c src/bridge.c src/main.c
CFLAGS   := -O2 -g -Wall -Wextra -Wno-unused-parameter -fPIC

# ---------------------------------------------------------------- host ----
CC_HOST ?= cc
HOST_CC  := $(CC_HOST)
HOST_BIN := $(BUILD)/aad-usb

# ------------------------------------------------------------- android ----
SDK_DIR  ?= $(firstword \
    $(filter-out ,$(shell sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)) \
    $(filter-out ,$(ANDROID_SDK_ROOT) $(ANDROID_HOME) $(HOME)/Library/Android/sdk))

NDK_CANDIDATES := $(shell \
    for d in $(NDK_DIR) $(NDK_HOME) $(SDK_DIR)/ndk/* \
             $(HOME)/.gradle/caches/*/transforms-*/*/transformed/ndk-* ; do \
        [ -n "$$d" ] && [ -d "$$d" ] && echo "$$d"; done | sort -V | uniq)
NDK_DIR ?= $(lastword $(NDK_CANDIDATES))

UNAME_S := $(shell uname -s)
ifneq (,$(findstring Darwin,$(UNAME_S)))
NDK_PREBUILT := darwin-x86_64
else
NDK_PREBUILT := linux-x86_64
endif
TOOLCHAIN := $(NDK_DIR)/toolchains/llvm/prebuilt/$(NDK_PREBUILT)
CC_AND    := $(TOOLCHAIN)/bin/clang
CFLAGS_AND:= --target=aarch64-linux-android21 --sysroot=$(TOOLCHAIN)/sysroot
LDFLAGS_AND:= -llog -lutils

AND_BIN  := $(BUILD)/aad-usb-arm64

# --------------------------------------------------------------- objects --
H_OBJ   := $(patsubst src/%.c,$(BUILD)/host/obj/%.o,$(SRC))
A_OBJ   := $(patsubst src/%.c,$(BUILD)/android/obj/%.o,$(SRC))

.PHONY: host android test module module-bin clean
host: $(HOST_BIN)

ifeq ($(NDK_DIR),)
android:
	@echo "No Android NDK found (looked in: $$NDK_DIR, $$NDK_HOME, $(SDK_DIR)/ndk/*, gradle transforms cache)."
	@echo "Install one into the standard SDK location, e.g.:"
	@echo "  $(SDK_DIR)/cmdline-tools/.../bin/sdkmanager 'ndk;27.0.12077973'"
	@echo "or via Android Studio (Settings > Languages & Frameworks > NDK)."
	@echo "Then re-run: make android"
	@exit 1
else
android: $(AND_BIN)
	@test -x $(CC_AND) || { echo "NDK toolchain missing: $(CC_AND)"; exit 1; }
	@echo "android binary: $(AND_BIN)"
endif

$(BUILD)/host/obj/%.o: src/%.c src/aad.h | $(BUILD)/host/obj
	$(HOST_CC) $(CFLAGS) -c $< -o $@

$(HOST_BIN): $(H_OBJ)
	$(HOST_CC) $(H_OBJ) -o $@ -lpthread

$(BUILD)/android/obj/%.o: src/%.c src/aad.h | $(BUILD)/android/obj
	$(CC_AND) $(CFLAGS) $(CFLAGS_AND) -c $< -o $@

$(AND_BIN): $(A_OBJ)
	$(CC_AND) $(A_OBJ) -o $@ -lpthread $(LDFLAGS_AND)

$(BUILD)/host/obj $(BUILD)/android/obj:
	mkdir -p $@

# --------------------------------------------------------------- tests ----
$(BUILD)/test_plist: src/plist.c src/util.c src/identity.c src/aad.h test/test_plist.c | $(BUILD)
	$(HOST_CC) $(CFLAGS) -Isrc src/plist.c src/util.c src/identity.c test/test_plist.c -o $@ -lpthread

test: $(BUILD)/test_plist
	$(BUILD)/test_plist

# ------------------------------------------------------------ magisk -----
MODULE_SRC := magisk
MODULE_OUT := $(BUILD)/module
MOD_VER    := $(shell sed -n 's/^version=//p' $(MODULE_SRC)/module.prop)

module:
ifeq ($(NDK_DIR),)
	@echo "No Android NDK found — build the arm64 binary first (make android)."
	@exit 1
endif
	$(MAKE) module-bin

module-bin: $(AND_BIN)
	rm -rf $(MODULE_OUT)
	mkdir -p $(MODULE_OUT)/files/system/adb/aadusb
	cp $(AND_BIN) $(MODULE_OUT)/files/system/adb/aadusb/aad-usb
	chmod 755 $(MODULE_OUT)/files/system/adb/aadusb/aad-usb
	cp $(MODULE_SRC)/module.prop $(MODULE_SRC)/service.sh $(MODULE_SRC)/post-fs-data.sh $(MODULE_OUT)/
	chmod 755 $(MODULE_OUT)/service.sh $(MODULE_OUT)/post-fs-data.sh
	cd $(BUILD) && zip -qr aadusb-$(MOD_VER).zip module
	@echo "module zip: $(BUILD)/aadusb-$(MOD_VER).zip"

clean:
	rm -rf $(BUILD)

$(BUILD):
	mkdir -p $(BUILD)
