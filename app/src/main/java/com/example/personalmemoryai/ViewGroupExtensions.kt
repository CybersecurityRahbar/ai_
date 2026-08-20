package com.example.personalmemoryai

import android.view.View
import android.view.ViewGroup

/** Small compatibility helper used by the programmatic Command Center UI. */
fun ViewGroup.childrenSequence(): Sequence<View> = sequence {
    for (index in 0 until childCount) {
        yield(getChildAt(index))
    }
}
