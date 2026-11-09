package com.friday.ai.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCatalogTest {
    @Test fun `catalog has only the two reviewed lightweight artifacts`() {
        val models = LocalModelCatalog.recommended
        assertEquals(2, models.size)
        assertEquals("smollm2-135m-q4-k-m", models[0].id)
        assertEquals(105_454_432L, models[0].expectedBytes)
        assertEquals("qwen2.5-0.5b-q4-0", models[1].id)
        assertEquals(428_730_208L, models[1].expectedBytes)
    }

    @Test fun `each catalog download is pinned and hash verified`() {
        LocalModelCatalog.recommended.forEach { model ->
            assertTrue(model.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(model.expectedSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(model.downloadUrl.contains("/resolve/${model.revision}/"))
            assertTrue(model.fileName.endsWith(".gguf"))
        }
    }
}
