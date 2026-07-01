package com.android.systemui.lite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.lite.core.GlobalActionsCoreStartable
import com.android.systemui.lite.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(dynamicColor = false) {
                val app = application as SystemUIApplication
                val logs by app.systemLogs.collectAsState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1C1E))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "SystemUI Running",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "CoreStartables: ${app.getStartableCount()}",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                        Text(
                            "Process: com.android.systemui",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        val ga = app.getStartable(GlobalActionsCoreStartable::class.java)
                        val ipcOk = ga?.isRegistered == true

                        Text(
                            "IPC: ${if (ipcOk) "Connected" else "Disconnected"}",
                            color = if (ipcOk) Color(0xFF30D158) else Color(0xFFFF453A),
                            fontSize = 13.sp
                        )

                        Button(
                            onClick = { ga?.show() },
                            modifier = Modifier.fillMaxWidth(0.7f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF453A)
                            )
                        ) {
                            Text("Test Power Menu", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
