package io.trimio.android.billing

import androidx.activity.ComponentActivity
import io.trimio.core.data.Billing
import java.lang.ref.WeakReference

/** Store billing that needs the visible activity to show the purchase sheet. */
abstract class ActivityBilling : Billing {
    private var activityRef: WeakReference<ComponentActivity>? = null

    protected val activity: ComponentActivity? get() = activityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun attach(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
    }
}
