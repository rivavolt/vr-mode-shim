package net.avolt.vrmodeshim

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.FeatureInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.UserHandle
import android.service.vr.VrListenerService
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
 * Android 16 deleted VrManagerService but kept the API in front of it: Activity.setVrModeEnabled still reaches ActivityClientController.setVrMode, which looks VrManagerInternal up in LocalServices and dereferences it, so the call throws a NullPointerException back into the app, and Daydream's VR launcher makes it in onCreate. With no VR manager registered the call is answered with 0, the success code, and the hook does the one part of VR mode Google's apps depend on: binding the VR listener the app names, as VrManagerService did. Where a VR manager is present the call passes through untouched.
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
        val activityRecord = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl)
        listeners.stub = XposedHelpers.findClass("android.service.vr.IVrListener\$Stub", cl)
        fun noVrManager() = XposedHelpers.callStaticMethod(localServices, "getService", vrManagerInternal) == null

        // the app's request is recorded on its activity, as the original did, and takes effect if that activity is the resumed one
        XposedHelpers.findAndHookMethod(
            "com.android.server.wm.ActivityClientController", cl, "setVrMode",
            IBinder::class.java, Boolean::class.javaPrimitiveType, ComponentName::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!noVrManager()) return
                    param.result = 0
                    try {
                        val atms = XposedHelpers.getObjectField(param.thisObject, "mService")
                        synchronized(XposedHelpers.getObjectField(param.thisObject, "mGlobalLock")) {
                            val record = XposedHelpers.callStaticMethod(activityRecord, "isInRootTaskLocked", param.args[0]) ?: return
                            XposedHelpers.setObjectField(record, "requestedVrComponent", if (param.args[1] as Boolean) param.args[2] else null)
                            if (XposedHelpers.getObjectField(atms, "mLastResumedActivity") === record) listeners.follow(atms, record)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("vr-mode-shim: could not record the VR request: $t")
                    }
                }
            },
        )

        // VrManagerService followed the resumed activity: VR mode is on exactly while the resumed activity has asked for it
        XposedHelpers.findAndHookMethod(
            "com.android.server.wm.ActivityTaskManagerService", cl, "setLastResumedActivityUncheckLocked",
            activityRecord, String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!noVrManager()) return
                    try {
                        listeners.follow(param.thisObject, param.args[0] ?: return)
                    } catch (t: Throwable) {
                        XposedBridge.log("vr-mode-shim: could not follow the resumed activity: $t")
                    }
                }
            },
        )
    }

    private val listeners = VrListeners()

    /**
     * What VrManagerService did for the VR listener an activity names: bind it while that activity is the resumed one and tell it which activity that is, unbind it when the resumed activity no longer asks for VR mode. VR Services learns that VR mode is on only from this binding (its controller service ignores the Daydream button until it does).
     *
     * follow is called with the window manager's lock held, so it only reads the record and posts; binding and unbinding go through ActivityManagerService and run on a thread of their own, as VrManagerService's handler did.
     */
    private class VrListeners {
        // Context.BIND_FOREGROUND_SERVICE, hidden from the SDK; VrManagerService bound the listener with it so the listener runs at foreground priority
        private val BIND_FOREGROUND_SERVICE = 0x04000000
        private val handler = Handler(HandlerThread("vr-mode-shim").apply { start() }.looper)
        var stub: Class<*>? = null
        private var context: Context? = null
        private var bound: ComponentName? = null
        private var connection: ServiceConnection? = null
        private var service: Any? = null
        private var focused: Pair<ComponentName, Int>? = null

        fun follow(atms: Any, record: Any) {
            val listener = XposedHelpers.getObjectField(record, "requestedVrComponent") as ComponentName?
            if (listener == null) return handler.post { leave() }.let {}
            val context = XposedHelpers.getObjectField(atms, "mContext") as Context
            val activity = XposedHelpers.getObjectField(record, "mActivityComponent") as ComponentName
            val pid = XposedHelpers.callMethod(record, "getPid") as Int
            val userId = XposedHelpers.getIntField(record, "mUserId")
            handler.post { enter(context, listener, activity, pid, userId) }
        }

        private fun enter(context: Context, listener: ComponentName, activity: ComponentName, pid: Int, userId: Int) {
            if (focused == activity to pid && bound == listener) return
            focused = activity to pid
            if (bound == listener && connection != null) return tell()
            leave()
            focused = activity to pid
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    handler.post { service = XposedHelpers.callStaticMethod(stub, "asInterface", binder); tell() }
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    handler.post { service = null }
                }
            }
            val intent = Intent(VrListenerService.SERVICE_INTERFACE).setComponent(listener)
            val user = XposedHelpers.callStaticMethod(UserHandle::class.java, "of", userId) as UserHandle
            if (context.bindServiceAsUser(intent, conn, Context.BIND_AUTO_CREATE or BIND_FOREGROUND_SERVICE, user)) {
                this.context = context
                bound = listener
                connection = conn
                XposedBridge.log("vr-mode-shim: VR mode on, bound $listener for $activity")
            } else {
                XposedBridge.log("vr-mode-shim: could not bind $listener")
            }
        }

        private fun leave() {
            focused = null
            val conn = connection ?: return
            context?.unbindService(conn)
            XposedBridge.log("vr-mode-shim: VR mode off, unbound $bound")
            connection = null
            bound = null
            service = null
        }

        private fun tell() {
            val listener = service ?: return
            val (activity, pid) = focused ?: return
            try {
                XposedHelpers.callMethod(listener, "focusedActivityChanged", activity, false, pid)
            } catch (t: Throwable) {
                XposedBridge.log("vr-mode-shim: could not tell the VR listener: $t")
            }
        }
    }
}
