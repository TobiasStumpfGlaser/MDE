package com.example.mde

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class SettingsActivityTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private var previousTimeout = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        previousTimeout = settings.logoutTimeSec
        settings.logoutTimeSec = 1
    }

    @After
    fun tearDown() {
        settings.logoutTimeSec = previousTimeout
    }

    @Test
    fun dispatchTouchEvent_resetsInactivityTimer() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .create().start().resume().get()

        val shadowActivity = Shadows.shadowOf(activity)
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())

        mainLooper.idleFor(Duration.ofMillis(800))
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        activity.dispatchTouchEvent(event)
        event.recycle()

        mainLooper.idleFor(Duration.ofMillis(300))
        assertNull(shadowActivity.nextStartedActivity)

        mainLooper.idleFor(Duration.ofMillis(800))
        val startedIntent = shadowActivity.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(LoginActivity::class.java.name, startedIntent.component?.className)
    }
}
