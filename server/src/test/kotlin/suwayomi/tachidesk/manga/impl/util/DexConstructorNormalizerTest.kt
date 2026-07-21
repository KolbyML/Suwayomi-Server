package suwayomi.tachidesk.manga.impl.util

import com.googlecode.d2j.DexLabel
import com.googlecode.d2j.Method
import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexCodeNode
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.node.DexMethodNode
import com.googlecode.d2j.node.insn.MethodStmtNode
import com.googlecode.d2j.reader.Op
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes

class DexConstructorNormalizerTest {
    @Test
    fun `tracks one allocation through reordered control-flow aliases`() {
        val dex = DexFileNode()
        addClass(dex, "Lwrapped;", "Ljava/lang/Object;")
        val factory = addClass(dex, "Lf;", "Ljava/lang/Object;")
        val method = addMethod(factory, "branch", registerCount = 3)
        val alternate = DexLabel()
        val joined = DexLabel()
        method.codeNode.apply {
            visitTypeStmt(Op.NEW_INSTANCE, 0, 0, "Lwrapped;")
            visitJumpStmt(Op.IF_EQZ, 2, 0, alternate)
            visitStmt2R(Op.MOVE_OBJECT, 1, 0)
            visitJumpStmt(Op.GOTO, 0, 0, joined)
            visitLabel(alternate)
            visitStmt2R(Op.MOVE_OBJECT_FROM16, 1, 0)
            visitLabel(joined)
            visitMethodStmt(Op.INVOKE_DIRECT, intArrayOf(1), constructor("Ljava/lang/Object;"))
            visitStmt0R(Op.RETURN_VOID)
        }

        val normalized = DexConstructorNormalizer.from(dex)

        assertEquals(
            setOf(ForwardingConstructor("wrapped", "java/lang/Object", "()V")),
            normalized.forwardingConstructors,
        )
        assertEquals(
            "Lwrapped;",
            method.codeNode.stmts
                .filterIsInstance<MethodStmtNode>()
                .single()
                .method.owner,
        )
    }

    @Test
    fun `rejects a constructor receiver merged with a non-allocation value`() {
        val dex = DexFileNode()
        addClass(dex, "Lwrapped;", "Ljava/lang/Object;")
        val factory = addClass(dex, "Lf;", "Ljava/lang/Object;")
        val method = addMethod(factory, "ambiguous", registerCount = 3)
        val alternate = DexLabel()
        val joined = DexLabel()
        method.codeNode.apply {
            visitTypeStmt(Op.NEW_INSTANCE, 0, 0, "Lwrapped;")
            visitJumpStmt(Op.IF_EQZ, 2, 0, alternate)
            visitStmt2R(Op.MOVE_OBJECT, 1, 0)
            visitJumpStmt(Op.GOTO, 0, 0, joined)
            visitLabel(alternate)
            visitConstStmt(Op.CONST_4, 1, 0)
            visitLabel(joined)
            visitMethodStmt(Op.INVOKE_DIRECT, intArrayOf(1), constructor("Ljava/lang/Object;"))
            visitStmt0R(Op.RETURN_VOID)
        }

        assertThrows(ExtensionCompatibilityException::class.java) {
            DexConstructorNormalizer.from(dex)
        }
    }

    @Test
    fun `tracks nested and aliased allocations by DEX register`() {
        val dex = DexFileNode()
        addClass(dex, "La;", "Ljava/lang/Object;")
        addClass(dex, "Lb;", "Ljava/lang/Object;")
        val factory = addClass(dex, "Lc;", "Ljava/lang/Object;")
        val method = addMethod(factory, "nested", registerCount = 4)
        method.codeNode.apply {
            visitTypeStmt(Op.NEW_INSTANCE, 0, 0, "La;")
            visitTypeStmt(Op.NEW_INSTANCE, 1, 0, "Lb;")
            visitStmt2R(Op.MOVE_OBJECT, 3, 0)
            visitMethodStmt(Op.INVOKE_DIRECT, intArrayOf(3), constructor("Ljava/lang/Object;"))
            visitMethodStmt(Op.INVOKE_DIRECT, intArrayOf(1, 0), constructor("Lb;", "La;"))
            visitStmt0R(Op.RETURN_VOID)
        }

        val normalized = DexConstructorNormalizer.from(dex)
        val constructors = method.codeNode.stmts.filterIsInstance<MethodStmtNode>()

        assertEquals(listOf("La;", "Lb;"), constructors.map { it.method.owner })
        assertEquals(
            setOf(ForwardingConstructor("a", "java/lang/Object", "()V")),
            normalized.forwardingConstructors,
        )
    }

    @Test
    fun `keeps distinct constructorless subclasses sharing a superclass`() {
        val dex = DexFileNode()
        addClass(dex, "Lbase;", "Ljava/lang/Object;")
        addClass(dex, "Lx;", "Lbase;")
        addClass(dex, "Ly;", "Lbase;")
        val factory = addClass(dex, "Lf;", "Ljava/lang/Object;")
        val method = addMethod(factory, "make", registerCount = 2)
        method.codeNode.apply {
            visitTypeStmt(Op.NEW_INSTANCE, 0, 0, "Lx;")
            visitMethodStmt(Op.INVOKE_DIRECT, intArrayOf(0), constructor("Lbase;"))
            visitTypeStmt(Op.NEW_INSTANCE, 1, 0, "Ly;")
            visitMethodStmt(Op.INVOKE_DIRECT, intArrayOf(1), constructor("Lbase;"))
            visitStmt0R(Op.RETURN_VOID)
        }

        val normalized = DexConstructorNormalizer.from(dex)

        assertEquals(
            setOf(
                ForwardingConstructor("x", "base", "()V"),
                ForwardingConstructor("y", "base", "()V"),
            ),
            normalized.forwardingConstructors,
        )
        assertEquals(
            listOf("Lx;", "Ly;"),
            method.codeNode.stmts
                .filterIsInstance<MethodStmtNode>()
                .map { it.method.owner },
        )
    }

    @Test
    fun `preserves constructor arguments and skipped superclass relationship`() {
        val dex = DexFileNode()
        addClass(dex, "Lbase;", "Ljava/lang/Object;")
        addClass(dex, "Lmid;", "Lbase;")
        addClass(dex, "Lz;", "Lmid;")
        val factory = addClass(dex, "Lf;", "Ljava/lang/Object;")
        val method = addMethod(factory, "make", registerCount = 2)
        method.codeNode.apply {
            visitTypeStmt(Op.NEW_INSTANCE, 0, 0, "Lz;")
            visitConstStmt(Op.CONST_4, 1, 7)
            visitMethodStmt(Op.INVOKE_DIRECT, intArrayOf(0, 1), constructor("Lbase;", "I"))
            visitStmt0R(Op.RETURN_VOID)
        }

        val normalized = DexConstructorNormalizer.from(dex)

        assertEquals(
            setOf(ForwardingConstructor("z", "base", "(I)V")),
            normalized.forwardingConstructors,
        )
        assertEquals(
            "Lz;",
            method.codeNode.stmts
                .filterIsInstance<MethodStmtNode>()
                .single()
                .method.owner,
        )
    }

    @Test
    fun `leaves existing constructor calls unchanged`() {
        val dex = DexFileNode()
        val existing = addClass(dex, "Lkept;", "Ljava/lang/Object;")
        addMethod(existing, "<init>", registerCount = 2, parameters = arrayOf("I"))
            .codeNode
            .visitStmt0R(Op.RETURN_VOID)
        val factory = addClass(dex, "Lf;", "Ljava/lang/Object;")
        val method = addMethod(factory, "make", registerCount = 2)
        method.codeNode.apply {
            visitTypeStmt(Op.NEW_INSTANCE, 0, 0, "Lkept;")
            visitConstStmt(Op.CONST_4, 1, 1)
            visitMethodStmt(Op.INVOKE_DIRECT, intArrayOf(0, 1), constructor("Lkept;", "I"))
            visitStmt0R(Op.RETURN_VOID)
        }

        val normalized = DexConstructorNormalizer.from(dex)

        assertTrue(normalized.forwardingConstructors.isEmpty())
        assertEquals(
            "Lkept;",
            method.codeNode.stmts
                .filterIsInstance<MethodStmtNode>()
                .single()
                .method.owner,
        )
    }

    private fun addClass(
        dex: DexFileNode,
        name: String,
        superName: String,
    ): DexClassNode = dex.visit(Opcodes.ACC_PUBLIC, name, superName, emptyArray()) as DexClassNode

    private fun addMethod(
        owner: DexClassNode,
        name: String,
        registerCount: Int,
        parameters: Array<String> = emptyArray(),
    ): DexMethodNode =
        (
            owner.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                Method(owner.className, name, parameters, "V"),
            ) as DexMethodNode
        ).also { method ->
            method.codeNode = DexCodeNode().apply { visitRegister(registerCount) }
        }

    private fun constructor(
        owner: String,
        vararg parameters: String,
    ): Method = Method(owner, "<init>", parameters, "V")
}
