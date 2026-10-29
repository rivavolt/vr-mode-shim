package net.avolt.vrmodeshim

import android.content.ComponentName
import android.os.IBinder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Android 16 deleted VrManagerService but kept the API in front of it: Activity.setVrModeEnabled still reaches ActivityClientController.setVrMode in system_server, which looks VrManagerInternal up in LocalServices and dereferences it, so on a device that declares the VR features the call throws a NullPointerException back into the app. Every Google VR app (Daydream, its launcher, the GVR SDK titles) makes that call in onCreate and dies there.
 *
 * With no VR manager registered, the call is answered with 0, the success code, and does nothing, which is what VR mode amounts to on a framework that no longer implements it. Where a VR manager is present the call passes through untouched.
 */
class VrModeHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        val cl = lpparam.classLoader
        val localServices = XposedHelpers.findClass("com.android.server.LocalServices", cl)
        val vrManagerInternal = XposedHelpers.findClass("com.android.server.vr.VrManagerInternal", cl)
        XposedHelpers.findAndHookMethod(
            "com.android.server.wm.ActivityClientController", cl, "setVrMode",
            IBinder::class.java, Boolean::class.javaPrimitiveType, ComponentName::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (XposedHelpers.callStaticMethod(localServices, "getService", vrManagerInternal) != null) return
                    XposedBridge.log("vr-mode-shim: setVrMode(${param.args[1]}) for ${param.args[2]} answered with no VR manager")
                    param.result = 0
                }
            },
        )
    }
}
