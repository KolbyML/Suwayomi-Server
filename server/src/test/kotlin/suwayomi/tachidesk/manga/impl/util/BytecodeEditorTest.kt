package suwayomi.tachidesk.manga.impl.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `repairs optimized constructors lost by dex2jar`() {
        val jarPath = tempDir.resolve("optimized-constructors.jar")
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zip ->
            zip.putNextEntry(ZipEntry("test/OptimizedFactory.class"))
            zip.write(optimizedFactoryClass())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("test/OptimizedTag.class"))
            zip.write(optimizedTagClass())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("test/ArgumentBase.class"))
            zip.write(argumentBaseClass())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("test/OptimizedSubclass.class"))
            zip.write(optimizedSubclassClass())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("test/SkippedSuperclass.class"))
            zip.write(skippedSuperclassClass())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("test/ExistingSubclass.class"))
            zip.write(existingSubclassClass())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("test/OptimizedAdapter.class"))
            zip.write(optimizedAdapterClass())
            zip.closeEntry()
        }

        BytecodeEditor.fixAndroidClasses(
            jarPath,
            setOf(
                ForwardingConstructor(
                    allocatedType = "test/OptimizedFactory",
                    invokedOwner = "java/lang/Object",
                    descriptor = "()V",
                ),
                ForwardingConstructor(
                    allocatedType = "test/OptimizedSubclass",
                    invokedOwner = "test/ArgumentBase",
                    descriptor = "(I)V",
                ),
                ForwardingConstructor(
                    allocatedType = "test/OptimizedTag",
                    invokedOwner = "java/lang/Object",
                    descriptor = "()V",
                ),
            ),
        )

        URLClassLoader(arrayOf(jarPath.toUri().toURL()), javaClass.classLoader).use { loader ->
            val factoryClass = loader.loadClass("test.OptimizedFactory")
            val singleton = factoryClass.getField("singleton").get(null)
            assertTrue(factoryClass.isInstance(singleton))

            val tagClass = loader.loadClass("test.OptimizedTag")
            val tag = factoryClass.getMethod("makeTag").invoke(null)
            assertTrue(tagClass.isInstance(tag))
            assertTrue(!tagClass.getField("flag").getBoolean(tag))

            val subclassClass = loader.loadClass("test.OptimizedSubclass")
            val subclass = subclassClass.getField("singleton").get(null)
            assertTrue(subclassClass.isInstance(subclass))
            assertTrue(subclassClass.getField("value").getInt(subclass) == 7)

            val opaqueSubclass = factoryClass.getMethod("makeOpaque").invoke(null)
            assertTrue(subclassClass.isInstance(opaqueSubclass))
            assertTrue(subclassClass.getField("value").getInt(opaqueSubclass) == 9)

            val optimizedTagClass = loader.loadClass("test.OptimizedTag")
            val nested = factoryClass.getMethod("makeNested").invoke(null)
            val adapterClass = loader.loadClass("test.OptimizedAdapter")
            assertTrue(adapterClass.isInstance(nested))
            assertTrue(optimizedTagClass.isInstance(adapterClass.getField("tag").get(nested)))

            val existingSubclass = loader.loadClass("test.ExistingSubclass")
            val existing = existingSubclass.getConstructor(String::class.java).newInstance("retained")
            assertTrue(existingSubclass.getField("value").get(existing) == "retained")
        }
    }

    @Test
    fun `rejects unverifiable output without replacing installed jar`() {
        val installed = tempDir.resolve("installed.jar")
        val staged = tempDir.resolve("staged.jar")
        val original = "known-good-installation".toByteArray()
        Files.write(installed, original)
        ZipOutputStream(Files.newOutputStream(staged)).use { zip ->
            zip.putNextEntry(ZipEntry("test/Invalid.class"))
            zip.write(invalidClass())
            zip.closeEntry()
        }

        val error =
            assertThrows(ExtensionCompatibilityException::class.java) {
                PackageTools.promoteVerifiedJar(staged, installed)
            }

        assertTrue(error.message.orEmpty().contains("test.Invalid"))
        assertArrayEquals(original, Files.readAllBytes(installed))
        assertTrue(Files.exists(staged))
    }

    @Test
    fun `converted jar cache key includes APK hash and converter version`() {
        val apk = "same-apk".toByteArray()

        val current = PackageTools.convertedJarCacheKey(apk, "converter-v1")

        assertTrue(current.endsWith("-converter-v1.jar"))
        assertNotEquals(current, PackageTools.convertedJarCacheKey("different-apk".toByteArray(), "converter-v1"))
        assertNotEquals(current, PackageTools.convertedJarCacheKey(apk, "converter-v2"))
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

    private fun invalidClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Invalid", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "broken", "()I", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun optimizedFactoryClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "test/OptimizedFactory",
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "singleton",
                "Ltest/OptimizedFactory;",
                null,
                null,
            ).visitEnd()

        writer
            .visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
            .apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "test/OptimizedFactory")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "test/OptimizedFactory", "<init>", "()V", false)
                visitFieldInsn(
                    Opcodes.PUTSTATIC,
                    "test/OptimizedFactory",
                    "singleton",
                    "Ltest/OptimizedFactory;",
                )
                visitInsn(Opcodes.RETURN)
                visitMaxs(2, 0)
                visitEnd()
            }

        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "makeTag",
                "()Ltest/OptimizedTag;",
                null,
                null,
            ).apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "test/OptimizedTag")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "test/OptimizedTag", "<init>", "()V", false)
                visitVarInsn(Opcodes.ASTORE, 0)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ICONST_0)
                visitFieldInsn(Opcodes.PUTFIELD, "test/OptimizedTag", "flag", "Z")
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(2, 1)
                visitEnd()
            }

        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "makeOpaque",
                "()Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                // An unrelated allocation before the broken one must not stop the repair scan.
                visitTypeInsn(Opcodes.NEW, "java/lang/Object")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.POP)
                visitTypeInsn(Opcodes.NEW, "test/OptimizedSubclass")
                visitInsn(Opcodes.DUP)
                visitIntInsn(Opcodes.BIPUSH, 9)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "test/OptimizedSubclass", "<init>", "(I)V", false)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "test/OptimizedFactory",
                    "identity",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false,
                )
                visitInsn(Opcodes.ARETURN)
                visitMaxs(3, 0)
                visitEnd()
            }
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "makeNested",
                "()Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                // dex2jar reverses these nested allocations compared with their DEX order.
                visitTypeInsn(Opcodes.NEW, "test/OptimizedAdapter")
                visitInsn(Opcodes.DUP)
                visitTypeInsn(Opcodes.NEW, "test/OptimizedTag")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "test/OptimizedTag", "<init>", "()V", false)
                visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "test/OptimizedAdapter",
                    "<init>",
                    "(Ltest/OptimizedTag;)V",
                    false,
                )
                visitInsn(Opcodes.ARETURN)
                visitMaxs(4, 0)
                visitEnd()
            }
        writer
            .visitMethod(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
                "identity",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun optimizedTagClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "test/OptimizedTag",
            null,
            "java/lang/Object",
            null,
        )
        writer.visitField(Opcodes.ACC_PUBLIC, "flag", "Z", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun optimizedAdapterClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "test/OptimizedAdapter",
            null,
            "java/lang/Object",
            null,
        )
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "tag", "Ltest/OptimizedTag;", null, null).visitEnd()
        writer
            .visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ltest/OptimizedTag;)V", null, null)
            .apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitFieldInsn(Opcodes.PUTFIELD, "test/OptimizedAdapter", "tag", "Ltest/OptimizedTag;")
                visitInsn(Opcodes.RETURN)
                visitMaxs(2, 2)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun argumentBaseClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC,
            "test/ArgumentBase",
            null,
            "java/lang/Object",
            null,
        )
        writer.visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd()
        writer
            .visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null)
            .apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ILOAD, 1)
                visitFieldInsn(Opcodes.PUTFIELD, "test/ArgumentBase", "value", "I")
                visitInsn(Opcodes.RETURN)
                visitMaxs(2, 2)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun optimizedSubclassClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "test/OptimizedSubclass",
            null,
            "test/ArgumentBase",
            null,
        )
        writer
            .visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                "singleton",
                "Ltest/OptimizedSubclass;",
                null,
                null,
            ).visitEnd()
        writer
            .visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
            .apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "test/OptimizedSubclass")
                visitInsn(Opcodes.DUP)
                visitIntInsn(Opcodes.BIPUSH, 7)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "test/OptimizedSubclass", "<init>", "(I)V", false)
                visitFieldInsn(
                    Opcodes.PUTSTATIC,
                    "test/OptimizedSubclass",
                    "singleton",
                    "Ltest/OptimizedSubclass;",
                )
                visitInsn(Opcodes.RETURN)
                visitMaxs(3, 0)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun skippedSuperclassClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "test/SkippedSuperclass",
            null,
            "java/lang/Object",
            null,
        )
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun existingSubclassClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_6,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "test/ExistingSubclass",
            null,
            "test/SkippedSuperclass",
            null,
        )
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "value", "Ljava/lang/String;", null, null).visitEnd()
        writer
            .visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null)
            .apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
                visitInsn(Opcodes.POP)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitFieldInsn(Opcodes.PUTFIELD, "test/ExistingSubclass", "value", "Ljava/lang/String;")
                visitInsn(Opcodes.RETURN)
                visitMaxs(2, 2)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
