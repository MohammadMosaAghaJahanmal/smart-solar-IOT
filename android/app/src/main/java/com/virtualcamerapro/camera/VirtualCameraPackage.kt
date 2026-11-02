package com.virtualcamerapro.camera

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

class VirtualCameraPackage : BaseReactPackage() {
    override fun getModule(name: String, context: ReactApplicationContext): NativeModule? = if(name == VirtualCameraModule.NAME) VirtualCameraModule(context) else null
    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(VirtualCameraModule.NAME to ReactModuleInfo(VirtualCameraModule.NAME,VirtualCameraModule.NAME,false,false,false,true))
    }
    override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = listOf(MediaPreviewManager())
}
