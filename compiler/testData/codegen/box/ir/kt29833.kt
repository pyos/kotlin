// TARGET_BACKEND: JVM
// IGNORE_BACKEND_FIR: JVM_IR
// FILE: Definitions.kt
import interop.*

object Definitions {
    const val KT_CONSTANT = Interface.CONSTANT

    val ktValue = Interface.CONSTANT
}

fun box(): String =
    Definitions.ktValue

// FILE: interop/Interface.java
package interop;

public class Interface {
    public static final String CONSTANT = "OK";
}
