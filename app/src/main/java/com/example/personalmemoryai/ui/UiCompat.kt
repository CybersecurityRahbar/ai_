package com.example.personalmemoryai.ui

import android.view.View
import android.view.ViewGroup

/** Small UI compatibility helpers for the project-wide programmatic UI. */
fun ViewGroup.childrenSequence(): Sequence<View> = sequence {
    for (index in 0 until childCount) {
        yield(getChildAt(index))
    }
}
