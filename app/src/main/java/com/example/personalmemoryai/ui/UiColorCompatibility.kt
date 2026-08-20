package com.example.personalmemoryai.ui

import android.graphics.Color
import android.widget.TextView

/**
 * Compatibility overload for legacy programmatic screens where a TextView's
 * `text` property was accidentally passed to setTextColor(). New code should
 * use an explicit Int color value instead.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
fun TextView.setTextColor(color: CharSequence) {
    setTextColor(Color.rgb(235, 246, 255))
}
