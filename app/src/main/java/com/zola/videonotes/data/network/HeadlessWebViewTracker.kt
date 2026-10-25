package com.zola.videonotes.data.network

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * 前台 Activity 追踪：无头 WebView 必须挂在窗口上才能跑 JS，
 * 这里用 Application 生命周期回调记录当前 resumed 的 Activity（弱引用，不泄漏）。
 */
object HeadlessWebViewTracker {

    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() == activity) {
                    resumedActivity = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun currentActivity(): Activity? = resumedActivity?.get()
}
