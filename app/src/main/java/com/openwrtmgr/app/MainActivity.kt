package com.openwrtmgr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.openwrtmgr.app.navigation.OpenWrtNavGraph
import com.openwrtmgr.app.ui.theme.OpenWrtManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as OpenWrtManagerApp).routerRepository
        setContent {
            OpenWrtManagerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OpenWrtNavGraph(repository = repository)
                }
            }
        }
    }
}
