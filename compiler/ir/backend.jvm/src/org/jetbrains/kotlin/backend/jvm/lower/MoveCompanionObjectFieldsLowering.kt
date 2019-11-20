/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.codegen.isJvmInterface
import org.jetbrains.kotlin.backend.jvm.ir.replaceThisByStaticReference
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrAnonymousInitializerImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

internal val moveOrCopyCompanionObjectFieldsPhase = makeIrFilePhase(
    ::MoveOrCopyCompanionObjectFieldsLowering,
    name = "MoveOrCopyCompanionObjectFields",
    description = "Move and/or copy companion object fields to static fields of companion's owner"
)

internal val remapObjectFieldAccesses = makeIrFilePhase(
    ::RemapObjectFieldAccesses,
    name = "RemapObjectFieldAccesses",
    description = "Make IrGetField/IrSetField to objects' fields point to the static versions",
    prerequisite = setOf(propertiesToFieldsPhase)
)

private class MoveOrCopyCompanionObjectFieldsLowering(val context: JvmBackendContext) : ClassLoweringPass {
    override fun lower(irClass: IrClass) {
        if (irClass.isObject && !irClass.isCompanion) {
            irClass.handle()
        } else {
            (irClass.declarations.singleOrNull { it is IrClass && it.isCompanion } as IrClass?)?.handle()
        }
    }

    private fun IrClass.handle() {
        val newParent = context.declarationFactory.getStaticBackingFieldParent(this)
        val newDeclarations = declarations.map {
            when (it) {
                is IrProperty -> context.declarationFactory.getStaticBackingField(it, newParent)?.also { newField ->
                    it.backingField = newField
                    newField.correspondingPropertySymbol = it.symbol
                }
                is IrAnonymousInitializer -> moveAnonymousInitializerToStaticParent(it, newParent)
                else -> null
            }
        }

        if (newParent === this) {
            // Keep fields as children of `IrProperty`, but replace anonymous initializers with static ones,
            // preserving the relative ordering of anonymous initializers and property initializers.
            for ((i, declaration) in newDeclarations.withIndex()) {
                if (declaration is IrAnonymousInitializer) {
                    declarations[i] = declaration
                }
            }

            val companionParent = if (isCompanion) parentAsClass else null
            if (companionParent?.isJvmInterface == true) {
                for (declaration in declarations) {
                    if (declaration is IrProperty && declaration.isConst && declaration.hasPublicVisibility) {
                        copyConstField(declaration.backingField!!, companionParent)
                    }
                }
            }
        } else {
            // Move all touched declarations to the parent.
            declarations.removeAll { it is IrAnonymousInitializer }
            newDeclarations.filterNotNullTo(newParent.declarations)
        }
    }

    private val IrProperty.hasPublicVisibility: Boolean
        get() = !Visibilities.isPrivate(visibility) && visibility != Visibilities.PROTECTED

    private fun moveAnonymousInitializerToStaticParent(oldInitializer: IrAnonymousInitializer, newParent: IrClass) =
        with(oldInitializer) {
            val oldParent = parentAsClass
            val newSymbol = IrAnonymousInitializerSymbolImpl(newParent.symbol)
            IrAnonymousInitializerImpl(startOffset, endOffset, origin, newSymbol, isStatic = true).apply {
                parent = newParent
                body = this@with.body
                    .replaceThisByStaticReference(context.declarationFactory, oldParent, oldParent.thisReceiver!!)
                    .patchDeclarationParents(newParent) as IrBlockBody
            }
        }

    private fun copyConstField(oldField: IrField, newParent: IrClass) =
        newParent.addField {
            updateFrom(oldField)
            name = oldField.name
            isStatic = true
        }.apply {
            parent = newParent
            annotations += oldField.annotations
            initializer = oldField.initializer?.deepCopyWithSymbols(this)
        }
}

private class RemapObjectFieldAccesses(val context: JvmBackendContext) : FileLoweringPass, IrElementTransformerVoid() {
    override fun lower(irFile: IrFile) = irFile.transformChildrenVoid()

    private fun IrField.remap(): IrField? =
        correspondingPropertySymbol?.owner?.let(context.declarationFactory::getStaticBackingField)

    override fun visitGetField(expression: IrGetField): IrExpression =
        expression.symbol.owner.remap()?.let {
            with(expression) {
                IrGetFieldImpl(startOffset, endOffset, it.symbol, type, /* receiver = */ null, origin, superQualifierSymbol)
            }
        } ?: super.visitGetField(expression)

    override fun visitSetField(expression: IrSetField): IrExpression =
        expression.symbol.owner.remap()?.let {
            with(expression) {
                IrSetFieldImpl(
                    startOffset, endOffset, it.symbol, /* receiver = */ null, visitExpression(value), type, origin, superQualifierSymbol
                )
            }
        } ?: super.visitSetField(expression)
}
