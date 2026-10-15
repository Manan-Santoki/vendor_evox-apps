#
# SPDX-License-Identifier: Apache-2.0
#

# App updater: installs the bundled apps on first boot and keeps them updated
PRODUCT_PACKAGES += EvoXApps

# Bundled APKs, downloaded by scripts/fetch-apks.py before the build
-include vendor/evox-apps/prebuilt/products.mk
