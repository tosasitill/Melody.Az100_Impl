package com.tosasitill.az100

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HookCallbacksTest {
    @Test
    fun changedArgumentsReachTheOriginalAndAfterHook() {
        val receiver = Any()
        val arguments = arrayOf<Any?>(1)
        val hook = HookCallbacks().apply {
            before {
                assertSame(receiver, instanceOrNull)
                args[0] = 2
            }
            after {
                assertEquals(2, args[0])
                result = (result as Int) + 1
            }
        }
        assertEquals(5, hook.invoke(receiver, arguments, { (it[0] as Int) * 2 }, { throw it }))
        assertEquals(1, arguments[0])
    }

    @Test
    fun nullResultSkipsVoidOriginalButStillRunsCleanup() {
        var proceeded = false
        var cleanedUp = false
        val hook = HookCallbacks().apply {
            before { result = null }
            after { cleanedUp = true }
        }
        assertEquals(null, hook.invoke(null, emptyArray(), { proceeded = true; null }, { throw it }))
        assertFalse(proceeded)
        assertTrue(cleanedUp)
    }

    @Test
    fun falseResultSkipsBooleanOriginal() {
        val hook = HookCallbacks().apply { before { result = false } }
        assertEquals(false, hook.invoke(null, emptyArray(), { fail("original called"); true }, { throw it }))
    }

    @Test
    fun originalExceptionRunsAfterCleanupAndReachesTheCaller() {
        val exception = IllegalStateException("callee failure")
        var cleanedUp = false
        val hook = HookCallbacks().apply {
            after {
                assertTrue(hasThrowable)
                assertSame(exception, throwable)
                cleanedUp = true
            }
        }
        try {
            hook.invoke(null, emptyArray(), { throw exception }, { throw it })
            fail("callee exception swallowed")
        } catch (t: IllegalStateException) {
            assertSame(exception, t)
        }
        assertTrue(cleanedUp)
    }

    @Test
    fun afterHookCanRecoverFromACalleeException() {
        val hook = HookCallbacks().apply { after { if (hasThrowable) result = false } }
        assertEquals(false, hook.invoke(null, emptyArray(), { throw NullPointerException() }, { throw it }))
    }

    @Test
    fun failedBeforeHookRestoresArgumentsAndCallsTheOriginalOnce() {
        var calls = 0
        var reported = 0
        val hook = HookCallbacks().apply {
            before {
                args[0] = 2
                result = false
                throw IllegalArgumentException("bad hook")
            }
        }
        assertEquals(1, hook.invoke(null, arrayOf<Any?>(1), { calls++; it[0] }, { reported++ }))
        assertEquals(1, calls)
        assertEquals(1, reported)
    }

    @Test
    fun failedAfterHookKeepsTheOriginalResult() {
        var reported = 0
        val hook = HookCallbacks().apply {
            after {
                result = 0
                throw IllegalStateException("bad after hook")
            }
        }
        assertEquals(42, hook.invoke(null, emptyArray(), { 42 }, { reported++ }))
        assertEquals(1, reported)
    }

    @Test
    fun failedAfterHookKeepsTheOriginalException() {
        val exception = IllegalStateException("callee failure")
        val hook = HookCallbacks().apply {
            after {
                result = false
                throw IllegalArgumentException("bad after hook")
            }
        }
        try {
            hook.invoke(null, emptyArray(), { throw exception }, {})
            fail("callee exception swallowed")
        } catch (t: IllegalStateException) {
            assertSame(exception, t)
        }
    }
}
