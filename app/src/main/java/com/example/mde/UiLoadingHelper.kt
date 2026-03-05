import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.Gravity

object UiLoadingHelper {

    private var loadingDialog: AlertDialog? = null

    fun show(activity: Activity, message: String = "Kommunikation mit Server...") {

        if (loadingDialog != null) return

        // ProgressBar
        val progress = ProgressBar(activity)

        // TextView zentrieren
        val text = TextView(activity)
        text.text = message
        text.setPadding(20, 20, 20, 20)
        text.gravity = Gravity.CENTER          // Text innerhalb der TextView zentrieren
        text.textAlignment = TextView.TEXT_ALIGNMENT_CENTER

        // Layout zentrieren
        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)
        layout.gravity = Gravity.CENTER        // Elemente innerhalb des Layouts zentrieren
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