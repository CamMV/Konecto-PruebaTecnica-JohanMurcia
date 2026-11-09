package com.konecto.series76.ui.feature.debug

import com.konecto.series76.domain.model.AnswerStatus
import com.konecto.series76.domain.model.ChatMessage
import com.konecto.series76.domain.model.DebugSession
import com.konecto.series76.domain.model.Role
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugListsTest {
    private val ana = User("u-ana", "ana@konecto.test", "Ana Torres", isAdmin = true)
    private val luis = User("u-luis", "luis@konecto.test", "Luis Gómez")
    private val beto = User("u-beto", "beto@konecto.test", "beto Ruiz")

    private fun session(id: String, owner: User) = DebugSession(id, "title $id", 0L, 2, owner)

    @Test fun `chats are grouped by user, logged-in user first, then by name`() {
        val sessions = listOf(session("s1", luis), session("s2", ana), session("s3", luis), session("s4", beto))
        val groups = groupByUser(sessions, meId = "u-luis")
        assertEquals(listOf("Luis Gómez", "Ana Torres", "beto Ruiz"), groups.map { it.user.name })
        assertEquals(listOf(true, false, false), groups.map { it.isMe })
        // Server order kept inside a group, numbered per user.
        assertEquals(listOf("s1" to "Chat 1", "s3" to "Chat 2"), groups[0].chats.map { it.id to it.label })
    }

    private fun msg(id: String, role: Role, text: String, state: SendState = SendState.SENT) = ChatMessage(
        id, "s1", role, text, if (role == Role.ASSISTANT) AnswerStatus.ANSWERED else null,
        emptyList(), emptyList(), 0L, state, null, null,
    )

    @Test fun `each answer is paired with the question before it`() {
        val items = answerItems(
            listOf(
                msg("a0", Role.ASSISTANT, "orphan"),
                msg("q1", Role.USER, "Torque of 761A?"),
                msg("a1", Role.ASSISTANT, "It is **700 in-lbs**."),
                msg("q2", Role.USER, "and power?"),
                msg("a2", Role.ASSISTANT, "pending", SendState.PENDING),
            ),
        )
        assertEquals(listOf("a0", "a1"), items.map { it.id })
        assertNull(items[0].question)
        assertEquals("Torque of 761A?", items[1].question)
        assertEquals("It is 700 in-lbs.", items[1].answerPreview)
        assertEquals("Ans 2", items[1].label)
    }
}
