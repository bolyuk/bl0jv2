#!/bin/sh
# Builds a disk image holding the OS (kernel programs in sbin/, commands in bin/)
# and boots it. The VM reads no host files: everything the OS runs comes from the
# image, which this script fills with --disk-put and the boot program reads.
#
#   aeon-os/aeon.sh [image] [boot|smp_boot]        (default: aeon.img, boot)
#   SCREEN=1 aeon-os/aeon.sh                        a text-mode display instead of the serial console
#
# JAR: the built CLI (mvn package), override with JAR=...
set -e
cd "$(dirname "$0")/.."
JAR=${JAR:-bl0jv2-cli/target/bl0jv2-vm-lib.jar}
IMG=${1:-aeon.img}
BOOT=${2:-boot}
CORES=1
mkdir -p "${SHARE:-share}"
[ "$BOOT" = smp_boot ] && CORES=4
exec java -jar "$JAR" -c -e -k -n "$CORES" --disk "$IMG" --disk-sectors 4096 \
  --disk-put aeon-os/bin:bin \
  --disk-put aeon-os/shell.bl0:sbin/shell.bl0c \
  --disk-put aeon-os/child_hello.bl0:sbin/child_hello.bl0c \
  --disk-put aeon-os/child_crash.bl0:sbin/child_crash.bl0c \
  ${SCREEN:+--display} --bridge-outbound --bridge-fs "${SHARE:-share}" \
  "aeon-os/$BOOT.bl0"
