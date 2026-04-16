package com.rahga.x2rock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rahga.x2rock.ui.X2RockNavGraph
import com.rahga.x2rock.ui.theme.X2RockTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            X2RockTheme {
                X2RockNavGraph()
            }
        }
    }
}
