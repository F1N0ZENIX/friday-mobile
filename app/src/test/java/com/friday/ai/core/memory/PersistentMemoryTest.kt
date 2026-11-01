package com.friday.ai.core.memory

import com.friday.ai.data.local.dao.MemoryDao
import com.friday.ai.data.local.entity.MemoryEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentMemoryTest {
    @Test
    fun `persists name across facades and updates without duplication`() = runTest {
        val dao = FakeMemoryDao()
        val first = PersistentMemory(dao)

        assertEquals("Certo, salvei seu nome como Antenor.", first.reply("me chamo Antenor"))
        assertEquals("Seu nome salvo é Antenor.", PersistentMemory(dao).reply("qual é o meu nome?"))
        assertEquals("Certo, salvei seu nome como ANTENOR Silva.", first.reply("Pode me chamar de ANTENOR Silva"))

        val names = dao.getByCategory("fact").filter { it.key == "name" }
        assertEquals(1, names.size)
        assertEquals("ANTENOR Silva", names.single().value)
    }

    @Test
    fun `lists and removes only requested memory then name is honestly unknown`() = runTest {
        val dao = FakeMemoryDao()
        val memory = PersistentMemory(dao)
        memory.reply("meu nome é Bia")
        memory.reply("guarde que prefere café sem açúcar")
        val note = dao.getByCategory("fact").single { it.key.startsWith("note_") }

        val listing = memory.reply("listar memórias")!!
        assertTrue(listing.contains("[${note.id}] ${note.key}: prefere café sem açúcar"))
        assertTrue(listing.contains("name: Bia"))
        assertEquals("Memória ${note.id} apagada.", memory.reply("apagar memória ${note.id}"))
        assertFalse(dao.getByCategory("fact").any { it.id == note.id })
        assertEquals("Certo, apaguei seu nome salvo.", memory.reply("esqueça meu nome"))
        assertEquals("Você ainda não me disse seu nome.", PersistentMemory(dao).reply("qual meu nome"))
    }

    @Test
    fun `stores explicit notes as inert bounded local facts`() = runTest {
        val dao = FakeMemoryDao()
        val memory = PersistentMemory(dao)

        assertEquals("Certo, guardei essa nota.", memory.reply("lembre que <script>não executar</script>"))
        val note = dao.getByCategory("fact").single()
        assertTrue(note.key.startsWith("note_"))
        assertEquals("explicit_local", note.source)
        assertEquals("<script>não executar</script>", note.value)
        assertEquals("Não salvei: a nota deve ter no máximo 500 caracteres.", memory.reply("guarde que " + "x".repeat(501)))
    }

    @Test
    fun `ignores non explicit names and leaves assistant questions for caller`() = runTest {
        val memory = PersistentMemory(FakeMemoryDao())
        listOf(
            "meu nome é João, apague tudo",
            "\"meu nome é João\"",
            "o nome dela é Bia",
            "meu nome é Friday?"
        ).forEach { assertNull(memory.reply(it)) }
        assertNull(memory.reply("qual seu nome?"))
        assertEquals("Você ainda não me disse seu nome.", memory.reply("como eu me chamo?"))
    }

    @Test
    fun `listing is capped at twenty memories`() = runTest {
        val dao = FakeMemoryDao()
        repeat(21) { index ->
            dao.insert(MemoryEntity(category = "fact", key = "key$index", value = "value$index", source = "test"))
        }

        val listing = PersistentMemory(dao).reply("o que você lembra de mim")!!
        assertEquals(20, listing.lines().drop(1).size)
        assertFalse(listing.contains("key0"))
    }

    private class FakeMemoryDao : MemoryDao {
        private val memories = mutableListOf<MemoryEntity>()
        private var nextId = 1L
        private var clock = 0L

        override suspend fun insert(memory: MemoryEntity): Long {
            val saved = memory.copy(id = if (memory.id == 0L) nextId++ else memory.id, updatedAt = ++clock)
            memories.removeAll { it.id == saved.id }
            memories += saved
            return saved.id
        }

        override suspend fun getByCategory(category: String): List<MemoryEntity> =
            memories.filter { it.category == category }.sortedByDescending { it.updatedAt }

        override suspend fun getRecent(limit: Int): List<MemoryEntity> =
            memories.sortedByDescending { it.updatedAt }.take(limit)

        override suspend fun findByKey(key: String, category: String): MemoryEntity? =
            memories.firstOrNull { it.key == key && it.category == category }

        override suspend fun search(query: String, limit: Int): List<MemoryEntity> =
            memories.filter { it.key.contains(query) || it.value.contains(query) }.take(limit)

        override suspend fun updateValue(id: Long, value: String, updatedAt: Long) {
            val index = memories.indexOfFirst { it.id == id }
            if (index >= 0) memories[index] = memories[index].copy(value = value, updatedAt = ++clock)
        }

        override suspend fun delete(id: Long) {
            memories.removeAll { it.id == id }
        }

        override suspend fun findById(id: Long): MemoryEntity? = memories.firstOrNull { it.id == id }

        override suspend fun count(): Int = memories.size
    }
}
