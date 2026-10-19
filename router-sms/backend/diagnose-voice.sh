#!/bin/sh
# Read-only: no dial, answer, AT commands, reset, port opens or SIM changes.
set -u
echo '=== ModemManager Voice interface (read only) ==='
mmcli -m 0 --voice-list-calls --output-json 2>&1
echo '=== Kernel sound cards ==='
if [ -r /proc/asound/cards ]; then cat /proc/asound/cards; else echo 'No /proc/asound/cards'; fi
echo '=== Kernel PCM devices ==='
if [ -r /proc/asound/pcm ]; then cat /proc/asound/pcm; else echo 'No /proc/asound/pcm'; fi
echo '=== USB interface driver links ==='
ls -l /sys/bus/usb/drivers/snd-usb-audio/ 2>/dev/null || true
echo 'No sound card does not exclude proprietary PCM/UART audio. A sound card does not prove it belongs to the modem.'
