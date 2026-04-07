package com.example.mde

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

object FontScaleUtil {

    /**
     * Skaliert alle TextViews im View-Tree relativ zur "originalen" Größe (in sp),
     * die pro View als Tag gespeichert wird.
     */
    fun applyFontScale(root: View, scale: Float) {
        traverse(root) { v ->
            if (v is TextView) {
                val key = R.id.tag_original_text_size_sp

                val originalSp = (v.getTag(key) as? Float)
                    ?: pxToSp(v, v.textSize).also { v.setTag(key, it) }

                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, originalSp * scale)
            }
        }
    }

    private fun traverse(view: View, block: (View) -> Unit) {
        block(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                traverse(view.getChildAt(i), block)
            }
        }
    }

    private fun pxToSp(view: View, px: Float): Float {
        return px / view.resources.displayMetrics.scaledDensity
    }
}