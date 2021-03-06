/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.test.tests

import kotlin.test.*

class BasicAssertionsTest {
    @Test
    fun testAssertEquals() {
        assertEquals(1, 1)
    }

    @Test
    fun testAssertSame() {
        val instance: Any = object {}
        assertSame(instance, instance)
    }

    @Test
    fun testAssertEqualsString() {
        assertEquals("a", "a")
    }

    @Test
    fun testAssertFailsWith() {
        assertFailsWith<IllegalStateException> { throw IllegalStateException() }
        assertFailsWith<AssertionError> { throw AssertionError() }
    }

    @Test
    fun testAssertFailsWithFails() {
        assertTrue(true) // at least one assertion required for qunit

        withDefaultAsserter run@{
            val rootCause = IllegalArgumentException()
            try {
                assertFailsWith<IllegalStateException> { throw rootCause }
            } catch (e: AssertionError) {
                if (e.cause !== rootCause) throw AssertionError("Expected to fail with correct cause")
                return@run
            }
            throw AssertionError("Expected to fail")
        }

        withDefaultAsserter run@{
            try {
                assertFailsWith<IllegalStateException> { }
            } catch (e: AssertionError) {
                return@run
            }
            throw AssertionError("Expected to fail")
        }
    }

    @Test
    fun testAssertFailsWithClass() {
        assertFailsWith(IllegalArgumentException::class) {
            throw IllegalArgumentException("This is illegal")
        }
    }

    @Test
    fun testAssertFailsWithClassFails() {
        val rootCause = IllegalStateException()
        val actual = checkFailedAssertion {
            assertFailsWith(IllegalArgumentException::class) { throw rootCause }
        }
        assertSame(rootCause, actual.cause, "Expected to fail with correct cause")

        checkFailedAssertion {
            assertFailsWith(Exception::class) { }
        }
    }

    @Test
    fun testAssertEqualsFails() {
        checkFailedAssertion { assertEquals(1, 2) }
    }

    @Test
    fun testAssertSameFails() {
        val instance1: Any = object {}
        val instance2: Any = object {}
        checkFailedAssertion { assertSame(instance1, instance2) }
    }

    @Test
    fun testAssertTrue() {
        assertTrue(true)
        assertTrue { true }
    }

    @Test()
    fun testAssertTrueFails() {
        checkFailedAssertion { assertTrue(false) }
        checkFailedAssertion { assertTrue { false } }
    }

    @Test
    fun testAssertFalse() {
        assertFalse(false)
        assertFalse { false }
    }

    @Test
    fun testAssertFalseFails() {
        checkFailedAssertion { assertFalse(true) }
        checkFailedAssertion { assertFalse { true } }
    }

    @Test
    fun testAssertFails() {
        assertFails { throw IllegalStateException() }
    }

    @Test()
    fun testAssertFailsFails() {
        checkFailedAssertion { assertFails { } }
    }


    @Test
    fun testAssertNotEquals() {
        assertNotEquals(1, 2)
    }

    @Test
    fun testAssertNotSame() {
        val instance1: Any = object {}
        val instance2: Any = object {}
        assertNotSame(instance1, instance2)
    }

    @Test()
    fun testAssertNotEqualsFails() {
        checkFailedAssertion { assertNotEquals(1, 1) }
    }

    @Test
    fun testAssertNotSameFails() {
        val instance: Any = object {}
        checkFailedAssertion { assertNotSame(instance, instance) }
    }

    @Test
    fun testAssertNotNull() {
        assertNotNull(true)
    }

    @Test()
    fun testAssertNotNullFails() {
        checkFailedAssertion { assertNotNull<Any>(null) }
    }

    @Test
    fun testAssertNotNullLambda() {
        assertNotNull("") { assertEquals("", it) }
    }

    @Test
    fun testAssertNotNullLambdaFails() {
        checkFailedAssertion {
            val value: String? = null
            assertNotNull(value) {
                it.substring(0, 0)
            }
        }
    }

    @Test
    fun testAssertNull() {
        assertNull(null)
    }

    @Test
    fun testAssertNullFails() {
        checkFailedAssertion { assertNull("") }
    }

    @Test()
    fun testFail() {
        val message = "should fail"
        val actual = checkFailedAssertion { fail(message) }
        assertEquals(message, actual.message)
    }

    @Test
    fun testFailWithCause() {
        val message = "should fail due to"
        val cause = IllegalStateException()
        val actual = checkFailedAssertion { fail(message, cause) }
        assertEquals(message, actual.message)
        assertSame(cause, actual.cause)
    }

    @Test
    fun testExpect() {
        expect(1) { 1 }
    }

    @Test
    fun testExpectFails() {
        checkFailedAssertion { expect(1) { 2 } }
    }
}


internal fun checkFailedAssertion(assertion: () -> Unit): AssertionError {
    return assertFailsWith<AssertionError> { withDefaultAsserter(assertion) }
}

private fun withDefaultAsserter(block: () -> Unit) {
    val current = overrideAsserter(DefaultAsserter)
    try {
        block()
    } finally {
        overrideAsserter(current)
    }
}
