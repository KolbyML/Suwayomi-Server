package suwayomi.tachidesk.manga.impl.util

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BytecodeEditorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `repairs missing stack map frames`() {
        val jarPath = tempDir.resolve("frame-less.jar")
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zip ->
            zip.putNextEntry(ZipEntry("test/FrameLess.class"))
            zip.write(frameLessClass())
            zip.closeEntry()
        }

        BytecodeEditor.repairStackMapFrames(jarPath)

        val repairedBytes =
            ZipFile(jarPath.toFile()).use { zip ->
                zip.getInputStream(zip.getEntry("test/FrameLess.class")).readAllBytes()
            }
        var frameCount = 0
        ClassReader(repairedBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitFrame(
                            type: Int,
                            numLocal: Int,
                            local: Array<out Any>?,
                            numStack: Int,
                            stack: Array<out Any>?,
                        ) {
                            frameCount += 1
                        }
                    }
            },
            0,
        )
        assertTrue(frameCount > 0)

        URLClassLoader(arrayOf(jarPath.toUri().toURL()), javaClass.classLoader).use { loader ->
            val repairedClass = loader.loadClass("test.FrameLess")
            val choose = repairedClass.getMethod("choose", Boolean::class.javaPrimitiveType)
            assertTrue(choose.invoke(null, true) == "yes")
            assertTrue(choose.invoke(null, false) == "no")
        }
    }

    private fun frameLessClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "test/FrameLess",
            null,
            "java/lang/Object",
            null,
        )

        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "choose",
                "(Z)Ljava/lang/String;",
                null,
                null,
            ).apply {
                visitCode()
                val falseBranch = org.objectweb.asm.Label()
                visitVarInsn(Opcodes.ILOAD, 0)
                visitJumpInsn(Opcodes.IFEQ, falseBranch)
                visitLdcInsn("yes")
                visitInsn(Opcodes.ARETURN)
                visitLabel(falseBranch)
                visitLdcInsn("no")
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
