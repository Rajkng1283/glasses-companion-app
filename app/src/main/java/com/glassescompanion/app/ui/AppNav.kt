package com.glassescompanion.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.glassescompanion.app.AppGraph

object Routes {
    const val HOME = "home"
    const val DEVICE = "device"
    const val DIALOGUE = "dialogue"
    const val INTERPRET = "interpret"
    const val GLASSES_PHONE = "glasses_phone"
    const val CALL = "call"
    const val PHOTO = "photo"
    const val ASSISTANT = "assistant"
    const val RECORDING = "recording"
    const val REMINDERS = "reminders"
    const val ALBUM = "album"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val GUIDE = "guide"
}

data class CallInvite(val room: String, val server: String?)

@Composable
fun AppNav(g: AppGraph, invite: CallInvite?) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    LaunchedEffect(invite) { if (invite != null) nav.navigate(Routes.CALL) }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(g) { nav.navigate(it) } }
        composable(Routes.DEVICE) { DeviceScreen(g, back) }
        composable(Routes.DIALOGUE) { DialogueScreen(g, back) }
        composable(Routes.INTERPRET) { InterpretationScreen(g, back) }
        composable(Routes.GLASSES_PHONE) { GlassesPhoneScreen(g, back) }
        composable(Routes.CALL) { CallScreen(g, back, invite?.room, invite?.server) }
        composable(Routes.PHOTO) { PhotoTranslateScreen(g, back) }
        composable(Routes.ASSISTANT) { AssistantScreen(g, back) }
        composable(Routes.RECORDING) { RecordingScreen(g, back) }
        composable(Routes.REMINDERS) { RemindersScreen(g, back) }
        composable(Routes.ALBUM) { AlbumScreen(g, back) { id -> nav.navigate("${Routes.PLAYER}/${Uri.encode(id)}") } }
        composable("${Routes.PLAYER}/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            PlayerScreen(g, Uri.decode(entry.arguments?.getString("id").orEmpty()), back)
        }
        composable(Routes.SETTINGS) { SettingsScreen(g, back) { nav.navigate(Routes.GUIDE) } }
        composable(Routes.GUIDE) { ControlGuideScreen(g, back) }
    }
}
