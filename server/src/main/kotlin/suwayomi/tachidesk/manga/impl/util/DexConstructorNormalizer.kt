package suwayomi.tachidesk.manga.impl.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.googlecode.d2j.DexLabel
import com.googlecode.d2j.Method
import com.googlecode.d2j.node.DexCodeNode
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.node.insn.AbstractMethodStmtNode
import com.googlecode.d2j.node.insn.BaseSwitchStmtNode
import com.googlecode.d2j.node.insn.ConstStmtNode
import com.googlecode.d2j.node.insn.DexLabelStmtNode
import com.googlecode.d2j.node.insn.DexStmtNode
import com.googlecode.d2j.node.insn.FieldStmtNode
import com.googlecode.d2j.node.insn.JumpStmtNode
import com.googlecode.d2j.node.insn.MethodStmtNode
import com.googlecode.d2j.node.insn.Stmt1RNode
import com.googlecode.d2j.node.insn.Stmt2R1NNode
import com.googlecode.d2j.node.insn.Stmt2RNode
import com.googlecode.d2j.node.insn.Stmt3RNode
import com.googlecode.d2j.node.insn.TypeStmtNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.DexFileVisitor
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Preserves construction semantics that are legal in optimized DEX but cannot be represented
 * directly in JVM bytecode.
 *
 * ART tracks the uninitialized value produced by `NEW_INSTANCE` by register. R8 can legally make
 * that value invoke an ancestor constructor after removing one or more forwarding constructors.
 * dex2jar otherwise treats the invoked owner as the allocated JVM type and loses the concrete DEX
 * type. This reader performs a forward reaching-definition analysis over DEX registers, rewrites
 * the matching constructor owner before dex2jar lowers the method, and records the forwarding
 * constructors which the JVM hierarchy requires.
 *
 * No JVM instruction order, consumer shape, source name, or obfuscated class name is consulted.
 */
internal class DexConstructorNormalizer private constructor(
    private val dex: DexFileNode,
) : BaseDexFileReader {
    val forwardingConstructors = mutableSetOf<ForwardingConstructor>()

    init {
        normalize()
    }

    override fun getDexVersion(): Int = dex.dexVersion

    override fun getClassNames(): List<String> = dex.clzs.map { it.className }

    override fun accept(visitor: DexFileVisitor) = dex.accept(visitor)

    override fun accept(
        visitor: DexFileVisitor,
        config: Int,
    ) = dex.accept(visitor)

    override fun accept(
        visitor: DexFileVisitor,
        classIdx: Int,
        config: Int,
    ) {
        visitor.visitDexFileVersion(dex.dexVersion)
        dex.clzs[classIdx].accept(visitor)
        visitor.visitEnd()
    }

    private fun normalize() {
        val classes = dex.clzs.associateBy { it.className }
        dex.clzs.forEach { owner ->
            owner.methods.orEmpty().forEach { method ->
                method.codeNode?.let { code -> normalizeMethod(method.method, code, classes) }
            }
        }
    }

    private fun normalizeMethod(
        enclosingMethod: Method,
        code: DexCodeNode,
        classes: Map<String, com.googlecode.d2j.node.DexClassNode>,
    ) {
        if (code.stmts.isEmpty() || code.totalRegister <= 0) return

        val labels = IdentityHashMap<DexLabel, Int>()
        code.stmts.forEachIndexed { index, stmt ->
            (stmt as? DexLabelStmtNode)?.let { labels[it.label] = index }
        }
        val successors = buildSuccessors(code, labels)
        val inStates = arrayOfNulls<RegisterState>(code.stmts.size)
        val queue = ArrayDeque<Int>()
        inStates[0] = RegisterState(code.totalRegister)
        queue.add(0)

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val input = inStates[index] ?: continue
            val output = transfer(code.stmts[index], index, input)
            successors[index].forEach { successor ->
                val previous = inStates[successor]
                val merged = previous?.merge(output) ?: output.copy()
                if (previous == null || merged != previous) {
                    inStates[successor] = merged
                    queue.add(successor)
                }
            }
        }

        code.stmts.indices.forEach { index ->
            val invocation = code.stmts[index] as? MethodStmtNode ?: return@forEach
            if (!invocation.isDirectConstructor() || invocation.args.isEmpty()) return@forEach
            val receiver = inStates[index]?.get(invocation.args[0]) ?: RegisterValue.OTHER
            if (receiver.allocations.isEmpty()) return@forEach
            if (receiver.hasOtherValue || receiver.allocations.size != 1) {
                throw ExtensionCompatibilityException(
                    "ambiguous DEX constructor receiver in " +
                        "${enclosingMethod.owner}.${enclosingMethod.name}${enclosingMethod.desc}",
                )
            }

            val allocation = receiver.allocations.single()
            if (allocation.type == invocation.method.owner) return@forEach
            if (!isAncestor(allocation.type, invocation.method.owner, classes)) {
                throw ExtensionCompatibilityException(
                    "DEX allocates ${allocation.type} but invokes unrelated constructor " +
                        "${invocation.method.owner} in " +
                        "${enclosingMethod.owner}.${enclosingMethod.name}${enclosingMethod.desc}",
                )
            }

            forwardingConstructors +=
                ForwardingConstructor(
                    allocatedType = allocation.type.dexTypeToInternalName(),
                    invokedOwner = invocation.method.owner.dexTypeToInternalName(),
                    descriptor = invocation.method.desc,
                )
            code.stmts[index] =
                MethodStmtNode(
                    invocation.op,
                    invocation.args,
                    Method(allocation.type, invocation.method.name, invocation.method.proto),
                )
        }
    }

    private fun buildSuccessors(
        code: DexCodeNode,
        labels: IdentityHashMap<DexLabel, Int>,
    ): List<Set<Int>> {
        val successors = MutableList(code.stmts.size) { mutableSetOf<Int>() }
        code.stmts.forEachIndexed { index, stmt ->
            if ((stmt.op == null || stmt.op.canContinue()) && index + 1 < code.stmts.size) {
                successors[index] += index + 1
            }
            when (stmt) {
                is JumpStmtNode -> successors[index] += labels.requiredIndex(stmt.label)
                is BaseSwitchStmtNode -> stmt.labels.forEach { successors[index] += labels.requiredIndex(it) }
            }
        }

        code.tryStmts.orEmpty().forEach { region ->
            val start = labels.requiredIndex(region.start)
            val end = labels.requiredIndex(region.end)
            val handlers = region.handler.map { labels.requiredIndex(it) }
            for (index in start until end.coerceAtMost(code.stmts.size)) {
                if (code.stmts[index].op?.canThrow() == true) successors[index] += handlers
            }
        }
        return successors
    }

    private fun transfer(
        stmt: DexStmtNode,
        statementIndex: Int,
        input: RegisterState,
    ): RegisterState {
        val output = input.copy()
        when (stmt) {
            is TypeStmtNode -> {
                when (stmt.op) {
                    Op.NEW_INSTANCE -> {
                        output[stmt.a] = RegisterValue.forAllocation(Allocation(statementIndex, stmt.type))
                    }

                    Op.CHECK_CAST -> {
                        Unit
                    }

                    else -> {
                        output.clear(stmt.a)
                    }
                }
            }

            is Stmt2RNode -> {
                if (stmt.op.isObjectMove()) {
                    output[stmt.a] = input[stmt.b]
                } else {
                    output.clear(stmt.a)
                }
            }

            is Stmt2R1NNode -> {
                output.clear(stmt.distReg)
            }

            is Stmt3RNode -> {
                output.clear(stmt.a)
            }

            is ConstStmtNode -> {
                output.clear(stmt.a)
            }

            is Stmt1RNode -> {
                if (stmt.op.writesSingleRegister()) output.clear(stmt.a)
            }

            is FieldStmtNode -> {
                if (stmt.op.readsFieldIntoRegister()) output.clear(stmt.a)
            }

            is MethodStmtNode -> {
                if (stmt.isDirectConstructor() && stmt.args.isNotEmpty()) {
                    val initialized = input[stmt.args[0]].allocations
                    if (initialized.isNotEmpty()) output.removeAllocations(initialized)
                }
            }
        }
        return output
    }

    private fun isAncestor(
        allocatedType: String,
        invokedOwner: String,
        classes: Map<String, com.googlecode.d2j.node.DexClassNode>,
    ): Boolean {
        var current: String? = allocatedType
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            if (current == invokedOwner) return true
            current = classes[current]?.superClass
        }
        return false
    }

    private fun MethodStmtNode.isDirectConstructor(): Boolean =
        (op == Op.INVOKE_DIRECT || op == Op.INVOKE_DIRECT_RANGE) && method.name == "<init>"

    private fun Op.isObjectMove(): Boolean = this == Op.MOVE_OBJECT || this == Op.MOVE_OBJECT_FROM16 || this == Op.MOVE_OBJECT_16

    private fun Op.writesSingleRegister(): Boolean =
        this == Op.MOVE_RESULT ||
            this == Op.MOVE_RESULT_WIDE ||
            this == Op.MOVE_RESULT_OBJECT ||
            this == Op.MOVE_EXCEPTION

    private fun Op.readsFieldIntoRegister(): Boolean =
        name.startsWith("IGET_") ||
            name == "IGET" ||
            name.startsWith("SGET_") ||
            name == "SGET"

    private fun IdentityHashMap<DexLabel, Int>.requiredIndex(label: DexLabel): Int =
        this[label] ?: throw ExtensionCompatibilityException("invalid DEX control-flow label")

    private fun String.dexTypeToInternalName(): String = removePrefix("L").removeSuffix(";")

    private data class Allocation(
        val statement: Int,
        val type: String,
    )

    private data class RegisterValue(
        val allocations: Set<Allocation>,
        val hasOtherValue: Boolean,
    ) {
        fun merge(other: RegisterValue): RegisterValue =
            RegisterValue(
                allocations = allocations + other.allocations,
                hasOtherValue = hasOtherValue || other.hasOtherValue,
            )

        fun initialized(constructed: Set<Allocation>): RegisterValue {
            val removed = allocations.intersect(constructed)
            return RegisterValue(
                allocations = allocations - constructed,
                hasOtherValue = hasOtherValue || removed.isNotEmpty(),
            )
        }

        companion object {
            val OTHER = RegisterValue(emptySet(), hasOtherValue = true)

            fun forAllocation(allocation: Allocation): RegisterValue = RegisterValue(setOf(allocation), hasOtherValue = false)
        }
    }

    private class RegisterState private constructor(
        private val registers: Array<RegisterValue>,
    ) {
        constructor(registerCount: Int) : this(Array(registerCount) { RegisterValue.OTHER })

        operator fun get(register: Int): RegisterValue = registers.getOrElse(register) { RegisterValue.OTHER }

        operator fun set(
            register: Int,
            value: RegisterValue,
        ) {
            if (register in registers.indices) registers[register] = value
        }

        fun clear(register: Int) {
            if (register in registers.indices) registers[register] = RegisterValue.OTHER
        }

        fun removeAllocations(allocations: Set<Allocation>) {
            registers.indices.forEach { index -> registers[index] = registers[index].initialized(allocations) }
        }

        fun copy(): RegisterState = RegisterState(Array(registers.size) { registers[it] })

        fun merge(other: RegisterState): RegisterState = RegisterState(Array(registers.size) { registers[it].merge(other.registers[it]) })

        override fun equals(other: Any?): Boolean = other is RegisterState && registers.contentEquals(other.registers)

        override fun hashCode(): Int = registers.contentHashCode()
    }

    companion object {
        fun from(reader: BaseDexFileReader): DexConstructorNormalizer {
            val node = DexFileNode()
            reader.accept(node)
            return DexConstructorNormalizer(node)
        }

        internal fun from(node: DexFileNode): DexConstructorNormalizer = DexConstructorNormalizer(node)
    }
}

class ExtensionCompatibilityException(
    detail: String,
    cause: Throwable? = null,
) : IllegalArgumentException("Extension bytecode is not compatible with this platform: $detail", cause)
