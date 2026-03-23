package app.movino.tv

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.getcapacitor.BridgeActivity
import app.movino.tv.plugin.NativePlayerPlugin

class MainActivity : BridgeActivity() {
    private val darkColor = Color.parseColor("#0A0E1A")

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(NativePlayerPlugin::class.java)
        super.onCreate(savedInstanceState)

        // Force dark background everywhere to prevent white flash on keyboard open/switch
        window.decorView.setBackgroundColor(darkColor)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.rootView.setBackgroundColor(darkColor)
        bridge?.webView?.setBackgroundColor(darkColor)

        // Also paint the navigation bar and status bar dark
        window.navigationBarColor = darkColor
        window.statusBarColor = darkColor

        // Walk the entire view tree and force dark backgrounds
        forceBackgroundRecursive(window.decorView)
    }

    override fun onResume() {
        super.onResume()
        // Re-apply after keyboard changes or config changes
        window.decorView.setBackgroundColor(darkColor)
        bridge?.webView?.setBackgroundColor(darkColor)
        forceBackgroundRecursive(window.decorView)
    }

    private fun forceBackgroundRecursive(view: View) {
        view.setBackgroundColor(darkColor)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                // Skip the WebView itself (already handled) and its children
                if (child === bridge?.webView) continue
                forceBackgroundRecursive(child)
            }
        }
    }
}
