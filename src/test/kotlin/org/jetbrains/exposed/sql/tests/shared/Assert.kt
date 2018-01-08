package org.jetbrains.exposed.sql.tests.shared

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.jetbrains.exposed.sql.vendors.currentDialectIfAvailable
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFails

private fun<T> assertEqualCollectionsImpl(collection : Collection<T>, expected : Collection<T>) {
    assertEquals (expected.size, collection.size, "Count mismatch on ${currentDialect.name}")
    for (p in collection) {
        assert(expected.any {p == it}) { "Unexpected element in collection pair $p on ${currentDialect.name}" }
    }
}

fun<T> assertEqualCollections (collection : Collection<T>, expected : Collection<T>) {
    assertEqualCollectionsImpl(collection, expected)
}

fun<T> assertEqualCollections (collection : Collection<T>, vararg expected : T) {
    assertEqualCollectionsImpl(collection, expected.toList())
}

fun<T> assertEqualCollections (collection : Iterable<T>, vararg expected : T) {
    assertEqualCollectionsImpl(collection.toList(), expected.toList())
}

fun<T> assertEqualCollections (collection : Iterable<T>, expected : Collection<T>) {
    assertEqualCollectionsImpl(collection.toList(), expected)
}

fun<T> assertEqualLists (l1: List<T>, l2: List<T>) {
    assertEquals(l1.size, l2.size, "Count mismatch on ${currentDialectIfAvailable?.name.orEmpty()}")
    for (i in 0 until l1.size)
        assertEquals(l1[i], l2[i], "Error at pos $i on ${currentDialectIfAvailable?.name.orEmpty()}:")
}

fun<T> assertEqualLists (l1: List<T>, vararg expected : T) {
    assertEqualLists(l1, expected.toList())
}

fun assertEqualDateTime(d1: Date?, d2: Date?) {
    when{
        d1 == null && d2 == null -> return
        d1 == null && d2 != null -> error("d1 is null while d2 is not on ${currentDialect.name}")
        d2 == null -> error ("d1 is not null while d2 is null on ${currentDialect.name}")
        d1 == null -> error("Impossible")
        // Mysql doesn't support millis prior 5.6.4
        (currentDialect as? MysqlDialect)?.isFractionDateTimeSupported() == false ->
            assertEquals(d1.time / 1000, d2.time / 1000,  "Failed on ${currentDialect.name}")
        else -> assertEquals(d1.time, d2.time,   "Failed on ${currentDialect.name}")
    }
}

fun Transaction.assertFailAndRollback(message: kotlin.String, block: () -> Unit) {
    commit()
    assertFails("Failed on ${currentDialect.name}. $message") {
        block()
        commit()
    }

    rollback()
}

fun equalDateTime(d1: Date?, d2: Date?) = try {
    assertEqualDateTime(d1, d2)
    true
} catch (e: Exception) {
    false
}
