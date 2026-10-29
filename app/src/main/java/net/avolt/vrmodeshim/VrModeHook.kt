package net.avolt.vrmodeshim

import android.content.ComponentName
import android.content.pm.FeatureInfo
import android.os.IBinder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Makes Google's Daydream platform run on a phone Google never certified and on an Android that no longer implements VR mode.
 *
 * Daydream, its VR Services and every GVR title refuse to run unless the system declares android.software.vr.mode and android.hardware.vr.high_performance. The module adds the two to SystemConfig's feature map as it loads, before any service reads it, at every framework start and with nothing written to /system.
 *
 * Android 16 deleted VrManagerService but kept the API in front of it: Activity.setVrModeEnabled still reaches ActivityClientController.setVrMode, which looks VrManagerInternal up in LocalServices and dereferences it, so the call throws a NullPointerException back into the app, and Daydream's VR launcher makes it in onCreate. With no VR manager registered the call is answered with 0, the success code, and does nothing, which is what VR mode amounts to on a framework that no longer implements it. Where a VR manager is present the call passes through untouched.
 */
class VrModeHook : IXposedHookLoadPackage {
    private val features = listOf("android.software.vr.mode", "android.hardware.vr.high_performance")

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        val cl = lpparam.classLoader
        guarded("declare features") { declareFeatures(cl) }
        guarded("answer setVrMode") { answerSetVrMode(cl) }
    }

    // a hook that throws in system_server takes the whole framework down, so each one only logs its failure
    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
            XposedBridge.log("vr-mode-shim: hooked to $what")
        } catch (t: Throwable) {
            XposedBridge.log("vr-mode-shim: could not $what: $t")
        }
    }

    // SystemConfig is the one feature map system_server has: ActivityManagerService copies it into the cache apps answer hasSystemFeature from, and PackageManagerService keeps it as the map it answers queries with, both while they are constructed. The module loads before either, so building the singleton here and adding to its map reaches every consumer, with no hook on SystemConfig's own readers, which are small enough for ART to inline into the boot image.
    private fun declareFeatures(cl: ClassLoader) {
        val config = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.android.server.SystemConfig", cl), "getInstance")
        @Suppress("UNCHECKED_CAST")
        val map = XposedHelpers.getObjectField(config, "mAvailableFeatures") as MutableMap<String, FeatureInfo>
        for (name in features) if (name !in map) map[name] = FeatureInfo().apply { this.name = name }
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
