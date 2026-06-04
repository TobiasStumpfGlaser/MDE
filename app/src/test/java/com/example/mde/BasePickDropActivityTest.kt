package com.example.mde

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BasePickDropActivityTest {

    @Before
    fun setUp() {
        mockkObject(TcpClient)
        every { TcpClient.sendCommand(any(), any(), any(), any(), any()) } answers {
            when (thirdArg<String>()) {
                "GetPickOverview" -> "{GetPickOverview}\nL1|P100|Projekt 100\n{/GetPickOverview}"
                "GetDropOverview" -> "{GetDropOverview}\nL1|P100|Projekt 100\n{/GetDropOverview}"
                "GetArtikel" -> "{GetArtikel}\n123.4567|Artikel|A|B|C|D|E|F|ST|1|0|0|0|G|L\n{/GetArtikel}"
                else -> "{SetBuchung}\nok\n{/SetBuchung}"
            }
        }

        mockkObject(UiLoadingHelper)
        every { UiLoadingHelper.show(any(), any(), any(), any()) } just Runs
        every { UiLoadingHelper.hide() } just Runs
        every { UiLoadingHelper.showError(any(), any()) } just Runs
        every { UiLoadingHelper.update(any(), any(), any()) } just Runs
        every { UiLoadingHelper.playErrorSound(any()) } just Runs
        every {
            UiLoadingHelper.confirm(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val onOk = arg<() -> Unit>(7)
            onOk.invoke()
            Unit
        }

        DataRepository.clear()
    }

    @After
    fun tearDown() {
        unmockkObject(TcpClient)
        unmockkObject(UiLoadingHelper)
        DataRepository.clear()
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = BasePickDropActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun showItemDialog(activity: BasePickDropActivity, item: ListDetail) {
        val method = BasePickDropActivity::class.java.getDeclaredMethod("showItemDialog", ListDetail::class.java)
        method.isAccessible = true
        method.invoke(activity, item)
    }

    @Test
    fun pickValidation_blankArticle_showsErrorAndDoesNotBook() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        setField(activity, "currentProjektNr", "P100")
        val item = ListDetail("", "2", "1", "Info", listenNummer = "L1")

        showItemDialog(activity, item)
        // Let Robolectric process all pending tasks before accessing the dialog
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Dialog should be created", dialog)
        (dialog as AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .performClick()

        verify { UiLoadingHelper.showError(activity, match<String> { it.contains("Artikel darf nicht leer") }) }
        verify(exactly = 0) { TcpClient.sendCommand(any(), any(), "SetBuchung", any(), any()) }
    }

    @Test
    fun pickBooking_usesNegativeAmountAndUpdatesSuccessStatus() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PickListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(PickListActivity::class.java, intent)
            .create().start().resume().get()

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        val item = ListDetail("123.4567", "3", "1", "Info", listenNummer = "L1")
        setField(activity, "currentProjektNr", "P100")
        setField(activity, "detailsListe", listOf(item))
        setField(activity, "detailsOriginal", listOf(item.copy()))

        showItemDialog(activity, item)
        // Let Robolectric process all pending tasks before accessing the dialog
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Dialog should be created", dialog)
        (dialog as AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .performClick()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = match<String> { it.contains("||-3|") },
                endTag = "{/SetBuchung}"
            )
        }
        verify { UiLoadingHelper.update(activity, match<String> { it.contains("Buchung erfolgreich") }, any()) }
    }

    @Test
    fun dropBooking_usesPositiveAmount() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), DropListActivity::class.java)
        intent.putExtra("USERNAME", "tester")
        val activity = Robolectric.buildActivity(DropListActivity::class.java, intent)
            .create().start().resume().get()

        clearMocks(TcpClient, answers = false, recordedCalls = true)

        val item = ListDetail("123.4567", "4", "1", "Info", listenNummer = "L1")
        setField(activity, "currentProjektNr", "P100")
        setField(activity, "detailsListe", listOf(item))
        setField(activity, "detailsOriginal", listOf(item.copy()))

        showItemDialog(activity, item)
        // Let Robolectric process all pending tasks before accessing the dialog
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Dialog should be created", dialog)
        (dialog as AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .performClick()

        verify(timeout = 2000) {
            TcpClient.sendCommand(
                context = any(),
                settings = any(),
                command = "SetBuchung",
                request = match<String> { it.contains("||+4|") },
                endTag = "{/SetBuchung}"
            )
        }
    }
}
