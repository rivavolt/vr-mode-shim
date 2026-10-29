package net.avolt.vrmodeshim

import android.content.ComponentName
import android.os.IBinder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Makes Google's Daydream platform run on a phone Google never certified and on an Android that no longer implements VR mode.
 *
 * Daydream, its VR Services and every GVR title refuse to run unless the system declares android.software.vr.mode and android.hardware.vr.high_performance. SystemConfig reads features from the XML files under /system/etc/permissions when system_server starts, so the hook adds the two right after that read, which holds for every framework start and needs nothing written to /system.
 *
 * Android 16 deleted VrManagerService but kept the API in front of it: Activity.setVrModeEnabled still reaches ActivityClientController.setVrMode, which looks VrManagerInternal up in LocalServices and dereferences it, so the call throws a NullPointerException back into the app, and Daydream's VR launcher makes it in onCreate. With no VR manager registered the call is answered with 0, the success code, and does nothing, which is what VR mode amounts to on a framework that no longer implements it. Where a VR manager is present the call passes through untouched.
 */
class VrModeHook : IXposedHookLoadPackage {
    private val features = listOf("android.software.vr.mode", "android.hardware.vr.high_performance")

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        val cl = lpparam.classLoader
        declareFeatures(cl)
        answerSetVrMode(cl)
    }

    private fun declareFeatures(cl: ClassLoader) {
        val systemConfig = XposedHelpers.findClass("com.android.server.SystemConfig", cl)
        XposedHelpers.findAndHookMethod(systemConfig, "readAllPermissions", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = addFeatures(param.thisObject)
        })
        // the instance may already exist if the framework read its config before the module loaded
        XposedHelpers.getStaticObjectField(systemConfig, "sInstance")?.let { addFeatures(it) }
    }

    private fun addFeatures(config: Any) {
        for (name in features) XposedHelpers.callMethod(config, "addFeature", name, 0)
        XposedBridge.log("vr-mode-shim: declared $features")
    }

    private fun answerSetVrMode(cl: ClassLoader) {
        val localServices = XposedHelpers.findClass("com.android.server.LocalServices", cl)
        val vrManagerInternal = XposedHelpers.findClass("com.android.server.vr.VrManagerInternal", cl)
        XposedHelpers.findAndHookMethod(
            "com.android.server.wm.ActivityClientController", cl, "setVrMode",
            IBinder::class.java, Boolean::class.javaPrimitiveType, ComponentName::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (XposedHelpers.callStaticMethod(localServices, "getService", vrManagerInternal) != null) return
                    param.result = 0
                }
            },
        )
    }
}
