$(call inherit-product, device/google/cuttlefish/vsoc_x86_64_only/phone/aosp_cf.mk)
$(call inherit-product, vendor/virtualcamerapro/build-integration/virtualcamera-pro.mk)
PRODUCT_NAME := vcam_cf_x86_64_phone
PRODUCT_MODEL := Virtual Camera Pro Cuttlefish reference
PRODUCT_PACKAGES += Camera2
# VirtualCameraProSystemTests is built explicitly and installed with adb -t.
