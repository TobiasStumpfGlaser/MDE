import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.Gravity

object UiLoadingHelper {

    private var loadingDialog: AlertDialog? = null
    private var loadingText: TextView? = null

    fun show(activity: Activity, message: String = "Kommunikation mit Server...") {

        // Wenn Dialog schon existiert → nur Text ändern
        if (loadingDialog != null) {
            loadingText?.text = message
            return
        }

        // ProgressBar
        val progress = ProgressBar(activity)

        // TextView zentrieren
        val text = TextView(activity)
        text.text = message
        text.setPadding(20, 20, 20, 20)
        text.gravity = Gravity.CENTER
        text.textAlignment = TextView.TEXT_ALIGNMENT_CENTER

        // Referenz speichern für spätere Updates
        loadingText = text

        // Layout zentrieren
        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)
        layout.gravity = Gravity.CENTER
        layout.addView(progress)
        layout.addView(text)

        // Dialog erstellen
        loadingDialog = AlertDialog.Builder(activity)
            .setView(layout)
            .setCancelable(false)
            .create()

        loadingDialog?.show()
    }

    fun hide() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }
}