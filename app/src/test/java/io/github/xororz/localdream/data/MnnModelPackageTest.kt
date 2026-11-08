package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnModelPackageTest {
    @Test fun acceptCompleteOriginalFormatLayout() {
        assertTrue(MnnModelPackage.containsRequired(MnnModelPackage.requiredFiles))
        assertFalse(MnnModelPackage.containsRequired(setOf("unet.mnn", "clip_v2.mnn")))
        assertFalse(MnnModelPackage.containsRequired(setOf("model.safetensors")))
    }

    @Test fun onlyAllowExplicitMnnLayoutWithinSafeArchiveDirectories() {
        assertEquals("unet.mnn", MnnModelPackage.safePackageFile("model/unet.mnn"))
        assertEquals("tokenizer.json", MnnModelPackage.safePackageFile("tokenizer.json"))
        for (bad in listOf(
            "../unet.mnn", "/unet.mnn", "m/../unet.mnn",
            "m\\unet.mnn", "C:/model/unet.mnn", "unet.ckpt",
            "model.safetensors", "unet.mnn/other", "./unet.mnn",
        )) {
            assertNull(bad, MnnModelPackage.safePackageFile(bad))
        }
    }

    @Test fun sanitizeIdWithoutPathAccessOrReservedExtension() {
        assertEquals("My_Model", MnnModelPackage.cleanId("My Model.zip"))
        assertEquals("Model", MnnModelPackage.cleanId("../../Model.zip"))
        assertTrue(MnnModelPackage.cleanId("x".repeat(200)).length <= 48)
        assertEquals("", MnnModelPackage.cleanId("__.zip"))
    }
}
