package com.example.mde

import android.content.Intent
import android.os.Looper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.example.mde.model.Artikel
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
class InventurActivityTest {

    private lateinit var activity: InventurActivity

    @Before
    fun setUp() {
        mockkObject(TcpClient)
        every { TcpClient.sendCommand(any(), any(), any(), any(), any()) } returns
                "{SetBuchung}\nok\n{/SetBuchung}"

        mockkObject(UiLoadingHelper)
        every { UiLoadingHelper.show(any(), any(), any(), any()) } just Runs
        every { UiLoadingHelper.hide() } just Runs
        every { UiLoadingHelper.showError(any(), any()) } just Runs
        every { UiLoadingHelper.update(any(), any(), any()) } just Runs
        every { UiLoadingHelper.playErrorSound(any()) } just Runs

        DataRepository.clear()
        DataRepository.artikelListe = listOf(
            Artikel(
                "123.4567", "Test Artikel",
                listOf("A", "", ""), listOf("", "", ""),
                "ST", "10", 0, 0, 0, "", ""
            )
        )

        val intent = Intent(ApplicationProvider.getApplicationContext(), InventurActivity::class.java)
        intent.putExtra("USERNAME", "testuser")
        activity = Robolectric.buildActivity(InventurActivity::class.java, intent)
            .create().start().resume().get()
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        DataRepository.clear()
    }

    @Test
    fun countBooking_usesEqualsPrefixInServerAmount() {
        activity.runOnUiThread {
            activity.etFilter.setText("123.4567 | Test Artikel")
            activity.edtMenge.setText("9")
        }

        clearMocks(TcpClient, answers = false, recordedCalls = true)
        activity.doBuchenWithDetails(
            einlagern = true,
            count = true,
            artikelText = null,
            projektText = "",
            mengeText = activity.edtMenge.text.toString(),
            serialsText = ""
        )

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = match { it.contains("||=9|") },
                endTag = "{/SetBuchung}"
            )
        }
    }

    @Test
    fun validation_emptyArticleOrMissingAmount_preventsBooking() {
        activity.runOnUiThread {
            activity.etFilter.setText("")
            activity.edtMenge.setText("5")
        }
        activity.doBuchenWithDetails(
            einlagern = true,
            count = true,
            artikelText = null,
            projektText = "",
            mengeText = activity.edtMenge.text.toString(),
            serialsText = ""
        )

        activity.runOnUiThread {
            activity.etFilter.setText("123.4567 | Test Artikel")
            activity.edtMenge.setText("")
        }
        activity.doBuchenWithDetails(
            einlagern = true,
            count = true,
            artikelText = null,
            projektText = "",
            mengeText = activity.edtMenge.text.toString(),
            serialsText = ""
        )

        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), any(), any(), any()) }
    }

    @Test
    fun validCountBooking_callsServerSuccessfully() {
        activity.runOnUiThread {
            activity.etFilter.setText("123.4567 | Test Artikel")
            activity.edtMenge.setText("3")
        }

        val started = activity.doBuchenWithDetails(
            einlagern = true,
            count = true,
            artikelText = null,
            projektText = "",
            mengeText = activity.edtMenge.text.toString(),
            serialsText = ""
        )

        assertEquals(true, started)
        verify(timeout = 2000, exactly = 1) { TcpClient.sendCommand(any(), any(), "SetBuchung", any(), any()) }
    }

    @Test
    fun dispatchTouchEvent_resetsLogoutTimer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = AppSettings(context)
        val previousTimeout = settings.logoutTimeSec
        settings.logoutTimeSec = 1

        try {
            val intent = Intent(context, InventurActivity::class.java)
            intent.putExtra("USERNAME", "testuser")
            val logoutActivity = Robolectric.buildActivity(InventurActivity::class.java, intent)
                .create().start().resume().get()

            val shadowActivity = Shadows.shadowOf(logoutActivity)
            val mainLooper = Shadows.shadowOf(Looper.getMainLooper())

            mainLooper.idleFor(Duration.ofMillis(800))
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            logoutActivity.dispatchTouchEvent(event)
            event.recycle()

            mainLooper.idleFor(Duration.ofMillis(300))
            assertNull(shadowActivity.nextStartedActivity)

            mainLooper.idleFor(Duration.ofMillis(800))
            val startedIntent = shadowActivity.nextStartedActivity
            assertNotNull(startedIntent)
            assertEquals(LoginActivity::class.java.name, startedIntent.component?.className)
        } finally {
            settings.logoutTimeSec = previousTimeout
        }
    }
}
