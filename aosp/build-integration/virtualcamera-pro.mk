# Include from the controlled product's .mk file, after device defaults.
PRODUCT_PACKAGES += VirtualCameraProBroker virtual_camera
PRODUCT_SYSTEM_PROPERTIES += ro.virtualcamera.pro.version=2
PRODUCT_COPY_FILES += \
    vendor/virtualcamerapro/build-integration/privapp-permissions.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-virtualcamerapro.xml \
    vendor/virtualcamerapro/build-integration/virtualcamerapro.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/virtualcamerapro.rc \
    vendor/virtualcamerapro/build-integration/default-permissions.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/default-permissions/virtualcamerapro.xml
# The integration tool installs the matching platform-private SELinux policy.
