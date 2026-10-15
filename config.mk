#
# SPDX-License-Identifier: Apache-2.0
#

# App updater: installs the bundled apps on first boot and keeps them updated
PRODUCT_PACKAGES += EvoXApps

# Bundled APKs, downloaded by scripts/fetch-apks.py before the build
-include vendor/evox-apps/prebuilt/products.mk

# Built-in ad blocking: AdAway writing a hosts file the ROM mounts over /system/etc/hosts
PRODUCT_PACKAGES += AdAway
PRODUCT_SYSTEM_EXT_PROPERTIES += ro.adaway.system_hosts=1
