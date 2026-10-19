include $(TOPDIR)/rules.mk

LUCI_TITLE:=Router SMS and experimental phone manager
LUCI_DEPENDS:=+router-sms-api +luci-base +rpcd
LUCI_PKGARCH:=all
PKG_VERSION:=0.3.1
PKG_RELEASE:=1
PKG_LICENSE:=MIT

include ../../luci.mk

# The accompanying Python builder also produces installable .ipk files without an SDK.
