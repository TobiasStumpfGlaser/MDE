package com.example.mde

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoginActivityTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private var previousTimeout = 0
    private var previousDefaultUser = ""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        previousTimeout = settings.logoutTimeSec
        previousDefaultUser = settings.defaultUser
        settings.logoutTimeSec = 1
        settings.defaultUser = "missing-user"

        mockkObject(TcpClient)
        every { TcpClient.sendCommand(any(), any(), any(), any(), any()) } returns
                "{GetBediener}\nAA|1234|User A\n{/GetBediener}"

        mockkObject(UiLoadingHelper)
        every { UiLoadingHelper.show(any(), any(), any(), any()) } just Runs
        every { UiLoadingHelper.update(any(), any(), any()) } just Runs
        every { UiLoadingHelper.showError(any(), any()) } just Runs
        every { UiLoadingHelper.hide() } just Runs
        every { UiLoadingHelper.playErrorSound(any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        settings.logoutTimeSec = previousTimeout
        settings.defaultUser = previousDefaultUser
        UserCache.userList.clear()
        UserCache.userPinMap.clear()
    }

    @Test
    fun inactivityTimeout_clearsPinAndUsername() {
        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create().start().resume().get()

        val txtUsername = activity.findViewById<AutoCompleteTextView>(R.id.txtUsername)
        val txtPin = activity.findViewById<EditText>(R.id.txtPin)

        activity.runOnUiThread {
            txtUsername.setText("Someone", false)
            txtPin.setText("9999")
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))

        assertEquals("", txtPin.text.toString())
        assertEquals("", txtUsername.text.toString())
    }

    @Test
    fun dispatchTouchEvent_resetsInactivityTimer() {
        val activity = Robolectric.buildActivity(LoginActivity::class.java)
            .create().start().resume().get()

        val txtPin = activity.findViewById<EditText>(R.id.txtPin)
        activity.runOnUiThread { txtPin.setText("9999") }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        mainLooper.idleFor(Duration.ofMillis(800))
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        activity.dispatchTouchEvent(event)
        event.recycle()

        mainLooper.idleFor(Duration.ofMillis(300))
        assertEquals("9999", txtPin.text.toString())

        mainLooper.idleFor(Duration.ofMillis(800))
        assertEquals("", txtPin.text.toString())
    }
}
