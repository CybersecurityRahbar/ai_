package com.example.personalmemoryai.ui

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Test

class BulkImagePickerIntentTest {
    @Test
    fun launchIntent_targetsBulkImagePickerExplicitly() {
        val intent = BulkImagePickerActivity.launchIntent("TEST")
        assertEquals(
            ComponentName(
                "com.example.personalmemoryai",
                "com.example.personalmemoryai.ui.BulkImagePickerActivity"
            ),
            intent.component
        )
        assertEquals("TEST", intent.getStringExtra("title"))
    }
}
