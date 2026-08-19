package de.simonroder.smartmeterscale.ocr

import de.simonroder.smartmeterscale.data.ScaleReading
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReadingValidatorTest {

    private lateinit var validator: ReadingValidator

    @Before
    fun setUp() {
        validator = ReadingValidator()
    }

    @Test
    fun `valid reading passes validation`() {
        assertTrue(validator.validate(ScaleReading(68.1, 14.6, 60.5)) is ValidationResult.Valid)
    }

    @Test
    fun `reading with only weight is valid`() {
        assertTrue(validator.validate(ScaleReading(72.0, null, null)) is ValidationResult.Valid)
    }

    @Test
    fun `boundary minimum weight is valid`() {
        assertTrue(validator.validate(ScaleReading(20.0, null, null)) is ValidationResult.Valid)
    }

    @Test
    fun `boundary maximum weight is valid`() {
        assertTrue(validator.validate(ScaleReading(300.0, null, null)) is ValidationResult.Valid)
    }

    @Test
    fun `weight below minimum fails`() {
        val result = validator.validate(ScaleReading(5.0, null, null))
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { "Weight" in it })
    }

    @Test
    fun `weight above maximum fails`() {
        val result = validator.validate(ScaleReading(500.0, null, null))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `body fat above maximum fails`() {
        val result = validator.validate(ScaleReading(70.0, 95.0, null))
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { "Body fat" in it })
    }

    @Test
    fun `body water below minimum fails`() {
        val result = validator.validate(ScaleReading(70.0, null, 10.0))
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { "Body water" in it })
    }

    @Test
    fun `multiple invalid fields reports all errors`() {
        val result = validator.validate(ScaleReading(1.0, 99.0, 5.0))
        assertTrue(result is ValidationResult.Invalid)
        assertEquals(3, (result as ValidationResult.Invalid).errors.size)
    }
}
