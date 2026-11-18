package dev.omalauncher.widgets.runtime

/** A row from Android's filtered phone index. Kept separate from Android APIs so grouping can be unit-tested. */
internal data class ContactLine(val id: Long, val name: String, val number: String, val label: String)

internal data class ContactNumber(val number: String, val label: String)

internal data class ContactMatch(val id: Long, val name: String, val numbers: List<ContactNumber>)

/** Never search the entire address book for an empty or arbitrarily long query. */
internal fun contactTerm(value: String): String = value.trim().take(80)

/** Only phone-number characters. A URI is built with Uri.fromParts, not by concatenating user data. */
internal fun phoneNumber(value: String): String? {
    val trimmed = value.trim()
    return trimmed.takeIf { it.length in 1..128 && it.any(Char::isDigit) && it.all { c -> c.isDigit() || c in "+() -.,*#;" } }
}

/** One contact per row in OWL; preserve the numbers' labels and filter duplicate provider rows. */
internal fun groupContacts(rows: List<ContactLine>, limit: Int = 20): List<ContactMatch> {
    val matches = LinkedHashMap<Long, Pair<String, MutableList<ContactNumber>>>()
    for (r in rows) {
        if (r.id <= 0) continue
        val number = phoneNumber(r.number) ?: continue
        if (r.id !in matches) {
            if (matches.size >= limit) break
            matches[r.id] = r.name to ArrayList()
        }
        val numbers = matches.getValue(r.id).second
        if (numbers.none { it.number == number }) numbers.add(ContactNumber(number, r.label))
    }
    return matches.map { (id, entry) -> ContactMatch(id, entry.first, entry.second.toList()) }
}
