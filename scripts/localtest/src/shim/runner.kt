// Tiny JUnit4-style runner: instantiate each test class argument, invoke its
// @Test methods in declaration order, and report pass/fail.
import java.lang.reflect.Modifier

fun main(args: Array<String>) {
    var pass = 0
    var fail = 0
    val failures = mutableListOf<String>()
    for (name in args) {
        val cls = runCatching { Class.forName(name) }.getOrElse {
            fail++; failures += "$name: class not found: $it"
            null
        } ?: continue
        for (m in cls.declaredMethods) {
            if (!m.isAnnotationPresent(org.junit.Test::class.java)) continue
            if (!Modifier.isPublic(m.modifiers)) {
                fail++; failures += "$name.${m.name}: not public"; continue
            }
            val inst = runCatching { cls.getDeclaredConstructor().newInstance() }.getOrElse { e ->
                fail++; failures += "FAIL $name.${m.name}: construct: $e"
                null
            } ?: continue
            runCatching {
                m.isAccessible = true
                m.invoke(inst)
            }.onFailure { e ->
                fail++
                val cause = e.cause ?: e
                failures += "FAIL $name.${m.name}: $cause"
            }.onSuccess { pass++ }
        }
    }
    println("TESTS pass=$pass fail=$fail")
    failures.forEach { println("  $it") }
    if (fail > 0) kotlin.system.exitProcess(1)
}
