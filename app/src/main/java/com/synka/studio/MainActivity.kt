package com.synka.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    companion object {
        private const val CRASH_FILE_NAME = "last_crash.txt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashCatcher()
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, CRASH_FILE_NAME)
        val existingCrashLog = if (crashFile.exists()) {
            crashFile.readText().takeIf { it.isNotBlank() }
        } else {
            null
        }

        val synkaDarkColors = darkColorScheme(
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            primary = Color(0xFF8AB4F8),
            onPrimary = Color(0xFF002D6C),
            error = Color(0xFFCF6679),
            onError = Color(0xFF000000),
            onBackground = Color(0xFFE3E3E3),
            onSurface = Color(0xFFE3E3E3)
        )

        setContent {
            MaterialTheme(colorScheme = synkaDarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (existingCrashLog != null) {
                        CrashCatcherScreen(
                            stackTrace = existingCrashLog,
                            onClearAndRelaunch = {
                                if (crashFile.exists()) {
                                    crashFile.delete()
                                }
                                relaunchActivity()
                            }
                        )
                    } else {
                        ReadyScreen(
                            onSimulateCrash = {
                                throw RuntimeException("Synka Studio Test Gate 1: Simulated uncaught crash on API ${Build.VERSION.SDK_INT}")
                            }
                        )
                    }
                }
            }
        }
    }

    private fun installCrashCatcher() {
        val appContext = applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("Thread: ${thread.name} (id=${thread.id})")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} | API ${Build.VERSION.SDK_INT}")
                pw.println("----------------------------------------")
                throwable.printStackTrace(pw)
                pw.flush()

                val file = File(appContext.filesDir, CRASH_FILE_NAME)
                file.writeText(sw.toString())

                val restartIntent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                appContext.startActivity(restartIntent)
            } catch (e: Exception) {
                previousHandler?.uncaughtException(thread, throwable)
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun relaunchActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}

@Composable
private fun ReadyScreen(onSimulateCrash: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Synka Chunk 1 Ready",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Android API Level: ${Build.VERSION.SDK_INT} (${Build.MODEL})",
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFA0A0A0)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onSimulateCrash,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFCF6679),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Simulate Test Crash",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun CrashCatcherScreen(
    stackTrace: String,
    onClearAndRelaunch: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        Text(
            text = "Synka Crash Catcher",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFCF6679)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Uncaught exception captured and persisted to disk.",
            fontSize = 13.sp,
            color = Color(0xFFB0B0B0)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                .padding(12.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = stackTrace,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color(0xFFE0E0E0)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Synka Crash Log", stackTrace)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Copy Crash Log")
            }

            OutlinedButton(
                onClick = onClearAndRelaunch,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Clear & Relaunch")
            }
        }
    }
}
