package org.junit

annotation class Test
annotation class Before
annotation class After
annotation class Ignore
annotation class BeforeClass
annotation class AfterClass

object Assert {
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$message expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertEquals(expected: Int, actual: Int) {
        if (expected != actual) throw AssertionError("expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertEquals(expected: Long, actual: Long) {
        if (expected != actual) throw AssertionError("expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("expected:<$expected> but was:<$actual>")
    }
    @JvmStatic fun assertTrue(condition: Boolean) {
        if (!condition) throw AssertionError("expected true")
    }
    @JvmStatic fun assertTrue(message: String?, condition: Boolean) {
        if (!condition) throw AssertionError(message ?: "expected true")
    }
    @JvmStatic fun assertFalse(condition: Boolean) {
        if (condition) throw AssertionError("expected false")
    }
    @JvmStatic fun assertFalse(message: String?, condition: Boolean) {
        if (condition) throw AssertionError(message ?: "expected false")
    }
    @JvmStatic fun assertNull(o: Any?) {
        if (o != null) throw AssertionError("expected null but was:<$o>")
    }
    @JvmStatic fun assertNotNull(o: Any?) {
        if (o == null) throw AssertionError("expected non-null")
    }
    @JvmStatic fun assertSame(expected: Any?, actual: Any?) {
        if (expected !== actual) throw AssertionError("expected same:<$expected> but was:<$actual>")
    }
    @JvmStatic fun fail(message: String? = null): Nothing = throw AssertionError(message ?: "fail")
}
