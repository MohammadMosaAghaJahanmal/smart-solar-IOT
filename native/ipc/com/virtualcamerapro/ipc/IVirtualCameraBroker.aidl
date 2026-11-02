package com.virtualcamerapro.ipc;
import android.os.ParcelFileDescriptor;
interface IVirtualCameraBroker {
    String getSupport();
    String getStatus();
    boolean activate(in ParcelFileDescriptor source, String mediaType, String settings);
    void deactivate();
    void updateSource(in ParcelFileDescriptor source, String mediaType);
    void updateSettings(String settings);
}
