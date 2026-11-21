package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.CargoCheckParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoCheckParserTest {

    /** Shape of the real E0596 message measured on the phone (trimmed). */
    private val e0596 = """{"reason":"compiler-message","package_id":"x","manifest_path":"/p/Cargo.toml","target":{"name":"p"},"message":{"rendered":"error[E0596]: ...","$""" +
        """message_type":"diagnostic","children":[{"children":[],"code":null,"level":"help","message":"consider changing this to be mutable","rendered":null,"spans":[{"byte_end":34,"byte_start":34,"column_end":9,"column_start":9,"expansion":null,"file_name":"src/main.rs","is_primary":true,"label":null,"line_end":4,"line_start":4,"suggested_replacement":"mut ","suggestion_applicability":"MachineApplicable","text":[]}]}],"code":{"code":"E0596","explanation":"..."},"level":"error","message":"cannot borrow `guess` as mutable, as it is not declared as mutable","spans":[{"byte_end":100,"byte_start":90,"column_end":30,"column_start":20,"expansion":null,"file_name":"src/main.rs","is_primary":true,"label":"cannot borrow as mutable","line_end":6,"line_start":6,"suggested_replacement":null,"suggestion_applicability":null,"text":[]}]}}"""

    @Test
    fun `parses the mutable-borrow error with its quick fix`() {
        val d = CargoCheckParser.parseLine(e0596)!!
        assertEquals("src/main.rs", d.file)
        assertEquals(6, d.lineStart); assertEquals(20, d.colStart)
        assertEquals(6, d.lineEnd); assertEquals(30, d.colEnd)
        assertTrue(d.isError)
        assertEquals("E0596", d.code)
        assertEquals("cannot borrow as mutable", d.label)
        assertEquals(1, d.fixes.size)
        val fix = d.fixes[0]
        assertEquals("Consider changing this to be mutable", fix.title)
        val e = fix.edits.single()
        assertEquals(4, e.lineStart); assertEquals(9, e.colStart)
        assertEquals(4, e.lineEnd); assertEquals(9, e.colEnd)
        assertEquals("mut ", e.replacement)
    }

    @Test
    fun `warning without a fix keeps its help text as a note`() {
        val line = """{"reason":"compiler-message","message":{"level":"warning","message":"unused variable: `x`","code":{"code":"unused_variables"},"spans":[{"file_name":"src/main.rs","is_primary":true,"line_start":3,"line_end":3,"column_start":9,"column_end":10,"label":null}],"children":[{"level":"note","message":"`#[warn(unused_variables)]` on by default","spans":[]}]}}"""
        val d = CargoCheckParser.parseLine(line)!!
        assertFalse(d.isError)
        assertTrue(d.fixes.isEmpty())
        assertEquals(listOf("note: `#[warn(unused_variables)]` on by default"), d.notes)
    }

    @Test
    fun `placeholder suggestions are not offered as buttons`() {
        val line = """{"reason":"compiler-message","message":{"level":"error","message":"e","code":null,"spans":[{"file_name":"src/main.rs","is_primary":true,"line_start":1,"line_end":1,"column_start":1,"column_end":2}],"children":[{"level":"help","message":"try this","spans":[{"file_name":"src/main.rs","is_primary":true,"line_start":1,"line_end":1,"column_start":1,"column_end":2,"suggested_replacement":"/* value */","suggestion_applicability":"HasPlaceholders"}]}]}}"""
        val d = CargoCheckParser.parseLine(line)!!
        assertTrue(d.fixes.isEmpty())
    }

    @Test
    fun `summaries, artifacts and non-json lines are ignored`() {
        val abort = """{"reason":"compiler-message","message":{"level":"error","message":"aborting due to 1 previous error","code":null,"spans":[],"children":[]}}"""
        val artifact = """{"reason":"compiler-artifact","package_id":"x"}"""
        assertNull(CargoCheckParser.parseLine(abort))
        assertNull(CargoCheckParser.parseLine(artifact))
        assertNull(CargoCheckParser.parseLine("   Compiling p v0.1.0"))
        assertNull(CargoCheckParser.parseLine("{not json"))
    }

    @Test
    fun `spans inside std are dropped`() {
        val line = """{"reason":"compiler-message","message":{"level":"error","message":"e","code":null,"spans":[{"file_name":"/rustc/abc/library/core/src/x.rs","is_primary":true,"line_start":1,"line_end":1,"column_start":1,"column_end":2}],"children":[]}}"""
        assertNull(CargoCheckParser.parseLine(line))
    }
}
