package com.gecesars.atxplan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gecesars.atxplan.ui.AtxPlanApp
import com.gecesars.atxplan.ui.theme.AtxPlanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtxPlanTheme {
                AtxPlanApp()
            }
        }
    }
}
