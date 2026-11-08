package me.rerere.localdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sizes were read off the HF tree API on 2026-11-08; a re-upload must fail a build (house rule). */
class LocalDreamCatalogTest {

    @Test fun sizesAreExactBytes() {
        val byId = LocalDreamCatalog.models.associate { it.id to it.sizeBytes }
        assertEquals(1_069_856_038L, byId["chilloutmix-8gen2"])
        assertEquals(1_054_661_172L, byId["absolutereality-8gen2"])
        assertEquals(1_032_290_626L, byId["dreamshaperv8-8gen2"])
        assertEquals(1_203_917_293L, byId["chilloutmix-mnn"])
    }

    @Test fun urlsAreHfResolveEndpoints() {
        LocalDreamCatalog.models.forEach {
            assertTrue("bad url ${it.url}", it.url.startsWith("https://huggingface.co/xororz/"))
            assertTrue("must be a resolve link, not a page", it.url.contains("/resolve/main/"))
        }
    }

    @Test fun flagshipQnnOnly() {
        // This build ships the flagship SoC-family compiles plus one MNN fallback; no
        // 8gen1/min entries without a tested device for them.
        LocalDreamCatalog.models.filter { it.packKind == "qnn" }.forEach {
            assertTrue("qnn packs must be the 8gen2 family: ${it.fileName}", it.fileName.endsWith("_8gen2.zip"))
        }
    }

    @Test fun lookupHelpers() {
        assertEquals("chilloutmix-8gen2", LocalDreamCatalog.entryFor("ChilloutMix_qnn2.28_8gen2.zip")?.id)
        assertEquals("chilloutmix-mnn", LocalDreamCatalog.entryById("chilloutmix-mnn")?.packKind)
        assertNull(LocalDreamCatalog.entryById("nope"))
    }

    @Test fun modelRowsAreImageTyped() {
        val model = LocalDreamCatalog.modelFor(LocalDreamCatalog.models.first())
        assertEquals(me.rerere.ai.provider.ModelType.IMAGE, model.type)
        assertEquals(listOf(me.rerere.ai.provider.Modality.IMAGE), model.outputModalities)
        assertEquals("chilloutmix-8gen2", model.modelId) // directory-keyed, not file-keyed
    }
}
