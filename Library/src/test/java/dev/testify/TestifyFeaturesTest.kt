package dev.testify

import android.content.Context
import dev.testify.TestifyFeatures.ExampleDisabledFeature
import dev.testify.TestifyFeatures.ExampleFeature
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TestifyFeaturesTest {

    @After
    @Before
    fun setUp() {
        ExampleFeature.reset()
        ExampleDisabledFeature.reset()
    }

    @Test
    fun `Features can default to enabled`() {
        assertTrue(ExampleFeature.isEnabled())
    }

    @Test
    fun `Features can default to disabled`() {
        assertFalse(ExampleDisabledFeature.isEnabled())
    }

    @Test
    fun `Can use setEnabled to override a disabled feature`() {
        ExampleDisabledFeature.setEnabled(true)
        assertTrue(ExampleDisabledFeature.isEnabled())
    }

    @Test
    fun `Can use setEnabled to override an enabled feature`() {
        ExampleFeature.setEnabled(false)
        assertFalse(ExampleFeature.isEnabled())
    }

    private fun mockContext(tag: String, enabled: Boolean): Context {
        mockkStatic(::getMetaDataBundle)
        every { getMetaDataBundle(any()) } returns mockk {
            every { this@mockk.containsKey(any()) } returns false
            every { this@mockk.containsKey(tag) } returns true
            every { this@mockk.getBoolean(tag) } returns enabled
        }
        return mockk()
    }

    @Test
    fun `Can use AndroidManifest to override an enabled feature`() {
        assertFalse(ExampleFeature.isEnabled(mockContext(ExampleFeature.tags.first(), enabled = false)))
    }

    @Test
    fun `Can use AndroidManifest to override a disabled feature`() {
        assertTrue(ExampleDisabledFeature.isEnabled(mockContext(ExampleDisabledFeature.tags.first(), enabled = true)))
    }

    @Test
    fun `Can use setEnabled to override a manifest change`() {
        ExampleFeature.setEnabled(enabled = true)
        assertTrue(ExampleFeature.isEnabled(mockContext(ExampleFeature.tags.first(), enabled = false)))
    }

    @Test
    fun `Can reset an enabled feature`() {
        ExampleFeature.setEnabled(false)
        TestifyFeatures.reset()
        assertTrue(ExampleFeature.isEnabled())
    }

    @Test
    fun `Can reset a disabled feature`() {
        ExampleDisabledFeature.setEnabled(true)
        TestifyFeatures.reset()
        assertFalse(ExampleDisabledFeature.isEnabled())
    }

    @Test
    fun `Can not use a random name`() {
        assertTrue(ExampleFeature.isEnabled(mockContext("random-tag", enabled = false)))
    }

    @Test
    fun `Can use an alias in the AndroidManifest to enable a feature`() {
        assertFalse(ExampleFeature.isEnabled(mockContext("testify-alias", enabled = false)))
    }
}
