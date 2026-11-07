package com.konecto.series76.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes. */
sealed interface Route {
    @Serializable data object Login : Route

    /** "Motion · Transition 1–7": animated hand-off from Login to the chat list. */
    @Serializable data object Transition : Route

    @Serializable data object Chats : Route

    /** sessionId null = new conversation (its id is created locally when the first message is sent). */
    @Serializable data class Conversation(val sessionId: String? = null) : Route

    @Serializable data object DebugChats : Route

    @Serializable data class DebugAnswers(val sessionId: String, val chatLabel: String) : Route

    @Serializable data class DebugAnswer(val sessionId: String, val messageId: String, val title: String) : Route
}
