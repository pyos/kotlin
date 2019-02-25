// IGNORE_BACKEND: JS, JS_IR, NATIVE
// WITH_REFLECT

// FILE: typeOf.kt
// TODO: remove this in 1.4
package kotlin.reflect
inline fun <reified T> typeOf(): KType = null!!

// FILE: test.kt

package test

import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.assertEquals

class C

fun check(expected: String, actual: KType) {
    assertEquals(expected, actual.toString())
}

fun box(): String {
    check("kotlin.Any", typeOf<Any>())
    check("kotlin.String", typeOf<String>())
    check("kotlin.String?", typeOf<String?>())
    check("kotlin.Unit", typeOf<Unit>())

    check("test.C", typeOf<C>())
    check("test.C?", typeOf<C?>())

    check("kotlin.collections.List<kotlin.String>", typeOf<List<String>>())
    check("kotlin.collections.Map<in kotlin.Number, kotlin.Any?>?", typeOf<Map<in Number, *>?>())
    check("kotlin.Enum<out kotlin.Enum<*>>", typeOf<Enum<*>>())
    check("kotlin.Enum<kotlin.annotation.AnnotationRetention>", typeOf<Enum<AnnotationRetention>>())

    check("kotlin.Array<kotlin.Any>", typeOf<Array<Any>>())
    check("kotlin.Array<out kotlin.Any?>", typeOf<Array<*>>())
    check("kotlin.Array<kotlin.IntArray>", typeOf<Array<IntArray>>())
    check("kotlin.Array<in kotlin.Array<test.C>?>", typeOf<Array<in Array<C>?>>())

    check("kotlin.Int", typeOf<Int>())
    check("kotlin.Int?", typeOf<Int?>())
    check("kotlin.Boolean", typeOf<Boolean>())

    return "OK"
}
