package dev.omalauncher.widgets.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactSearchTest {
    @Test fun searchRequiresNonBlankInput() {
        assertEquals("", contactTerm("  "))
        assertEquals("Olivia", contactTerm(" Olivia "))
        assertEquals(80, contactTerm("a".repeat(200)).length)
    }

    @Test fun groupsPhoneNumbersAndPreservesLabels() {
        val items = groupContacts(listOf(
            ContactLine(12, "Alex", "+1 555-1234", "mobile"),
            ContactLine(12, "Alex", "+1 555-1234", "mobile"),
            ContactLine(12, "Alex", "555-8765", "work"),
            ContactLine(13, "Sam", "555-2345", "home"),
        ))
        assertEquals(2, items.size)
        assertEquals("Alex", items.first().name)
        assertEquals(listOf("mobile", "work"), items.first().numbers.map { it.label })
        assertEquals(listOf("+1 555-1234", "555-8765"), items.first().numbers.map { it.number })
    }

    @Test fun rejectsMalformedNumbersAndLimitsResults() {
        assertNull(phoneNumber("tel:555-1234"))
        assertNull(phoneNumber("555\n1234"))
        assertNull(phoneNumber("callme"))
        assertNull(phoneNumber(" "))
        assertEquals("*67,5551234", phoneNumber("*67,5551234"))
        val rows = (1L..40L).map { ContactLine(it, "Name $it", "555000$it", "mobile") }
        assertEquals((1L..20L).toList(), groupContacts(rows).map { it.id })
    }
}
