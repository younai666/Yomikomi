package io.github.yomikomi

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.yomikomi.ui.YomikomiApp
import io.github.yomikomi.ui.YomikomiTheme
import io.github.yomikomi.capture.ScreenCaptureService

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 30) ScreenCaptureService.current?.onAppForegrounded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            YomikomiTheme {
                YomikomiApp()
            }
        }
    }
}
