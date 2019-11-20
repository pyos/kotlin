// IGNORE_BACKEND_FIR: JVM_IR
// KT-5159

object Test {
    val a = "OK"
}

fun box(): String = Test?.a
