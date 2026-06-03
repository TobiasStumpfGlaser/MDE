package com.example.mde

import android.app.AlertDialog
import android.view.KeyEvent
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScannerActivityTest {

    @Before
    fun resetDialogs() {
        ShadowAlertDialog.reset()
    }

    private fun hiddenInput(activity: ScannerActivity): EditText {
        val field = ScannerActivity::class.java.getDeclaredField("hiddenScanInput")
        field.isAccessible = true
        return field.get(activity) as EditText
    }

    private fun sendHidBarcode(activity: ScannerActivity, barcode: String) {
        val input = hiddenInput(activity)
        barcode.forEach { c ->
            val keyCode = KeyEvent.keyCodeFromString("KEYCODE_${c.uppercaseChar()}").takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
                ?: when (c) {
                    in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
                    else -> throw IllegalArgumentException("Unsupported test barcode char: $c")
                }
            input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }
        input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun hidBarcodeProcessing_showsConfirmationDialog() {
        val activity = Robolectric.buildActivity(ScannerActivity::class.java).create().start().resume().get()

        sendHidBarcode(activity, "12345")

        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message)?.text.toString().contains("12345"))
    }

    @Test
    fun confirmationYes_setsResultAndFinishes() {
        val activity = Robolectric.buildActivity(ScannerActivity::class.java).create().start().resume().get()

        sendHidBarcode(activity, "ABC1")
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        val shadow = shadowOf(activity)
        assertEquals(android.app.Activity.RESULT_OK, shadow.resultCode)
        assertEquals("ABC1", shadow.resultIntent.getStringExtra("barcode"))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun confirmationNo_resetsStateAndAllowsNextBarcode() {
        val activity = Robolectric.buildActivity(ScannerActivity::class.java).create().start().resume().get()

        sendHidBarcode(activity, "111")
        (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog)
            .getButton(AlertDialog.BUTTON_NEGATIVE)
            .performClick()

        sendHidBarcode(activity, "222")

        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message)?.text.toString().contains("222"))
    }

    @Test
    fun emptyBarcodeOnEnter_doesNotOpenDialog() {
        val activity = Robolectric.buildActivity(ScannerActivity::class.java).create().start().resume().get()
        hiddenInput(activity).dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertFalse((ShadowAlertDialog.getLatestAlertDialog() as? AlertDialog)?.isShowing == true)
    }
}
