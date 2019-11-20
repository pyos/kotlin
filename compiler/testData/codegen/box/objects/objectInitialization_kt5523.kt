// IGNORE_BACKEND_FIR: JVM_IR
object A {
    val a = "OK"
    val b = A.a
}

fun box() = A.b
