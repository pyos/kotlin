// TARGET_BACKEND: JVM
// IGNORE_BACKEND_FIR: JVM_IR
// WITH_RUNTIME
// FILE: box.kt
fun box(): String = Z.result

// FILE: z.kt
object Z {
    @JvmField
    val result: String = "OK"
}
