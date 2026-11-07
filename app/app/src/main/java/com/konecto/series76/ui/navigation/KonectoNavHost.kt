package com.konecto.series76.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.konecto.series76.ui.components.background.BubbleBackground
import com.konecto.series76.ui.components.background.BubblePresets
import com.konecto.series76.ui.feature.chats.ChatsRoute
import com.konecto.series76.ui.feature.conversation.ConversationRoute
import com.konecto.series76.ui.feature.debug.DebugAnswerRoute
import com.konecto.series76.ui.feature.debug.DebugAnswersRoute
import com.konecto.series76.ui.feature.debug.DebugChatsRoute
import com.konecto.series76.ui.feature.login.LoginRoute
import com.konecto.series76.ui.feature.transition.TransitionScreen
import kotlinx.coroutines.flow.Flow

private const val SLIDE_MS = 320

/**
 * Navigates only if [from] is still the top of the back stack. A second tap (or the end of an animation racing a tap)
 * comes from a screen that is already leaving, so it is ignored: a destination is never pushed twice.
 */
private fun NavHostController.navigateFrom(from: NavBackStackEntry, route: Route, builder: NavOptionsBuilder.() -> Unit = {}) {
    if (currentBackStackEntry?.id != from.id) return
    navigate(route) {
        launchSingleTop = true
        builder()
    }
}

/** Pops [from] once (double taps on "back" while the screen is leaving do nothing). */
private fun NavHostController.backFrom(from: NavBackStackEntry) {
    if (currentBackStackEntry?.id == from.id && previousBackStackEntry != null) popBackStack()
}

/** Login and Transition replace the whole stack: they cross-fade instead of sliding. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isRootSwitch(): Boolean =
    listOf(initialState, targetState).any { it.destination.hasRoute<Route.Login>() || it.destination.hasRoute<Route.Transition>() }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(direction: SlideDirection, offset: (Int) -> Int): EnterTransition =
    if (isRootSwitch()) fadeIn() else slideIntoContainer(direction, tween(SLIDE_MS), offset) + fadeIn(tween(SLIDE_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(direction: SlideDirection, offset: (Int) -> Int): ExitTransition =
    if (isRootSwitch()) fadeOut() else slideOutOfContainer(direction, tween(SLIDE_MS), offset) + fadeOut(tween(SLIDE_MS))

@Composable
fun KonectoNavHost(
    startDestination: Route,
    forcedLogout: Flow<Unit>,
    navController: NavHostController = rememberNavController(),
) {
    fun toLogin() {
        if (navController.currentDestination?.hasRoute<Route.Login>() == true) return
        navController.navigate(Route.Login) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    // 401 from the server (expired/revoked token) → back to Login from anywhere.
    LaunchedEffect(Unit) { forcedLogout.collect { toLogin() } }

    // One shared background for the chat/debug screens: only their content moves during transitions.
    // Login and Transition draw their own (opaque) backgrounds on top.
    Box(Modifier.fillMaxSize()) {
        BubbleBackground(BubblePresets.Main, Modifier.fillMaxSize())
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { enter(SlideDirection.Start) { it } },
            exitTransition = { exit(SlideDirection.Start) { it / 4 } },
            popEnterTransition = { enter(SlideDirection.End) { it / 4 } },
            popExitTransition = { exit(SlideDirection.End) { it } },
        ) {
            composable<Route.Login> { entry ->
                LoginRoute(onLoggedIn = {
                    navController.navigateFrom(entry, Route.Transition) { popUpTo<Route.Login> { inclusive = true } }
                })
            }
            composable<Route.Transition> { entry ->
                TransitionScreen(onFinished = {
                    navController.navigateFrom(entry, Route.Chats) { popUpTo<Route.Transition> { inclusive = true } }
                })
            }
            composable<Route.Chats> { entry ->
                ChatsRoute(
                    onOpenChat = { navController.navigateFrom(entry, Route.Conversation(it)) },
                    onNewChat = { navController.navigateFrom(entry, Route.Conversation()) },
                    onDebug = { navController.navigateFrom(entry, Route.DebugChats) },
                    onLoggedOut = ::toLogin,
                )
            }
            composable<Route.Conversation> { entry -> ConversationRoute(onBack = { navController.backFrom(entry) }) }
            composable<Route.DebugChats> { entry ->
                DebugChatsRoute(
                    onBack = { navController.backFrom(entry) },
                    onOpenChat = { id, label -> navController.navigateFrom(entry, Route.DebugAnswers(id, label)) },
                    onServerChanged = ::toLogin,
                )
            }
            composable<Route.DebugAnswers> { entry ->
                val sessionId = entry.toRoute<Route.DebugAnswers>().sessionId
                DebugAnswersRoute(
                    onBack = { navController.backFrom(entry) },
                    onOpenAnswer = { messageId, title ->
                        navController.navigateFrom(entry, Route.DebugAnswer(sessionId, messageId, title))
                    },
                )
            }
            composable<Route.DebugAnswer> { entry -> DebugAnswerRoute(onBack = { navController.backFrom(entry) }) }
        }
    }
}
