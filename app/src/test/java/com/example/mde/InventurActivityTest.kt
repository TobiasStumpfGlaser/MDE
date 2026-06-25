package com.example.mde

import android.content.Intent
import android.os.Looper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.example.mde.model.Artikel
import io.mockk.*
import org.junit.*
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
                artNr = "123.4567",
                bez = "Test Artikel",
                lagerorteW1 = listOf("A", "", ""),
                lagerorteW2 = listOf("", "", ""),
                masseinheit = "ST",
                bestand = "10",
                mindestbestand = 0,
                empfBestMenge = 0,
                bestellTrigger = 0,
                grossInfo = "",
                liefBestNr = "",
                snPflicht = false,
                bestellt3M = 4,
                bestellt6M = 6,
                EAN = "4001234567890",
                suchZusatz = "TESTSUCH"
            ),
            Artikel(
                artNr = "123.4568",
                bez = "Test Artikel",
                lagerorteW1 = listOf("A", "", ""),
                lagerorteW2 = listOf("", "", ""),
                masseinheit = "ST",
                bestand = "10",
                mindestbestand = 0,
                empfBestMenge = 0,
                bestellTrigger = 0,
                grossInfo = "",
                liefBestNr = "",
                snPflicht = true,
                EAN = "4001234567892",
                bestellt3M = 4,
                bestellt6M = 6,
                suchZusatz = "TESTSUCH2"
            )
        )

        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            InventurActivity::class.java
        ).apply {
            putExtra("USERNAME", "testuser")
        }

        activity = Robolectric.buildActivity(InventurActivity::class.java, intent)
            .create().start().resume().get()
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        DataRepository.clear()
    }

    // ─────────────────────────────────────────────
    // EAN SEARCH TEST
    // ─────────────────────────────────────────────

    @Test
    fun filter_byEAN_findsArticle() {
        activity.runOnUiThread {
            activity.etFilter.setText("4001234567892")
        }

        Assert.assertTrue(activity.hasSelectedArtikel())
    }

    // ─────────────────────────────────────────────
    // SUCHZUSATZ SEARCH TEST
    // ─────────────────────────────────────────────

    @Test
    fun filter_bySuchzusatz_findsArticle() {
        activity.runOnUiThread {
            activity.etFilter.setText("TESTSUCH2")
        }

        Assert.assertTrue(activity.hasSelectedArtikel())
    }

    // ─────────────────────────────────────────────
    // SN PFLICHT VALIDATION
    // ─────────────────────────────────────────────

    @Test
    fun snPflicht_blocksBooking_withoutSerials() {
        activity.runOnUiThread {
            activity.etFilter.setText("123.4568 | Test Artikel")
            activity.edtMenge.setText("1")
        }

        val result = activity.doBuchenWithDetails(
            einlagern = true,
            count = false,
            artikelText = null,
            projektText = "TEST",
            mengeText = "1",
            serialsText = "" // ❌ keine Seriennummern
        )

        Assert.assertFalse(result)

        verify(exactly = 0) {
            TcpClient.sendCommand(any(), any(), any(), any(), any())
        }
    }

    // ─────────────────────────────────────────────
    // SN OK = Booking allowed
    // ─────────────────────────────────────────────

    @Test
    fun snPflicht_allowsBooking_withSerials() {
        activity.runOnUiThread {
            activity.etFilter.setText("123.4567 | Test Artikel")
            activity.edtMenge.setText("1")
        }

        val result = activity.doBuchenWithDetails(
            einlagern = true,
            count = false,
            artikelText = null,
            projektText = "TEST",
            mengeText = "1",
            serialsText = "SN123"
        )

        Assert.assertTrue(result)

        verify(exactly = 1) {
            TcpClient.sendCommand(any(), any(), "SetBuchung", any(), any())
        }
    }

    // ─────────────────────────────────────────────
    // COUNT BOOKING (unchanged logic)
    // ─────────────────────────────────────────────

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
            projektText = "TEST",
            mengeText = "9",
            serialsText = ""
        )

        verify {
            TcpClient.sendCommand(
                any(),
                any(),
                eq("SetBuchung"),
                match { it.contains("||=9|") },
                eq("{/SetBuchung}")
            )
        }
    }

    // ─────────────────────────────────────────────
    // TIMER TEST (unchanged)
    // ─────────────────────────────────────────────

    @Test
    fun dispatchTouchEvent_resetsInactivityTimer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = AppSettings(context)

        val previousTimeout = settings.logoutTimeSec
        settings.logoutTimeSec = 1

        try {
            val intent = Intent(context, InventurActivity::class.java).apply {
                putExtra("USERNAME", "testuser")
            }

            val activity = Robolectric.buildActivity(InventurActivity::class.java, intent)
                .create().start().resume().get()

            val shadowActivity = Shadows.shadowOf(activity)
            val looper = Shadows.shadowOf(Looper.getMainLooper())

            looper.idleFor(Duration.ofMillis(800))

            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            activity.dispatchTouchEvent(event)
            event.recycle()

            looper.idleFor(Duration.ofMillis(300))
            Assert.assertNull(shadowActivity.nextStartedActivity)

            looper.idleFor(Duration.ofMillis(1200))

            val startedIntent = shadowActivity.nextStartedActivity
            Assert.assertNotNull(startedIntent)
            Assert.assertEquals(LoginActivity::class.java.name, startedIntent.component?.className)

        } finally {
            settings.logoutTimeSec = previousTimeout
        }
    }
}