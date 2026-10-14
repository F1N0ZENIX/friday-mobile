package com.friday.ai.core.people

import com.friday.ai.core.ContactsReader
import com.friday.ai.core.DeviceContext
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonChoiceTest {

    private fun person(name: String) = Person(name, listOf(PhoneNumber("+10000000000", "+10000000000", "US")))

    private val yakubov = person("Firdavs Yakubov")
    private val gettysburg = person("Firdavs Gettysburg")
    private val both = listOf(yakubov, gettysburg)

    @Test
    fun `name and surname spelled in cyrillic pick the one contact`() {
        // Whisper writes what it hears; the phone book is in Latin letters.
        val r = PeopleResolver.resolve("Фердавс Гетисбург", both)
        assertEquals(PeopleResolver.Result.Found(gettysburg), r)
    }

    @Test
    fun `the first name alone still asks which one`() {
        assertTrue(PeopleResolver.resolve("Фердавсу", both) is PeopleResolver.Result.Ambiguous)
    }

    @Test
    fun `an answer to which one is matched against those named only`() {
        assertEquals(gettysburg, PeopleDirectory.pick("Фердавс, Гетисбург.", both))
        assertEquals(gettysburg, PeopleDirectory.pick("Гетисбург", both))
        assertEquals(yakubov, PeopleDirectory.pick("Якубов", both))
        assertEquals(gettysburg, PeopleDirectory.pick("второй", both))
        assertEquals(yakubov, PeopleDirectory.pick("первого", both))
        assertNull("names neither", PeopleDirectory.pick("включи музыку", both))
        assertNull("names both equally", PeopleDirectory.pick("Фердавс", both))
    }

    @Test
    fun `the answer is carried out once, and only soon after the question`() = runTest {
        var now = 0L
        val contacts = mockk<ContactsReader>(relaxed = true)
        val device = mockk<DeviceContext>(relaxed = true)
        val people = PeopleDirectory(contacts, device) { now }
        val sent = mutableListOf<String>()

        people.ask(both) { sent += it.name; "Готово для ${it.name}" }
        assertEquals("Готово для Firdavs Gettysburg", people.answerPending("Гетисбург"))
        assertNull("one answer only", people.answerPending("Гетисбург"))

        people.ask(both) { sent += it.name; "x" }
        assertNull("something else closes the question", people.answerPending("включи музыку"))
        assertNull(people.answerPending("Якубов"))

        people.ask(both) { sent += it.name; "x" }
        now += PeopleDirectory.CHOICE_TTL_MS + 1
        assertNull("too late", people.answerPending("Якубов"))
        assertEquals(listOf("Firdavs Gettysburg"), sent)
    }
}
