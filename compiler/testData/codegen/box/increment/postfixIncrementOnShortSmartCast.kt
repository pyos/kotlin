// `10` is inferred to be `Int`, smart casting `i` to `Int & Short` instead,
// so there's an ambiguity between `Int.plus` and `Short.plus`.
// IGNORE_BACKEND_FIR: JVM_IR

public fun box() : String {
    var i : Short?
    i = 10
    // Postfix increment on a smart casted short should work
    val j = i++

    return if (j!!.toInt() == 10 && i!!.toInt() == 11) "OK" else "fail j = $j i = $i"
}
