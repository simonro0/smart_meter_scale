package de.simonroder.smartmeterscale.ocr

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OcrValueParserTest {

    private lateinit var parser: OcrValueParser

    @Before
    fun setUp() {
        parser = OcrValueParser()
    }

    @Test
    fun `parses weight body fat and body water from clean output`() {
        val result = parser.parse("68.1 kg\n14.6 %\n60.5 %")
        assertNotNull(result)
        assertEquals(68.1, result!!.weightKg, 0.01)
        assertEquals(14.6, result.bodyFatPercent!!, 0.01)
        assertEquals(60.5, result.bodyWaterPercent!!, 0.01)
    }

    @Test
    fun `parses weight with comma decimal separator`() {
        val result = parser.parse("68,1 kg\n14,6 %\n60,5 %")
        assertNotNull(result)
        assertEquals(68.1, result!!.weightKg, 0.01)
        assertEquals(14.6, result.bodyFatPercent!!, 0.01)
    }

    @Test
    fun `parses weight only when no percentages present`() {
        val result = parser.parse("72.3 kg")
        assertNotNull(result)
        assertEquals(72.3, result!!.weightKg, 0.01)
        assertNull(result.bodyFatPercent)
        assertNull(result.bodyWaterPercent)
    }

    @Test
    fun `returns null when no weight found`() {
        assertNull(parser.parse("14.6 %\n60.5 %"))
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(parser.parse(""))
    }

    @Test
    fun `handles ocr noise around values`() {
        val result = parser.parse("F\n14.6 W\n68.1kg\n60.5 %\n|")
        assertNotNull(result)
        assertEquals(68.1, result!!.weightKg, 0.01)
        assertEquals(14.6, result.bodyFatPercent!!, 0.01)
        assertEquals(60.5, result.bodyWaterPercent!!, 0.01)
    }

    @Test
    fun `handles kg without space`() {
        val result = parser.parse("68.1kg")
        assertNotNull(result)
        assertEquals(68.1, result!!.weightKg, 0.01)
    }

    @Test
    fun `parses integer weight`() {
        val result = parser.parse("70 kg")
        assertNotNull(result)
        assertEquals(70.0, result!!.weightKg, 0.01)
    }

    @Test
    fun `distinguishes body fat from body water by value range`() {
        val result = parser.parse("70.0 kg\n24.5 %\n58.3 %")
        assertNotNull(result)
        assertEquals(24.5, result!!.bodyFatPercent!!, 0.01)
        assertEquals(58.3, result.bodyWaterPercent!!, 0.01)
    }

    @Test
    fun `case insensitive kg matching`() {
        val result = parser.parse("68.1 KG")
        assertNotNull(result)
        assertEquals(68.1, result!!.weightKg, 0.01)
    }

    @Test
    fun `filters out physical button labels from scale case`() {
        val result = parser.parse("F\n14.6 %\n68.1 kg\n60.5 %\nON/OFF\nUP\nDOWN\nSET")
        assertNotNull(result)
        assertEquals(68.1, result!!.weightKg, 0.01)
        assertEquals(14.6, result.bodyFatPercent!!, 0.01)
        assertEquals(60.5, result.bodyWaterPercent!!, 0.01)
    }

    @Test
    fun `extracts fat from standalone F label followed by number`() {
        val result = parser.parse("F\n14.6\n68.1 kg\n60.5 %")
        assertNotNull(result)
        assertEquals(14.6, result!!.bodyFatPercent!!, 0.01)
    }

    @Test
    fun `extracts water from standalone W label followed by number`() {
        val result = parser.parse("68.1 kg\n14.6 %\nW\n60.5")
        assertNotNull(result)
        assertEquals(60.5, result!!.bodyWaterPercent!!, 0.01)
    }

    @Test
    fun `parseMeterValue filters button labels and returns max number`() {
        val result = parser.parseMeterValue("1234.5\nON/OFF\nUP\nDOWN\nSET\n0")
        assertEquals(1234.5, result!!, 0.01)
    }
}
