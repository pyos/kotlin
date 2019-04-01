/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.intrinsics.IrIntrinsicMethods
import org.jetbrains.kotlin.backend.jvm.intrinsics.JavaClassProperty
import org.jetbrains.kotlin.backend.jvm.intrinsics.Not
import org.jetbrains.kotlin.backend.jvm.lower.CrIrType
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.codegen.AsmUtil.*
import org.jetbrains.kotlin.codegen.ExpressionCodegen.putReifiedOperationMarkerIfTypeIsReifiedParameter
import org.jetbrains.kotlin.codegen.inline.NameGenerator
import org.jetbrains.kotlin.codegen.inline.ReifiedTypeInliner
import org.jetbrains.kotlin.codegen.inline.ReifiedTypeParametersUsages
import org.jetbrains.kotlin.codegen.inline.TypeParameterMappings
import org.jetbrains.kotlin.codegen.pseudoInsns.fakeAlwaysFalseIfeq
import org.jetbrains.kotlin.codegen.pseudoInsns.fixStackAndJump
import org.jetbrains.kotlin.codegen.signature.BothSignatureWriter
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.isReleaseCoroutines
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.TypeAliasConstructorDescriptor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.toKotlinType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils.isEnumClass
import org.jetbrains.kotlin.resolve.calls.components.hasDefaultValue
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.kotlin.resolve.jvm.AsmTypes.OBJECT_TYPE
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.isNothing
import org.jetbrains.kotlin.types.typesApproximation.approximateCapturedTypes
import org.jetbrains.kotlin.types.upperIfFlexible
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.jetbrains.kotlin.utils.keysToMap
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import java.util.*

sealed class ExpressionInfo

class LoopInfo(val loop: IrLoop, val continueLabel: Label, val breakLabel: Label) : ExpressionInfo()

class TryInfo(val onExit: IrExpression) : ExpressionInfo() {
    // Regions corresponding to copy-pasted contents of the `finally` block.
    // These should not be covered by `catch` clauses.
    val gaps = mutableListOf<Pair<Label, Label>>()
}

class BlockInfo private constructor(val parent: BlockInfo?) {
    val variables = mutableListOf<VariableInfo>()
    val infos = Stack<ExpressionInfo>()

    fun create() = BlockInfo(this).apply {
        this@apply.infos.addAll(this@BlockInfo.infos)
    }

    fun hasFinallyBlocks(): Boolean = infos.firstIsInstanceOrNull<TryInfo>() != null

    inline fun <T : ExpressionInfo, R> withBlock(info: T, f: (T) -> R): R {
        infos.add(info)
        try {
            return f(info)
        } finally {
            infos.pop()
        }
    }

    inline fun <R> handleBlock(f: (ExpressionInfo) -> R): R? {
        if (infos.isEmpty()) {
            return null
        }
        val top = infos.pop()
        try {
            return f(top)
        } finally {
            infos.add(top)
        }
    }

    companion object {
        fun create() = BlockInfo(null)
    }
}

class VariableInfo(val declaration: IrVariable, val index: Int, val type: Type, val startLabel: Label)

// A value that may not have been fully constructed yet. The ability to "roll back" code generation
// is useful for certain optimizations.
abstract class PromisedValue(val codegen: ExpressionCodegen, val type: Type) {
    // If this value is immaterial, construct an object on the top of the stack. This
    // must always be done before generating other values or emitting raw bytecode.
    abstract fun materialize()
}

// A value that *has* been fully constructed.
class MaterialValue(codegen: ExpressionCodegen, type: Type) : PromisedValue(codegen, type) {
    override fun materialize() {}
}

// A value that can be branched on. JVM has certain branching instructions which can be used
// to optimize these.
abstract class BooleanValue(codegen: ExpressionCodegen) : PromisedValue(codegen, Type.BOOLEAN_TYPE) {
    abstract fun jumpIfFalse(target: Label)
    abstract fun jumpIfTrue(target: Label)

    override fun materialize() {
        val const0 = Label()
        val end = Label()
        jumpIfFalse(const0)
        codegen.mv.iconst(1)
        codegen.mv.goTo(end)
        codegen.mv.mark(const0)
        codegen.mv.iconst(0)
        codegen.mv.mark(end)
    }
}

// Same as materialize(), but return a representation of the result.
val PromisedValue.materialized: MaterialValue
    get() {
        materialize()
        return MaterialValue(codegen, type)
    }

// Materialize and disregard this value. Materialization is forced because, presumably,
// we only wanted the side effects anyway.
fun PromisedValue.discard(): MaterialValue {
    materialize()
    if (type !== Type.VOID_TYPE)
        AsmUtil.pop(codegen.mv, type)
    return MaterialValue(codegen, Type.VOID_TYPE)
}

// On materialization, cast the value to a different type.
fun PromisedValue.coerce(target: Type) = when (target) {
    Type.VOID_TYPE -> discard()
    type -> this
    else -> object : PromisedValue(codegen, target) {
        // TODO remove dependency
        override fun materialize() = StackValue.coerce(this@coerce.materialized.type, type, codegen.mv)
    }
}

// Same as above, but with a return type that allows conditional jumping.
fun PromisedValue.coerceToBoolean() = when (val coerced = coerce(Type.BOOLEAN_TYPE)) {
    is BooleanValue -> coerced
    else -> object : BooleanValue(codegen) {
        override fun jumpIfFalse(target: Label) = coerced.materialize().also { codegen.mv.ifeq(target) }
        override fun jumpIfTrue(target: Label) = coerced.materialize().also { codegen.mv.ifne(target) }
        override fun materialize() = coerced.materialize()
    }
}

class ExpressionCodegen(
    val irFunction: IrFunction,
    val frame: IrFrameMap,
    val mv: InstructionAdapter,
    val classCodegen: ClassCodegen
) : IrElementVisitor<PromisedValue, BlockInfo>, BaseExpressionCodegen {

    private val intrinsics = IrIntrinsicMethods(classCodegen.context.irBuiltIns)

    val typeMapper = classCodegen.typeMapper

    val context = classCodegen.context

    private val state = classCodegen.state

    private val fileEntry = classCodegen.context.psiSourceManager.getFileEntry(irFunction.fileParent)

    override val frameMap: IrFrameMap
        get() = frame

    override val visitor: InstructionAdapter
        get() = mv

    override val inlineNameGenerator: NameGenerator = NameGenerator("${classCodegen.type.internalName}\$todo") // TODO

    override var lastLineNumber: Int = -1

    private val KotlinType.asmType: Type
        get() = typeMapper.mapType(this)

    private val IrType.asmType: Type
        get() = toKotlinType().asmType

    private val CallableDescriptor.asmType: Type
        get() = typeMapper.mapType(this)

    val IrExpression.asmType: Type
        get() = type.asmType

    // Assume this expression's result has already been materialized on the stack
    // with the correct type.
    val IrExpression.onStack: MaterialValue
        get() = MaterialValue(this@ExpressionCodegen, asmType)

    val voidValue: MaterialValue
        get() = MaterialValue(this@ExpressionCodegen, Type.VOID_TYPE)

    private fun markNewLabel() = Label().apply { mv.visitLabel(this) }

    private fun IrElement.markLineNumber(startOffset: Boolean) {
        val offset = if (startOffset) this.startOffset else endOffset
        if (offset < 0) {
            return
        }
        val lineNumber = fileEntry.getLineNumber(offset) + 1
        assert(lineNumber > 0)
        if (lastLineNumber != lineNumber) {
            lastLineNumber = lineNumber
            mv.visitLineNumber(lineNumber, markNewLabel())
        }
    }

    // TODO remove
    fun gen(expression: IrElement, type: Type, data: BlockInfo): StackValue {
        expression.accept(this, data).coerce(type).materialize()
        return StackValue.onStack(type)
    }

    fun generate() {
        mv.visitCode()
        val startLabel = markNewLabel()
        val info = BlockInfo.create()
        val body = irFunction.body!!
        val result = body.accept(this, info)
        // If this function has an expression body, return the result of that expression.
        // Otherwise, if it does not end in a return statement, it must be void-returning,
        // and an explicit return instruction at the end is still required to pass validation.
        if (body !is IrStatementContainer || body.statements.lastOrNull() !is IrReturn) {
            // Allow setting a breakpoint on the closing brace of a void-returning function
            // without an explicit return, or the `class Something(` line of a primary constructor.
            if (irFunction.origin != JvmLoweredDeclarationOrigin.CLASS_STATIC_INITIALIZER) {
                irFunction.markLineNumber(startOffset = irFunction is IrConstructor && irFunction.isPrimary)
            }
            val returnType = typeMapper.mapReturnType(irFunction.descriptor)
            result.coerce(returnType).materialize()
            mv.areturn(returnType)
        }
        writeLocalVariablesInTable(info)
        writeParameterInLocalVariableTable(startLabel)
        mv.visitEnd()
    }

    private fun writeParameterInLocalVariableTable(startLabel: Label) {
        if (!irFunction.isStatic) {
            mv.visitLocalVariable("this", classCodegen.type.descriptor, null, startLabel, markNewLabel(), 0)
        }
        val extensionReceiverParameter = irFunction.extensionReceiverParameter
        if (extensionReceiverParameter != null) {
            writeValueParameterInLocalVariableTable(extensionReceiverParameter, startLabel)
        }
        for (param in irFunction.valueParameters) {
            writeValueParameterInLocalVariableTable(param, startLabel)
        }
    }

    private fun writeValueParameterInLocalVariableTable(param: IrValueParameter, startLabel: Label) {
        val descriptor = param.descriptor
        val nameForDestructuredParameter = if (descriptor is ValueParameterDescriptor) {
            getNameForDestructuredParameterOrNull(descriptor)
        } else {
            null
        }

        val type = typeMapper.mapType(descriptor)
        // NOTE: we expect all value parameters to be present in the frame.
        mv.visitLocalVariable(
            nameForDestructuredParameter ?: param.name.asString(),
            type.descriptor, null, startLabel, markNewLabel(), findLocalIndex(param.symbol)
        )
    }

    override fun visitBlock(expression: IrBlock, data: BlockInfo): PromisedValue {
        if (expression.isTransparentScope)
            return super.visitBlock(expression, data)
        val info = data.create()
        return super.visitBlock(expression, info).apply {
            writeLocalVariablesInTable(info)
        }
    }

    private fun writeLocalVariablesInTable(info: BlockInfo) {
        val endLabel = markNewLabel()
        info.variables.forEach {
            when (it.declaration.origin) {
                IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
                IrDeclarationOrigin.FOR_LOOP_ITERATOR,
                IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE -> {
                    // Ignore implicitly created variables
                }
                else -> {
                    mv.visitLocalVariable(
                        it.declaration.name.asString(), it.type.descriptor, null, it.startLabel, endLabel, it.index
                    )
                }
            }
        }

        info.variables.reversed().forEach {
            frame.leave(it.declaration.symbol)
        }
    }

    private fun visitStatementContainer(container: IrStatementContainer, data: BlockInfo) =
        container.statements.fold(voidValue as PromisedValue) { prev, exp ->
            prev.discard()
            exp.accept(this, data).also { (exp as? IrExpression)?.markEndOfStatementIfNeeded() }
        }

    override fun visitBlockBody(body: IrBlockBody, data: BlockInfo) =
        visitStatementContainer(body, data).discard()

    override fun visitContainerExpression(expression: IrContainerExpression, data: BlockInfo) =
        visitStatementContainer(expression, data).coerce(expression.asmType)

    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall, data: BlockInfo): PromisedValue {
        expression.markLineNumber(startOffset = true)
        mv.load(0, OBJECT_TYPE) // HACK
        return generateCall(expression, null, data)
    }

    override fun visitCall(expression: IrCall, data: BlockInfo): PromisedValue {
        expression.markLineNumber(startOffset = true)
        if (expression.descriptor is ConstructorDescriptor) {
            return generateNewCall(expression, data)
        }
        return generateCall(expression, expression.superQualifier, data)
    }

    private fun generateNewCall(expression: IrCall, data: BlockInfo): PromisedValue {
        val type = expression.asmType
        if (type.sort == Type.ARRAY) {
            return generateNewArray(expression, data)
        }

        mv.anew(expression.asmType)
        mv.dup()
        generateCall(expression, expression.superQualifier, data)
        return expression.onStack
    }

    private fun generateNewArray(expression: IrCall, data: BlockInfo): PromisedValue {
        val args = expression.descriptor.valueParameters
        assert(args.size == 1 || args.size == 2) { "Unknown constructor called: " + args.size + " arguments" }

        if (args.size == 1) {
            // TODO move to the intrinsic
            expression.getValueArgument(0)!!.accept(this, data).coerce(Type.INT_TYPE).materialize()
            newArrayInstruction(expression.type.toKotlinType())
            return expression.onStack
        }

        return generateCall(expression, expression.superQualifier, data)
    }

    private fun generateCall(expression: IrFunctionAccessExpression, superQualifier: ClassDescriptor?, data: BlockInfo): PromisedValue {
        intrinsics.getIntrinsic(expression.descriptor.original as CallableMemberDescriptor)
            ?.invoke(expression, this, data)?.let { return it.coerce(expression.asmType) }
        val isSuperCall = superQualifier != null
        val callable = resolveToCallable(expression, isSuperCall)
        return generateCall(expression, callable, data, isSuperCall)
    }

    fun generateCall(
        expression: IrMemberAccessExpression,
        callable: Callable,
        data: BlockInfo,
        isSuperCall: Boolean = false
    ): PromisedValue {
        val callGenerator = getOrCreateCallGenerator(expression, expression.descriptor)

        val receiver = expression.dispatchReceiver
        receiver?.apply {
            callGenerator.genValueAndPut(
                null, this,
                if (isSuperCall) receiver.asmType else callable.dispatchReceiverType!!,
                -1, this@ExpressionCodegen, data
            )
        }

        expression.extensionReceiver?.apply {
            callGenerator.genValueAndPut(null, this, callable.extensionReceiverType!!, -1, this@ExpressionCodegen, data)
        }

        callGenerator.beforeValueParametersStart()
        val defaultMask = DefaultCallArgs(callable.valueParameterTypes.size)
        val extraArgsShift =
            when {
                expression.descriptor is ConstructorDescriptor && isEnumClass(expression.descriptor.containingDeclaration) -> 2
                expression.descriptor is ConstructorDescriptor &&
                        (expression.descriptor.containingDeclaration as ClassDescriptor).isInner -> 1 // skip the `$outer` parameter
                else -> 0
            }
        expression.descriptor.valueParameters.forEachIndexed { i, parameterDescriptor ->
            val arg = expression.getValueArgument(i)
            val parameterType = callable.valueParameterTypes[i]
            when {
                arg != null -> {
                    callGenerator.genValueAndPut(parameterDescriptor, arg, parameterType, i, this@ExpressionCodegen, data)
                }
                parameterDescriptor.hasDefaultValue() -> {
                    callGenerator.putValueIfNeeded(
                        parameterType,
                        StackValue.createDefaultValue(parameterType),
                        ValueKind.DEFAULT_PARAMETER,
                        i,
                        this@ExpressionCodegen
                    )
                    defaultMask.mark(i - extraArgsShift/*TODO switch to separate lower*/)
                }
                else -> {
                    assert(parameterDescriptor.varargElementType != null)
                    //empty vararg

                    // Upper bound for type of vararg parameter should always have a form of 'Array<out T>',
                    // while its lower bound may be Nothing-typed after approximation
                    val type = typeMapper.mapType(parameterDescriptor.type.upperIfFlexible())
                    callGenerator.putValueIfNeeded(
                        parameterType,
                        StackValue.operation(type) {
                            it.aconst(0)
                            it.newarray(correctElementType(type))
                        },
                        ValueKind.GENERAL_VARARG, i, this@ExpressionCodegen
                    )
                }
            }
        }

        callGenerator.genCall(
            callable,
            defaultMask.generateOnStackIfNeeded(callGenerator, expression.descriptor is ConstructorDescriptor, this),
            this,
            expression
        )

        val returnType = expression.descriptor.returnType
        if (returnType != null && returnType.isNothing()) {
            mv.aconst(null)
            mv.athrow()
            return voidValue
        } else if (expression.descriptor is ConstructorDescriptor) {
            return voidValue
        } else if (expression.type.isUnit()) {
            // NewInference allows casting `() -> T` to `() -> Unit`. A CHECKCAST here will fail.
            return MaterialValue(this, callable.returnType).discard().coerce(expression.asmType)
        }
        return MaterialValue(this, callable.returnType).coerce(expression.asmType)
    }

    override fun visitVariable(declaration: IrVariable, data: BlockInfo): PromisedValue {
        val varType = typeMapper.mapType(declaration.descriptor)
        val index = frame.enter(declaration.symbol, varType)

        declaration.markLineNumber(startOffset = true)

        declaration.initializer?.let {
            it.accept(this, data).coerce(varType).materialize()
            mv.store(index, varType)
            it.markLineNumber(startOffset = true)
        }

        data.variables.add(VariableInfo(declaration, index, varType, markNewLabel()))
        return voidValue
    }

    override fun visitGetValue(expression: IrGetValue, data: BlockInfo): PromisedValue {
        // Do not generate line number information for loads from compiler-generated
        // temporary variables. They do not correspond to variable loads in user code.
        if (expression.symbol.owner.origin != IrDeclarationOrigin.IR_TEMPORARY_VARIABLE)
            expression.markLineNumber(startOffset = true)
        mv.load(findLocalIndex(expression.symbol), expression.asmType)
        return expression.onStack
    }

    override fun visitFieldAccess(expression: IrFieldAccessExpression, data: BlockInfo): PromisedValue {
        val realDescriptor = DescriptorUtils.unwrapFakeOverride(expression.descriptor)
        val fieldType = typeMapper.mapType(realDescriptor.original.type)
        val ownerType = typeMapper.mapImplementationOwner(expression.descriptor).internalName
        val fieldName = expression.descriptor.name.asString()
        val isStatic = expression.receiver == null
        expression.markLineNumber(startOffset = true)
        expression.receiver?.accept(this, data)?.materialize()
        return if (expression is IrSetField) {
            expression.value.accept(this, data).coerce(fieldType).materialize()
            when {
                isStatic -> mv.putstatic(ownerType, fieldName, fieldType.descriptor)
                else -> mv.putfield(ownerType, fieldName, fieldType.descriptor)
            }
            voidValue.coerce(expression.asmType)
        } else {
            when {
                isStatic -> mv.getstatic(ownerType, fieldName, fieldType.descriptor)
                else -> mv.getfield(ownerType, fieldName, fieldType.descriptor)
            }
            MaterialValue(this, fieldType).coerce(expression.asmType)
        }
    }

    override fun visitSetField(expression: IrSetField, data: BlockInfo): PromisedValue {
        val expressionValue = expression.value
        // Do not add redundant field initializers that initialize to default values.
        // "expression.origin == null" means that the field is initialized when it is declared,
        // i.e., not in an initializer block or constructor body.
        val skip = irFunction is IrConstructor && irFunction.isPrimary &&
                expression.origin == null && expressionValue is IrConst<*> &&
                isDefaultValueForType(expression.symbol.owner.type.asmType, expressionValue.value)
        return if (skip) voidValue.coerce(expression.asmType) else super.visitSetField(expression, data)
    }

    /**
     * Returns true if the given constant value is the JVM's default value for the given type.
     * See: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-2.html#jvms-2.3
     */
    private fun isDefaultValueForType(type: Type, value: Any?): Boolean =
        when (type) {
            Type.BOOLEAN_TYPE -> value is Boolean && !value
            Type.CHAR_TYPE -> value is Char && value.toInt() == 0
            Type.BYTE_TYPE, Type.SHORT_TYPE, Type.INT_TYPE, Type.LONG_TYPE -> value is Number && value.toLong() == 0L
            // Must use `equals` for these two to differentiate between +0.0 and -0.0:
            Type.FLOAT_TYPE -> value is Number && value.toFloat().equals(0.0f)
            Type.DOUBLE_TYPE -> value is Number && value.toDouble().equals(0.0)
            else -> !isPrimitive(type) && value == null
        }

    private fun findLocalIndex(irSymbol: IrSymbol): Int {
        val index = frame.getIndex(irSymbol)
        if (index >= 0) {
            return index
        }
        if (irFunction.dispatchReceiverParameter != null && (irFunction.parent as? IrClass)?.thisReceiver?.symbol == irSymbol) {
            return 0
        }
        val dump = if (irSymbol.isBound) irSymbol.owner.dump() else irSymbol.descriptor.toString()
        throw AssertionError("Non-mapped local declaration: $dump\n in ${irFunction.dump()}")
    }

    override fun visitSetVariable(expression: IrSetVariable, data: BlockInfo): PromisedValue {
        expression.markLineNumber(startOffset = true)
        expression.value.markLineNumber(startOffset = true)
        expression.value.accept(this, data).coerce(expression.descriptor.asmType).materialize()
        mv.store(findLocalIndex(expression.symbol), expression.descriptor.asmType)
        return voidValue.coerce(expression.asmType)
    }

    override fun <T> visitConst(expression: IrConst<T>, data: BlockInfo): PromisedValue {
        expression.markLineNumber(startOffset = true)
        when (val value = expression.value) {
            is Boolean -> return object : BooleanValue(this) {
                override fun jumpIfFalse(target: Label) = if (value) Unit else codegen.mv.goTo(target)
                override fun jumpIfTrue(target: Label) = if (value) codegen.mv.goTo(target) else Unit
                override fun materialize() = codegen.mv.iconst(if (value) 1 else 0)
            }
            is Char -> mv.iconst(value.toInt())
            is Long -> mv.lconst(value)
            is Float -> mv.fconst(value)
            is Double -> mv.dconst(value)
            is Number -> mv.iconst(value.toInt())
            else -> mv.aconst(value)
        }
        return expression.onStack
    }

    override fun visitExpressionBody(body: IrExpressionBody, data: BlockInfo) =
        body.expression.accept(this, data)

    override fun visitElement(element: IrElement, data: BlockInfo) =
        throw AssertionError(
            "Unexpected IR element found during code generation. Either code generation for it " +
                    "is not implemented, or it should have been lowered: ${element.render()}"
        )

    // TODO maybe remove?
    override fun visitClass(declaration: IrClass, data: BlockInfo): PromisedValue {
        classCodegen.generateLocalClass(declaration)
        return voidValue
    }

    override fun visitVararg(expression: IrVararg, data: BlockInfo): PromisedValue {
        expression.markLineNumber(startOffset = true)
        val outType = expression.type
        val type = expression.asmType
        assert(type.sort == Type.ARRAY)
        val elementType = correctElementType(type)
        val arguments = expression.elements
        val size = arguments.size

        val hasSpread = arguments.firstIsInstanceOrNull<IrSpreadElement>() != null

        if (hasSpread) {
            val arrayOfReferences = KotlinBuiltIns.isArray(outType.toKotlinType())
            if (size == 1) {
                // Arrays.copyOf(receiverValue, newLength)
                val argument = (arguments[0] as IrSpreadElement).expression
                val arrayType = if (arrayOfReferences)
                    Type.getType("[Ljava/lang/Object;")
                else
                    Type.getType("[" + elementType.descriptor)
                argument.accept(this, data).coerce(type).materialize()
                mv.dup()
                mv.arraylength()
                mv.invokestatic("java/util/Arrays", "copyOf", Type.getMethodDescriptor(arrayType, arrayType, Type.INT_TYPE), false)
                if (arrayOfReferences) {
                    mv.checkcast(type)
                }
            } else {
                val owner: String
                val addDescriptor: String
                val toArrayDescriptor: String
                if (arrayOfReferences) {
                    owner = "kotlin/jvm/internal/SpreadBuilder"
                    addDescriptor = "(Ljava/lang/Object;)V"
                    toArrayDescriptor = "([Ljava/lang/Object;)[Ljava/lang/Object;"
                } else {
                    val spreadBuilderClassName =
                        AsmUtil.asmPrimitiveTypeToLangPrimitiveType(elementType)!!.typeName.identifier + "SpreadBuilder"
                    owner = "kotlin/jvm/internal/" + spreadBuilderClassName
                    addDescriptor = "(" + elementType.descriptor + ")V"
                    toArrayDescriptor = "()" + type.descriptor
                }
                mv.anew(Type.getObjectType(owner))
                mv.dup()
                mv.iconst(size)
                mv.invokespecial(owner, "<init>", "(I)V", false)
                for (i in 0..size - 1) {
                    mv.dup()
                    val argument = arguments[i]
                    if (argument is IrSpreadElement) {
                        argument.expression.accept(this, data).coerce(AsmTypes.OBJECT_TYPE).materialize()
                        mv.invokevirtual(owner, "addSpread", "(Ljava/lang/Object;)V", false)
                    } else {
                        argument.accept(this, data).coerce(elementType).materialize()
                        mv.invokevirtual(owner, "add", addDescriptor, false)
                    }
                }
                if (arrayOfReferences) {
                    mv.dup()
                    mv.invokevirtual(owner, "size", "()I", false)
                    newArrayInstruction(outType.toKotlinType())
                    mv.invokevirtual(owner, "toArray", toArrayDescriptor, false)
                    mv.checkcast(type)
                } else {
                    mv.invokevirtual(owner, "toArray", toArrayDescriptor, false)
                }
            }
        } else {
            mv.iconst(size)
            newArrayInstruction(expression.type.toKotlinType())
            for ((i, element) in expression.elements.withIndex()) {
                mv.dup()
                mv.iconst(i)
                element.accept(this, data).coerce(elementType).materialize()
                mv.astore(elementType)
            }
        }
        return expression.onStack
    }

    fun newArrayInstruction(arrayType: KotlinType) {
        if (KotlinBuiltIns.isArray(arrayType)) {
            val elementJetType = arrayType.arguments[0].type
//            putReifiedOperationMarkerIfTypeIsReifiedParameter(
//                    elementJetType,
//                    ReifiedTypeInliner.OperationKind.NEW_ARRAY
//            )
            mv.newarray(boxType(elementJetType.asmType))
        } else {
            val type = typeMapper.mapType(arrayType)
            mv.newarray(correctElementType(type))
        }
    }

    override fun visitReturn(expression: IrReturn, data: BlockInfo): PromisedValue {
        val returnType = typeMapper.mapReturnType(irFunction.descriptor)
        val afterReturnLabel = Label()
        expression.value.accept(this, data).coerce(returnType).materialize()
        generateFinallyBlocksIfNeeded(returnType, afterReturnLabel, data)
        expression.markLineNumber(startOffset = true)
        mv.areturn(returnType)
        mv.mark(afterReturnLabel)
        mv.nop()/*TODO check RESTORE_STACK_IN_TRY_CATCH processor*/
        return voidValue
    }

    override fun visitWhen(expression: IrWhen, data: BlockInfo): PromisedValue {
        expression.markLineNumber(startOffset = true)
        SwitchGenerator(expression, data, this).generate()?.let { return it }

        val endLabel = Label()
        for (branch in expression.branches) {
            val elseLabel = Label()
            if (branch.condition.isFalseConst() || branch.condition.isTrueConst()) {
                // True or false conditions known at compile time need not be generated. A linenumber and nop
                // are still required for a debugger to break on the line of the condition.
                if (branch !is IrElseBranch) {
                    branch.condition.markLineNumber(startOffset = true)
                    mv.nop()
                }
                if (branch.condition.isFalseConst())
                    continue // The branch body is dead code.
            } else {
                branch.condition.accept(this, data).coerceToBoolean().jumpIfFalse(elseLabel)
            }
            val result = branch.result.accept(this, data).coerce(expression.asmType).materialized
            if (branch.condition.isTrueConst()) {
                // The rest of the expression is dead code.
                mv.mark(endLabel)
                return result
            }
            mv.goTo(endLabel)
            mv.mark(elseLabel)
        }
        // Produce the default value for the type. Doesn't really matter right now, as non-exhaustive
        // conditionals cannot be used as expressions.
        val result = voidValue.coerce(expression.asmType).materialized
        mv.mark(endLabel)
        return result
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall, data: BlockInfo): PromisedValue {
        val asmType = expression.typeOperand.asmType
        return when (expression.operator) {
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
                expression.argument.accept(this, data).discard()
                expression.argument.markEndOfStatementIfNeeded()
                voidValue.coerce(expression.asmType)
            }

            IrTypeOperator.IMPLICIT_CAST -> {
                expression.argument.accept(this, data).coerce(expression.asmType)
            }

            IrTypeOperator.IMPLICIT_INTEGER_COERCION -> {
                expression.argument.accept(this, data).coerce(expression.asmType)
            }

            IrTypeOperator.CAST, IrTypeOperator.SAFE_CAST -> {
                expression.argument.accept(this, data).coerce(AsmTypes.OBJECT_TYPE).materialize()
                val boxedType = boxType(asmType)
                generateAsCast(
                    mv, expression.typeOperand.toKotlinType(), boxedType, expression.operator == IrTypeOperator.SAFE_CAST,
                    state.languageVersionSettings.isReleaseCoroutines()
                )
                MaterialValue(this, boxedType).coerce(expression.asmType)
            }

            IrTypeOperator.INSTANCEOF, IrTypeOperator.NOT_INSTANCEOF -> {
                expression.argument.accept(this, data).coerce(AsmTypes.OBJECT_TYPE).materialize()
                val type = boxType(asmType)
                generateIsCheck(mv, expression.typeOperand.toKotlinType(), type, state.languageVersionSettings.isReleaseCoroutines())
                // TODO remove this type operator, generate an intrinsic call around INSTANCEOF instead
                if (IrTypeOperator.NOT_INSTANCEOF == expression.operator)
                    Not.BooleanNegation(expression.onStack.coerceToBoolean())
                else
                    expression.onStack
            }

            IrTypeOperator.IMPLICIT_NOTNULL -> {
                val value = expression.argument.accept(this, data).materialized
                mv.dup()
                mv.visitLdcInsn("TODO provide message for IMPLICIT_NOTNULL") /*TODO*/
                mv.invokestatic(
                    "kotlin/jvm/internal/Intrinsics", "checkExpressionValueIsNotNull",
                    "(Ljava/lang/Object;Ljava/lang/String;)V", false
                )
                // Unbox primitives.
                value.coerce(expression.asmType)
            }

            else -> throw AssertionError("type operator ${expression.operator} should have been lowered")
        }
    }

    private fun IrExpression.markEndOfStatementIfNeeded() {
        when (this) {
            is IrWhen -> if (this.branches.size > 1) {
                this.markLineNumber(false)
            }
            is IrTry -> this.markLineNumber(false)
            is IrContainerExpression -> when (this.origin) {
                IrStatementOrigin.WHEN, IrStatementOrigin.IF ->
                    this.markLineNumber(false)
            }
        }
    }

    override fun visitStringConcatenation(expression: IrStringConcatenation, data: BlockInfo): PromisedValue {
        expression.markLineNumber(startOffset = true)
        when (expression.arguments.size) {
            0 -> mv.aconst("")
            1 -> {
                // Convert single arg to string.
                val type = expression.arguments[0].accept(this, data).materialized.type
                AsmUtil.genToString(StackValue.onStack(type), type, null, typeMapper).put(expression.asmType, mv)
            }
            else -> {
                // Use StringBuilder to concatenate.
                AsmUtil.genStringBuilderConstructor(mv)
                expression.arguments.forEach {
                    AsmUtil.genInvokeAppendMethod(mv, it.accept(this, data).materialized.type, null)
                }
                mv.invokevirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            }
        }
        return expression.onStack
    }

    override fun visitWhileLoop(loop: IrWhileLoop, data: BlockInfo): PromisedValue {
        val continueLabel = markNewLabel()
        val endLabel = Label()
        // Mark stack depth for break
        mv.fakeAlwaysFalseIfeq(endLabel)
        loop.condition.markLineNumber(true)
        loop.condition.accept(this, data).coerceToBoolean().jumpIfFalse(endLabel)
        data.withBlock(LoopInfo(loop, continueLabel, endLabel)) {
            loop.body?.accept(this, data)?.discard()
        }
        mv.goTo(continueLabel)
        mv.mark(endLabel)
        return voidValue
    }

    override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: BlockInfo): PromisedValue {
        val entry = markNewLabel()
        val endLabel = Label()
        val continueLabel = Label()
        // Mark stack depth for break/continue
        mv.fakeAlwaysFalseIfeq(continueLabel)
        mv.fakeAlwaysFalseIfeq(endLabel)
        data.withBlock(LoopInfo(loop, continueLabel, endLabel)) {
            loop.body?.accept(this, data)?.discard()
        }
        mv.visitLabel(continueLabel)
        loop.condition.markLineNumber(true)
        loop.condition.accept(this, data).coerceToBoolean().jumpIfTrue(entry)
        mv.mark(endLabel)
        return voidValue
    }

    private fun unwindBlockStack(endLabel: Label, data: BlockInfo, loop: IrLoop? = null): LoopInfo? {
        return data.handleBlock {
            when {
                it is TryInfo -> {
                    it.gaps.add(markNewLabel() to endLabel)
                    genFinallyBlock(it, data)
                }
                it is LoopInfo && it.loop == loop -> return it
            }
            unwindBlockStack(endLabel, data, loop)
        }
    }

    override fun visitBreakContinue(jump: IrBreakContinue, data: BlockInfo): PromisedValue {
        jump.markLineNumber(startOffset = true)
        val endLabel = Label()
        val stackElement = unwindBlockStack(endLabel, data, jump.loop)
            ?: throw AssertionError("Target label for break/continue not found")
        mv.fixStackAndJump(if (jump is IrBreak) stackElement.breakLabel else stackElement.continueLabel)
        mv.mark(endLabel)
        return voidValue
    }

    override fun visitTry(aTry: IrTry, data: BlockInfo): PromisedValue {
        aTry.markLineNumber(startOffset = true)
        return if (aTry.finallyExpression != null)
            data.withBlock(TryInfo(aTry.finallyExpression!!)) { visitTryWithInfo(aTry, data, it) }
        else
            visitTryWithInfo(aTry, data, null)
    }

    private fun visitTryWithInfo(aTry: IrTry, data: BlockInfo, tryInfo: TryInfo?): PromisedValue {
        val tryBlockStart = markNewLabel()
        mv.nop()
        val tryResult = aTry.tryResult.accept(this, data).coerce(aTry.asmType).materialized
        val tryBlockEnd = markNewLabel()
        val tryBlockGaps = tryInfo?.gaps?.toList() ?: listOf()
        val tryCatchBlockEnd = Label()
        if (tryInfo != null) {
            data.handleBlock { genFinallyBlock(tryInfo, data) }
            tryInfo.onExit.markLineNumber(startOffset = false)
            mv.goTo(tryCatchBlockEnd)
            tryInfo.gaps.add(tryBlockEnd to markNewLabel())
        } else {
            mv.goTo(tryCatchBlockEnd)
        }

        val catches = aTry.catches
        for (clause in catches) {
            val clauseStart = markNewLabel()
            val descriptor = clause.parameter
            val descriptorType = descriptor.asmType
            val index = frame.enter(clause.catchParameter, descriptorType)
            mv.store(index, descriptorType)

            val catchBody = clause.result
            catchBody.markLineNumber(true)
            catchBody.accept(this, data).coerce(aTry.asmType).materialized

            frame.leave(clause.catchParameter)

            val clauseEnd = markNewLabel()

            mv.visitLocalVariable(
                descriptor.name.asString(), descriptorType.descriptor, null, clauseStart, clauseEnd,
                index
            )

            if (tryInfo != null) {
                data.handleBlock { genFinallyBlock(tryInfo, data) }
                tryInfo.onExit.markLineNumber(startOffset = false)
                mv.goTo(tryCatchBlockEnd)
                tryInfo.gaps.add(clauseEnd to markNewLabel())
            } else if (clause != catches.last()) {
                mv.goTo(tryCatchBlockEnd)
            }

            genTryCatchCover(clauseStart, tryBlockStart, tryBlockEnd, tryBlockGaps, descriptorType.internalName)
        }

        if (tryInfo != null) {
            // Generate `try { ... } catch (e: Any?) { <finally>; throw e }` around every part of
            // the try-catch that is not a copy-pasted `finally` block.
            val defaultCatchStart = markNewLabel()
            // While keeping this value on the stack should be enough, the bytecode validator will
            // complain if a catch block does not start with ASTORE.
            val savedException = frame.enterTemp(AsmTypes.JAVA_THROWABLE_TYPE)
            mv.store(savedException, AsmTypes.JAVA_THROWABLE_TYPE)

            val finallyStart = markNewLabel()
            // Nothing will cover anything after this point, so don't bother recording the gap here.
            data.handleBlock { genFinallyBlock(tryInfo, data) }
            mv.load(savedException, AsmTypes.JAVA_THROWABLE_TYPE)
            frame.leaveTemp(AsmTypes.JAVA_THROWABLE_TYPE)
            mv.athrow()

            // Include the ASTORE into the covered region. This is used by the inliner to detect try-finally.
            genTryCatchCover(defaultCatchStart, tryBlockStart, finallyStart, tryInfo.gaps, null)
        }

        mv.mark(tryCatchBlockEnd)
        // TODO: generate a common `finally` for try & catch blocks here? Right now this breaks the inliner.
        return tryResult
    }

    private fun genTryCatchCover(catchStart: Label, tryStart: Label, tryEnd: Label, tryGaps: List<Pair<Label, Label>>, type: String?) {
        val lastRegionStart = tryGaps.fold(tryStart) { regionStart, (gapStart, gapEnd) ->
            mv.visitTryCatchBlock(regionStart, gapStart, catchStart, type)
            gapEnd
        }
        mv.visitTryCatchBlock(lastRegionStart, tryEnd, catchStart, type)
    }

    private fun genFinallyBlock(tryInfo: TryInfo, data: BlockInfo) {
        tryInfo.onExit.accept(this, data).discard()
    }

    private fun generateFinallyBlocksIfNeeded(returnType: Type, afterReturnLabel: Label, data: BlockInfo) {
        if (data.hasFinallyBlocks()) {
            if (Type.VOID_TYPE != returnType) {
                val returnValIndex = frame.enterTemp(returnType)
                mv.store(returnValIndex, returnType)
                unwindBlockStack(afterReturnLabel, data, null)
                mv.load(returnValIndex, returnType)
                frame.leaveTemp(returnType)
            } else {
                unwindBlockStack(afterReturnLabel, data, null)
            }
        }
    }

    override fun visitThrow(expression: IrThrow, data: BlockInfo): PromisedValue {
        expression.markLineNumber(startOffset = true)
        expression.value.accept(this, data).coerce(AsmTypes.JAVA_THROWABLE_TYPE).materialize()
        mv.athrow()
        return voidValue
    }

    override fun visitGetClass(expression: IrGetClass, data: BlockInfo) =
        generateClassLiteralReference(expression, true, data)

    override fun visitClassReference(expression: IrClassReference, data: BlockInfo) =
        generateClassLiteralReference(expression, true, data)

    fun generateClassLiteralReference(
        classReference: IrExpression,
        wrapIntoKClass: Boolean,
        data: BlockInfo
    ): PromisedValue {
        if (classReference is IrGetClass) {
            // TODO transform one sort of access into the other?
            JavaClassProperty.invokeWith(classReference.argument.accept(this, data))
        } else if (classReference is IrClassReference) {
            val classType = classReference.classType
            if (classType is CrIrType) {
                putJavaLangClassInstance(mv, classType.type, null, typeMapper)
                return classReference.onStack
            } else {
                val kotlinType = classType.toKotlinType()
                if (TypeUtils.isTypeParameter(kotlinType)) {
                    assert(TypeUtils.isReifiedTypeParameter(kotlinType)) { "Non-reified type parameter under ::class should be rejected by type checker: " + kotlinType }
                    putReifiedOperationMarkerIfTypeIsReifiedParameter(kotlinType, ReifiedTypeInliner.OperationKind.JAVA_CLASS, mv, this)
                }

                putJavaLangClassInstance(mv, typeMapper.mapType(kotlinType), kotlinType, typeMapper)
            }
        } else {
            throw AssertionError("not an IrGetClass or IrClassReference: ${classReference.dump()}")
        }

        if (wrapIntoKClass) {
            wrapJavaClassIntoKClass(mv)
        }
        return classReference.onStack
    }

    private fun resolveToCallable(irCall: IrMemberAccessExpression, isSuper: Boolean): Callable {
        var descriptor = irCall.descriptor
        if (descriptor is TypeAliasConstructorDescriptor) {
            //TODO where is best to unwrap?
            descriptor = descriptor.underlyingConstructorDescriptor
        }
        if (descriptor is PropertyDescriptor) {
            descriptor = descriptor.getter!!
        }
        if (descriptor is CallableMemberDescriptor && JvmCodegenUtil.getDirectMember(descriptor) is SyntheticJavaPropertyDescriptor) {
            val propertyDescriptor = JvmCodegenUtil.getDirectMember(descriptor) as SyntheticJavaPropertyDescriptor
            descriptor = if (descriptor is PropertyGetterDescriptor) {
                propertyDescriptor.getMethod
            } else {
                propertyDescriptor.setMethod!!
            }
        }
        return typeMapper.mapToCallableMethod(descriptor as FunctionDescriptor, isSuper)
    }

    private fun getOrCreateCallGenerator(
        descriptor: CallableDescriptor,
        element: IrMemberAccessExpression?,
        typeParameterMappings: TypeParameterMappings?,
        isDefaultCompilation: Boolean
    ): IrCallGenerator {
        if (element == null) return IrCallGenerator.DefaultCallGenerator

        // We should inline callable containing reified type parameters even if inline is disabled
        // because they may contain something to reify and straight call will probably fail at runtime
        val isInline = descriptor.isInlineCall(state)

        if (!isInline) return IrCallGenerator.DefaultCallGenerator

        val original = unwrapInitialSignatureDescriptor(DescriptorUtils.unwrapFakeOverride(descriptor.original as FunctionDescriptor))
        return if (isDefaultCompilation) {
            TODO()
        } else {
            IrInlineCodegen(this, state, original, typeParameterMappings!!, IrSourceCompilerForInline(state, element, this))
        }
    }

    private fun getOrCreateCallGenerator(
        memberAccessExpression: IrMemberAccessExpression,
        descriptor: CallableDescriptor
    ): IrCallGenerator {
        val typeArguments =
            if (memberAccessExpression.typeArgumentsCount == 0) {
                //avoid ambiguity with type constructor type parameters
                emptyMap()
            } else descriptor.original.typeParameters.keysToMap {
                memberAccessExpression.getTypeArgumentOrDefault(it)
            }

        val mappings = TypeParameterMappings()
        for (entry in typeArguments.entries) {
            val key = entry.key
            val type = entry.value

            val isReified = key.isReified || InlineUtil.isArrayConstructorWithLambda(descriptor)

            val typeParameterAndReificationArgument = extractReificationArgument(type)
            if (typeParameterAndReificationArgument == null) {
                val approximatedType = approximateCapturedTypes(entry.value).upper
                // type is not generic
                val signatureWriter = BothSignatureWriter(BothSignatureWriter.Mode.TYPE)
                val asmType = typeMapper.mapTypeParameter(approximatedType, signatureWriter)

                mappings.addParameterMappingToType(
                    key.name.identifier, approximatedType, asmType, signatureWriter.toString(), isReified
                )
            } else {
                mappings.addParameterMappingForFurtherReification(
                    key.name.identifier, type, typeParameterAndReificationArgument.second, isReified
                )
            }
        }

        return getOrCreateCallGenerator(descriptor, memberAccessExpression, mappings, false)
    }

    override fun consumeReifiedOperationMarker(typeParameterDescriptor: TypeParameterDescriptor) {
        //TODO
    }

    override fun propagateChildReifiedTypeParametersUsages(reifiedTypeParametersUsages: ReifiedTypeParametersUsages) {
        //TODO
    }

    override fun pushClosureOnStack(
        classDescriptor: ClassDescriptor,
        putThis: Boolean,
        callGenerator: CallGenerator,
        functionReferenceReceiver: StackValue?
    ) {
        //TODO
    }

    override fun markLineNumberAfterInlineIfNeeded() {
        //TODO
    }
}

fun DefaultCallArgs.generateOnStackIfNeeded(callGenerator: IrCallGenerator, isConstructor: Boolean, codegen: ExpressionCodegen): Boolean {
    val toInts = toInts()
    if (!toInts.isEmpty()) {
        for (mask in toInts) {
            callGenerator.putValueIfNeeded(Type.INT_TYPE, StackValue.constant(mask, Type.INT_TYPE), ValueKind.DEFAULT_MASK, -1, codegen)
        }

        val parameterType = if (isConstructor) AsmTypes.DEFAULT_CONSTRUCTOR_MARKER else AsmTypes.OBJECT_TYPE
        callGenerator.putValueIfNeeded(
            parameterType,
            StackValue.constant(null, parameterType),
            ValueKind.METHOD_HANDLE_IN_DEFAULT,
            -1,
            codegen
        )
    }
    return toInts.isNotEmpty()
}

internal fun CallableDescriptor.isInlineCall(state: GenerationState) =
    (!state.isInlineDisabled || InlineUtil.containsReifiedTypeParameters(this)) &&
            (InlineUtil.isInline(this) || InlineUtil.isArrayConstructorWithLambda(this))
