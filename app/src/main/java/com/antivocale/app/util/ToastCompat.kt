package com.antivocale.app.util

import android.content.Context
import android.view.Gravity
import android.widget.Toast

/**
 * Shows a [Toast] positioned above the navigation bar so it doesn't overlap
 * with gesture navigation in edge-to-edge mode.
 */
object ToastCompat {

    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, message, duration).apply {
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, navBarHeight(context))
            show()
        }
    }

    fun show(context: Context, resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, resId, duration).apply {
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, navBarHeight(context))
            show()
        }
    }

    private fun navBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }
}
