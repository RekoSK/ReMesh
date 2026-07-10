#!/usr/bin/env bash
#
# Same commands as build.sh, but with no git dependency and no required
# environment variables, so it works in a plain (non-repo) checkout.
#
#   FIRMWARE_VERSION  defaults to the version declared in the examples, rather
#                     than being mandatory
#   COMMIT_HASH       appended only when git is available

set -e

global_usage() {
  cat - <<EOF
Usage:
sh build2.sh <command> [target]

Commands:
  help|usage|-h|--help: Shows this message.
  list|-l: List firmwares available to build.
  build-firmware <target> [...]: Build the firmware for the given build target(s).
  build-firmwares: Build all firmwares for all targets.
  build-matching-firmwares <build-match-spec>: Build all firmwares for build targets containing the string given for <build-match-spec>.
  build-companion-firmwares: Build all companion firmwares for all build targets.
  build-repeater-firmwares: Build all repeater firmwares for all build targets.
  build-room-server-firmwares: Build all chat room server firmwares for all build targets.
  upload <target>: Build and flash the given build target over USB.

Examples:
Build firmware for the "ESP32C6_SX1276_SH1106_repeater" device target
$ sh build2.sh build-firmware ESP32C6_SX1276_SH1106_repeater

Build all firmwares for device targets containing the string "ESP32C6_SX1276"
$ sh build2.sh build-matching-firmwares ESP32C6_SX1276

Flash the companion BLE firmware to a connected board
$ sh build2.sh upload ESP32C6_SX1276_SH1106_companion_radio_ble

Environment Variables:
  FIRMWARE_VERSION=v1.2.3: Version string baked into the firmware. Defaults to the
                   version declared in examples/companion_radio/MyMesh.h.
  DISABLE_DEBUG=1: Disables all debug logging flags (MESH_DEBUG, MESH_PACKET_LOGGING, etc.)
                   If not set, debug flags from variant platformio.ini files are used.

Examples:
Build without debug logging:
$ export DISABLE_DEBUG=1
$ sh build2.sh build-firmware ESP32C6_SX1276_SH1106_repeater
EOF
}

# get a list of pio env names that start with "env:"
get_pio_envs() {
  pio project config | grep 'env:' | sed 's/env://'
}

# Catch cries for help before doing anything else.
case $1 in
  help|usage|-h|--help|"")
    global_usage
    exit 1
    ;;
  list|-l)
    get_pio_envs
    exit 0
    ;;
esac

# cache project config json for use in get_platform_for_env()
PIO_CONFIG_JSON=$(pio project config --json-output)

# $1 should be the string to find (case insensitive)
get_pio_envs_containing_string() {
  shopt -s nocasematch
  envs=($(get_pio_envs))
  for env in "${envs[@]}"; do
      if [[ "$env" == *${1}* ]]; then
        echo $env
      fi
  done
}

# $1 should be the string to find (case insensitive)
get_pio_envs_ending_with_string() {
  shopt -s nocasematch
  envs=($(get_pio_envs))
  for env in "${envs[@]}"; do
    if [[ "$env" == *${1} ]]; then
      echo $env
    fi
  done
}

# get platform flag for a given environment
# $1 should be the environment name
get_platform_for_env() {
  local env_name=$1
  echo "$PIO_CONFIG_JSON" | python3 -c "
import sys, json, re
data = json.load(sys.stdin)
for section, options in data:
    if section == 'env:$env_name':
        for key, value in options:
            if key == 'build_flags':
                for flag in value:
                    match = re.search(r'(ESP32_PLATFORM|NRF52_PLATFORM|STM32_PLATFORM|RP2040_PLATFORM)', flag)
                    if match:
                        print(match.group(1))
                        sys.exit(0)
"
}

# disable all debug logging flags if DISABLE_DEBUG=1 is set
disable_debug_flags() {
  if [ "$DISABLE_DEBUG" == "1" ]; then
    export PLATFORMIO_BUILD_FLAGS="${PLATFORMIO_BUILD_FLAGS} -UMESH_DEBUG -UBLE_DEBUG_LOGGING -UWIFI_DEBUG_LOGGING -UBRIDGE_DEBUG -UGPS_NMEA_DEBUG -UCORE_DEBUG_LEVEL -UESPNOW_DEBUG_LOGGING -UDEBUG_RP2040_WIRE -UDEBUG_RP2040_SPI -UDEBUG_RP2040_CORE -UDEBUG_RP2040_PORT -URADIOLIB_DEBUG_SPI -UCFG_DEBUG -URADIOLIB_DEBUG_BASIC -URADIOLIB_DEBUG_PROTOCOL"
  fi
}

# resolve a short commit hash, or empty string outside a git repo
get_commit_hash() {
  git rev-parse --short HEAD 2>/dev/null || echo ""
}

# The examples carry the current release as an #ifndef fallback, e.g.
#   #ifndef FIRMWARE_VERSION
#     #define FIRMWARE_VERSION "v1.16.0"
#   #endif
# Since we always pass -DFIRMWARE_VERSION we'd otherwise clobber that with a
# made-up number, and the mobile app gates features on the reported version.
# So read the real one straight out of the source of truth.
# NOTE: matches any quoted string, not just a leading-"v" semver, so rebranded
# versions like "Re16" are picked up too.
get_default_firmware_version() {
  local v
  v=$(sed -n 's/^[[:space:]]*#define[[:space:]]\{1,\}FIRMWARE_VERSION[[:space:]]\{1,\}"\([^"]*\)".*/\1/p' \
        examples/companion_radio/MyMesh.h 2>/dev/null | head -1)
  if [ -z "$v" ]; then
    echo "could not determine FIRMWARE_VERSION from examples/companion_radio/MyMesh.h" >&2
    echo "set it explicitly, e.g: export FIRMWARE_VERSION=Re16" >&2
    exit 1
  fi
  echo "$v"
}

# build firmware for the provided pio env in $1
build_firmware() {
  # get env platform for post build actions
  ENV_PLATFORM=($(get_platform_for_env $1))

  COMMIT_HASH=$(get_commit_hash)

  # set firmware build date
  FIRMWARE_BUILD_DATE=$(date '+%d-%b-%Y')

  # unlike build.sh, FIRMWARE_VERSION is optional and defaults to the version
  # the tree actually declares, so the reported version stays app-compatible
  FIRMWARE_VERSION="${FIRMWARE_VERSION:-$(get_default_firmware_version)}"

  # set firmware version string
  # with git:    v1.16.0-abcdef
  # without git: v1.16.0   (a bare "-" suffix would confuse version parsers)
  if [ -n "$COMMIT_HASH" ]; then
    FIRMWARE_VERSION_STRING="${FIRMWARE_VERSION}-${COMMIT_HASH}"
  else
    FIRMWARE_VERSION_STRING="${FIRMWARE_VERSION}"
  fi

  # craft filename
  # e.g: ESP32C6_SX1276_SH1106_repeater-v1.16.0
  FIRMWARE_FILENAME="$1-${FIRMWARE_VERSION_STRING}"

  # add firmware version info to end of existing platformio build flags in environment vars
  # NOTE: kept local to this function so repeated calls don't stack duplicate flags
  local PLATFORMIO_BUILD_FLAGS="${PLATFORMIO_BUILD_FLAGS} -DFIRMWARE_BUILD_DATE='\"${FIRMWARE_BUILD_DATE}\"' -DFIRMWARE_VERSION='\"${FIRMWARE_VERSION_STRING}\"'"
  export PLATFORMIO_BUILD_FLAGS

  # disable debug flags if requested
  disable_debug_flags

  # build firmware target
  pio run -e $1

  # build merge-bin for esp32 fresh install, copy .bins to out folder
  if [ "$ENV_PLATFORM" == "ESP32_PLATFORM" ]; then
    pio run -t mergebin -e $1
    cp .pio/build/$1/firmware.bin out/${FIRMWARE_FILENAME}.bin 2>/dev/null || true
    cp .pio/build/$1/firmware-merged.bin out/${FIRMWARE_FILENAME}-merged.bin 2>/dev/null || true
  fi

  # build .uf2 for nrf52 boards, copy .uf2 and .zip to out folder
  if [ "$ENV_PLATFORM" == "NRF52_PLATFORM" ]; then
    python3 bin/uf2conv/uf2conv.py .pio/build/$1/firmware.hex -c -o .pio/build/$1/firmware.uf2 -f 0xADA52840
    cp .pio/build/$1/firmware.uf2 out/${FIRMWARE_FILENAME}.uf2 2>/dev/null || true
    cp .pio/build/$1/firmware.zip out/${FIRMWARE_FILENAME}.zip 2>/dev/null || true
  fi

  # for stm32, copy .bin and .hex to out folder
  if [ "$ENV_PLATFORM" == "STM32_PLATFORM" ]; then
    cp .pio/build/$1/firmware.bin out/${FIRMWARE_FILENAME}.bin 2>/dev/null || true
    cp .pio/build/$1/firmware.hex out/${FIRMWARE_FILENAME}.hex 2>/dev/null || true
  fi

  # for rp2040, copy .bin and .uf2 to out folder
  if [ "$ENV_PLATFORM" == "RP2040_PLATFORM" ]; then
    cp .pio/build/$1/firmware.bin out/${FIRMWARE_FILENAME}.bin 2>/dev/null || true
    cp .pio/build/$1/firmware.uf2 out/${FIRMWARE_FILENAME}.uf2 2>/dev/null || true
  fi

  echo "Built ${FIRMWARE_FILENAME} -> out/"
}

# firmwares containing $1 will be built
build_all_firmwares_matching() {
  envs=($(get_pio_envs_containing_string "$1"))
  for env in "${envs[@]}"; do
      build_firmware $env
  done
}

# firmwares ending with $1 will be built
build_all_firmwares_by_suffix() {
  envs=($(get_pio_envs_ending_with_string "$1"))
  for env in "${envs[@]}"; do
    build_firmware $env
  done
}

build_repeater_firmwares() {
  build_all_firmwares_by_suffix "_repeater"
}

build_companion_firmwares() {
  build_all_firmwares_by_suffix "_companion_radio_usb"
  build_all_firmwares_by_suffix "_companion_radio_ble"
}

build_room_server_firmwares() {
  build_all_firmwares_by_suffix "_room_server"
}

build_firmwares() {
  build_companion_firmwares
  build_repeater_firmwares
  build_room_server_firmwares
}

# `upload` flashes a board, so it must not wipe out/ first
if [[ $1 == "upload" ]]; then
  if [ "$2" ]; then
    pio run -e $2 -t upload
    exit 0
  else
    echo "usage: $0 upload <target>"
    exit 1
  fi
fi

# clean build dir
mkdir -p out

# handle script args
if [[ $1 == "build-firmware" ]]; then
  TARGETS=${@:2}
  if [ "$TARGETS" ]; then
    for env in $TARGETS; do
      build_firmware $env
    done
  else
    echo "usage: $0 build-firmware <target>"
    exit 1
  fi
elif [[ $1 == "build-matching-firmwares" ]]; then
  if [ "$2" ]; then
     build_all_firmwares_matching $2
  else
     echo "usage: $0 build-matching-firmwares <build-match-spec>"
    exit 1
  fi
elif [[ $1 == "build-firmwares" ]]; then
  build_firmwares
elif [[ $1 == "build-companion-firmwares" ]]; then
  build_companion_firmwares
elif [[ $1 == "build-repeater-firmwares" ]]; then
  build_repeater_firmwares
elif [[ $1 == "build-room-server-firmwares" ]]; then
  build_room_server_firmwares
elif [[ $1 == "get-companion-firmwares-to-build" ]]; then
  get_pio_envs_ending_with_string "_companion_radio_usb"
  get_pio_envs_ending_with_string "_companion_radio_ble"
elif [[ $1 == "get-repeater-firmwares-to-build" ]]; then
  get_pio_envs_ending_with_string "_repeater"
elif [[ $1 == "get-room-server-firmwares-to-build" ]]; then
  get_pio_envs_ending_with_string "_room_server"
else
  echo "unknown command: $1"
  global_usage
  exit 1
fi
