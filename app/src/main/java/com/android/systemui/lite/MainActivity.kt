package com.android.systemui.lite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.systemui.lite.ui.theme.MyApplicationTheme
import com.android.systemui.lite.systemui.ui.SimulatedPhone
import com.android.systemui.lite.systemui.ui.DeveloperConfigPanel
import com.android.systemui.lite.systemui.viewmodel.SystemUIViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme(dynamicColor = false) {
        val viewModel: SystemUIViewModel = SystemUIViewModel.instance
        
        Scaffold(
          modifier = Modifier.fillMaxSize(),
          contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
          Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1C1E)) // Professional Polish Dark Slate Background (#1A1C1E)
                .padding(innerPadding)
                .padding(16.dp)
          ) {
            val config = LocalConfiguration.current
            val isTablet = config.screenWidthDp >= 720

            if (isTablet) {
              // Large Screen / Tablet Mode: Dual-pane Side-by-Side Layout
              Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
              ) {
                // Left pane: Simulated Smartphone running SystemUI
                Box(
                  modifier = Modifier
                      .weight(0.42f)
                      .fillMaxHeight(),
                  contentAlignment = Alignment.Center
                ) {
                  SimulatedPhone(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxHeight(0.95f)
                  )
                }

                // Right pane: Developer Suite Controls
                Box(
                  modifier = Modifier
                      .weight(0.58f)
                      .fillMaxHeight()
                ) {
                  DeveloperConfigPanel(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                  )
                }
              }
            } else {
              // Compact Screen Mode: Tabbed Switcher View
              var currentPanelTab by remember { mutableStateOf(0) } // 0: Phone, 1: Controls
              
              Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                // Sub-selector Header
                Card(
                  modifier = Modifier
                      .fillMaxWidth()
                      .height(48.dp),
                  colors = CardDefaults.cardColors(containerColor = Color(0xFF30343A)), // Professional Polish Inactive Card Gray (#30343A)
                  shape = RoundedCornerShape(14.dp)
                ) {
                  Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                      modifier = Modifier
                          .weight(1f)
                          .fillMaxHeight()
                          .background(
                            if (currentPanelTab == 0) Color(0xFFD3E4FF) else Color.Transparent, // Active: light pastel blue (#D3E4FF)
                            RoundedCornerShape(14.dp)
                          )
                          .clickable { currentPanelTab = 0 },
                      contentAlignment = Alignment.Center
                    ) {
                      Text(
                        "📲 Simulated Device",
                        color = if (currentPanelTab == 0) Color(0xFF001C38) else Color(0xFF909094), // Active text: dark navy (#001C38), Inactive text: (#909094)
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                      )
                    }
                    Box(
                      modifier = Modifier
                          .weight(1f)
                          .fillMaxHeight()
                          .background(
                            if (currentPanelTab == 1) Color(0xFFD3E4FF) else Color.Transparent, // Active: light pastel blue (#D3E4FF)
                            RoundedCornerShape(14.dp)
                          )
                          .clickable { currentPanelTab = 1 },
                      contentAlignment = Alignment.Center
                    ) {
                      Text(
                        "🛠️ Developer Suite",
                        color = if (currentPanelTab == 1) Color(0xFF001C38) else Color(0xFF909094), // Active text: dark navy (#001C38), Inactive text: (#909094)
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                      )
                    }
                  }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // View Body with simple fade transitions
                Box(
                  modifier = Modifier
                      .weight(1f)
                      .fillMaxWidth(),
                  contentAlignment = Alignment.Center
                ) {
                  if (currentPanelTab == 0) {
                    SimulatedPhone(
                      viewModel = viewModel,
                      modifier = Modifier.fillMaxHeight()
                    )
                  } else {
                    DeveloperConfigPanel(
                      viewModel = viewModel,
                      modifier = Modifier.fillMaxSize()
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
