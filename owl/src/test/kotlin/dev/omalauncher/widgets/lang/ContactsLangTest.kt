package dev.omalauncher.widgets.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContactsLangTest {
    private fun error(source: String): String =
        try {
            Compiler.compile(source)
            fail("expected a compile error")
            ""
        } catch (e: CompileError) {
            e.message.orEmpty()
        }

    private val header = "widget { meta { owl: 25 } source c = android.contacts "

    @Test fun sourceAndUserActionsCompile() {
        val source = header + """
            state query = ""
            Input { text: query; onChange: { query = value; contacts("search", value) } }
            Button { text: "allow"; onTap: contacts("request") }
            Button { text: "call"; onTap: contacts("dial", "+1 555-1234") }
            Button { text: "text"; onTap: contacts("text", "5551234") }
            Button { text: "details"; onTap: contacts("open", "123") }
        }""".trimIndent()
        val program = Compiler.compile(source)
        assertEquals("android.contacts", (program.globals.single { it is Global.Source } as Global.Source).kind)
        assertTrue(program.commands.any { it.contains("matching phone contacts") })
    }

    @Test fun requiresOwl25() {
        assertTrue(error("widget { meta { owl: 24 } source c = android.contacts Text { text: c.query } }").contains("owl 25"))
    }

    @Test fun requestRequiresSourceAndTap() {
        assertTrue(error("widget { meta { owl: 25 } Button { text: \"ask\"; onTap: contacts(\"request\") } }").contains("android.contacts"))
        assertTrue(error(header + "Text { text: \"ask\"; onPress: contacts(\"request\") } }").contains("onTap"))
        assertTrue(error(header + "on c { contacts(\"request\") } Text { text: c.query } }").contains("trigger"))
    }

    @Test fun rejectsWrongArityAndBackgroundActions() {
        assertTrue(error(header + "Text { text: \"x\"; onTap: contacts(\"search\") } }").contains("contacts(\"search\", query)"))
        assertTrue(error(header + "on c { contacts(\"dial\", \"123\") } Text { text: c.query } }").contains("trigger"))
    }
}
