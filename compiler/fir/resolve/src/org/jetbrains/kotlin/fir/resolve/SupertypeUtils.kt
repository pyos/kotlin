/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.expandedConeType
import org.jetbrains.kotlin.fir.declarations.superConeTypes
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.ConeAbbreviatedType
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType

fun lookupSuperTypes(
    klass: FirRegularClass,
    lookupInterfaces: Boolean,
    deep: Boolean
): List<ConeClassLikeType> {
    return mutableListOf<ConeClassLikeType>().also {
        if (lookupInterfaces) klass.symbol.collectSuperTypes(it, deep)
        else klass.symbol.collectSuperClasses(it)
    }
}

private tailrec fun ConeClassLikeType.computePartialExpansion(): ConeClassLikeType? {
    return when (this) {
        is ConeAbbreviatedType -> directExpansion.takeIf { it !is ConeClassErrorType }?.computePartialExpansion()
        else -> return this
    }
}

private tailrec fun ConeClassLikeSymbol.collectSuperClasses(list: MutableList<ConeClassLikeType>) {
    when (this) {
        is FirClassSymbol -> {
            val superClassType =
                fir.superConeTypes
                    .map { it.computePartialExpansion() }
                    .firstOrNull {
                        it !is ConeClassErrorType && (it?.symbol as? FirClassSymbol)?.fir?.classKind == ClassKind.CLASS
                    } ?: return
            list += superClassType
            superClassType.symbol.collectSuperClasses(list)
        }
        is FirTypeAliasSymbol -> {
            val expansion = fir.expandedConeType?.computePartialExpansion() ?: return
            expansion.symbol.collectSuperClasses(list)
        }
        else -> error("?!id:1")
    }
}

private fun ConeClassLikeSymbol.collectSuperTypes(list: MutableList<ConeClassLikeType>, deep: Boolean) {
    when (this) {
        is FirClassSymbol -> {
            val superClassTypes =
                fir.superConeTypes.mapNotNull { it.computePartialExpansion() }
            list += superClassTypes
            if (deep)
                superClassTypes.forEach {
                    if (it !is ConeClassErrorType) {
                        it.symbol.collectSuperTypes(list, deep)
                    }
                }
        }
        is FirTypeAliasSymbol -> {
            val expansion = fir.expandedConeType?.computePartialExpansion() ?: return
            expansion.symbol.collectSuperTypes(list, deep)
        }
        else -> error("?!id:1")
    }
}
