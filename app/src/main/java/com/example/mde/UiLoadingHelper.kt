import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object UiLoadingHelper {

    private var loadingDialog: AlertDialog? = null

    fun show(activity: Activity, message: String = "Kommunikation mit Server...") {

        if (loadingDialog != null) return

        val progress = ProgressBar(activity)

        val text = TextView(activity)
        text.text = message
        text.setPadding(20, 20, 20, 20)

        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)
        layout.addView(progress)
        layout.addView(text)

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