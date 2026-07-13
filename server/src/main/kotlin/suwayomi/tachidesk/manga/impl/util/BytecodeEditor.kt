package suwayomi.tachidesk.manga.impl.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object BytecodeEditor {
    private val logger = KotlinLogging.logger {}

    /**
     * Replace some java class references inside a jar with new ones that behave like Androids
     *
     * @param jarFile The JarFile to replace class references in
     */
    fun fixAndroidClasses(jarFile: Path) {
        rewriteClasses(jarFile, replaceAndroidClasses = true)
    }

    /**
     * Rebuild stack map frames in an already converted extension jar.
     *
     * Some JVMs accept the frame-less Java 6 bytecode emitted by dex2jar while stricter
     * verifiers reject it. This is separate from [fixAndroidClasses] so callers can safely
     * repair existing jars without applying the Android class substitutions a second time.
     */
    fun repairStackMapFrames(jarFile: Path) {
        rewriteClasses(jarFile, replaceAndroidClasses = false)
    }

    private fun rewriteClasses(
        jarFile: Path,
        replaceAndroidClasses: Boolean,
    ) {
        FileSystems.newFileSystem(jarFile, null as ClassLoader?)?.use { fileSystem ->
            val classFiles =
                Files.walk(fileSystem.getPath("/")).use { paths ->
                    paths
                        .filter { path -> !Files.isDirectory(path) }
                        .map(::getClassBytes)
                        .filter { pair -> pair != null }
                        .map { pair -> pair!! }
                        .toList()
                }
            val hierarchy = ClassHierarchy(classFiles.map { it.second })
            val transformed =
                classFiles.map { pair ->
                    transform(pair, hierarchy, replaceAndroidClasses)
                }
            transformed.forEach(::write)
        }
    }

    /**
     * Get class bytes from a [Path]
     *
     * @param path The path entry to get the class bytes from
     *
     * @return [Pair] of the [Path] plus the class [ByteArray], or null if it's not a valid class
     */
    private fun getClassBytes(path: Path): Pair<Path, ByteArray>? {
        return try {
            if (path.toString().endsWith(".class")) {
                val bytes = Files.readAllBytes(path)
                if (bytes.size < 4) {
                    // Invalid class size
                    return null
                }
                val cafebabe =
                    String.format(
                        "%02X%02X%02X%02X",
                        bytes[0],
                        bytes[1],
                        bytes[2],
                        bytes[3],
                    )
                if (cafebabe.lowercase() != "cafebabe") {
                    // Corrupted class
                    return null
                }

                path to bytes
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Error loading class from Path: $path" }
            null
        }
    }

    /**
     * The path where replacement classes will reside
     */
    private const val REPLACEMENT_PATH = "xyz/nulldev/androidcompat/replace"

    /**
     * List of classes that will be replaced
     */
    private val classesToReplace =
        listOf(
            "java/text/SimpleDateFormat",
        )

    /**
     * Replace direct references to the class, used on places
     * that don't have any other text then the class
     *
     * @return [String] of class or null if [String] was null
     */
    private fun String?.replaceDirectly() =
        when (this) {
            null -> null
            in classesToReplace -> "$REPLACEMENT_PATH/$this"
            else -> this
        }

    /**
     * Replace references to the class, used in places that have
     * other text around the class references
     *
     * @return [String] with class references replaced, or null if [String] was null
     */
    private fun String?.replaceIndirectly(): String? {
        if (this == null) return null
        var classReference: String = this
        classesToReplace.forEach {
            classReference = classReference.replace(it, "$REPLACEMENT_PATH/$it")
        }
        return classReference
    }

    /**
     * Replace all references to certain classes inside the class file
     * with ones that behave more like Androids
     *
     * @param pair Class bytecode to load into ASM for ease of modification
     *
     * @return [ByteArray] with modified bytecode
     */
    private fun transform(
        pair: Pair<Path, ByteArray>,
        hierarchy: ClassHierarchy,
        replaceAndroidClasses: Boolean,
    ): Pair<Path, ByteArray> {
        // Read the class and prepare to modify it
        val cr = ClassReader(pair.second)
        val cw = FrameComputingClassWriter(hierarchy)
        if (!replaceAndroidClasses) {
            cr.accept(cw, ClassReader.SKIP_FRAMES)
            return pair.first to cw.toByteArray()
        }
        // Modify the class
        cr.accept(
            object : ClassVisitor(Opcodes.ASM5, cw) {
                // Modify field descriptor, for example
                // class MangaYes {
                //     val format = SimpleDateFormat("YYYY-MM-dd")
                // }
                override fun visitField(
                    access: Int,
                    name: String?,
                    desc: String?,
                    signature: String?,
                    cst: Any?,
                ): FieldVisitor? {
                    logger.trace { "CLass Field" to "${desc.replaceIndirectly()}: ${cst?.let { it::class.java.simpleName }}: $cst" }
                    return super.visitField(access, name, desc.replaceIndirectly(), signature, cst)
                }

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    logger.trace { "Visiting $name: $signature: $superName" }
                    super.visit(version, access, name, signature, superName, interfaces)
                }

                // Modify method bytecode, for example
                // class MangaYes {
                //     fun fetchChapterList() {
                //         SimpleDateFormat("YYYY-MM-dd")
                //     }
                // }
                override fun visitMethod(
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    exceptions: Array<String?>?,
                ): MethodVisitor {
                    logger.trace { "Processing method $name: ${desc.replaceIndirectly()}: $signature" }
                    val mv: MethodVisitor? =
                        super.visitMethod(
                            access,
                            name,
                            desc.replaceIndirectly(),
                            signature,
                            exceptions,
                        )
                    return object : MethodVisitor(Opcodes.ASM5, mv) {
                        override fun visitLdcInsn(cst: Any?) {
                            logger.trace { "Ldc" to "${cst?.let { "${it::class.java.simpleName}: $it" }}" }
                            super.visitLdcInsn(cst)
                        }

                        // Replace method type, for example
                        // val format = DateFormat()
                        // fun fetchChapterList() {
                        //     if (format is SimpleDateFormat)
                        // }
                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String?,
                        ) {
                            logger.trace {
                                "Type" to "$opcode: ${type.replaceDirectly()}"
                            }
                            super.visitTypeInsn(
                                opcode,
                                type.replaceDirectly(),
                            )
                        }

                        // Replace method field, for example
                        // fun fetchChapterList() {
                        //     val format = SimpleDateFormat("YYYY-MM-dd")
                        // }
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                            itf: Boolean,
                        ) {
                            logger.trace {
                                "Method" to "$opcode: ${owner.replaceDirectly()}: $name: ${desc.replaceIndirectly()}"
                            }
                            super.visitMethodInsn(
                                opcode,
                                owner.replaceDirectly(),
                                name,
                                desc.replaceIndirectly(),
                                itf,
                            )
                        }

                        // Replace class field call from method, for example
                        // val format = SimpleDateFormat("YYYY-MM-dd")
                        // fun fetchChapterList() {
                        //     format.format(Date())
                        // }
                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                        ) {
                            logger.trace { "Field" to "$opcode: $owner: $name: ${desc.replaceIndirectly()}" }
                            super.visitFieldInsn(opcode, owner, name, desc.replaceIndirectly())
                        }

                        override fun visitInvokeDynamicInsn(
                            name: String?,
                            desc: String?,
                            bsm: Handle?,
                            vararg bsmArgs: Any?,
                        ) {
                            logger.trace { "InvokeDynamic" to "$name: $desc" }
                            super.visitInvokeDynamicInsn(name, desc, bsm, *bsmArgs)
                        }
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        return pair.first to cw.toByteArray()
    }

    private data class ClassInfo(
        val superName: String?,
        val interfaces: List<String>,
        val isInterface: Boolean,
    )

    private class FrameComputingClassWriter(
        private val hierarchy: ClassHierarchy,
    ) : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
        override fun getCommonSuperClass(
            type1: String,
            type2: String,
        ): String = hierarchy.commonSuperClass(type1, type2)
    }

    private class ClassHierarchy(classBytes: List<ByteArray>) {
        private val classInfo = mutableMapOf<String, ClassInfo>()
        private val missingClasses = mutableSetOf<String>()

        init {
            classBytes.forEach { bytes ->
                val reader = ClassReader(bytes)
                classInfo[reader.className] = reader.toClassInfo()
            }
        }

        fun commonSuperClass(
            type1: String,
            type2: String,
        ): String {
            if (type1 == type2) return type1
            if (isAssignableFrom(type1, type2)) return type1
            if (isAssignableFrom(type2, type1)) return type2

            if (type1.startsWith("[") && type2.startsWith("[")) {
                val component1 = type1.substring(1)
                val component2 = type2.substring(1)
                if (!isPrimitiveDescriptor(component1) && !isPrimitiveDescriptor(component2)) {
                    val commonComponent =
                        commonSuperClass(
                            descriptorToType(component1),
                            descriptorToType(component2),
                        )
                    return "[${typeToDescriptor(commonComponent)}"
                }
            }

            if (type1.startsWith("[") || type2.startsWith("[")) {
                return OBJECT_CLASS
            }

            val firstInfo = resolve(type1)
            val secondInfo = resolve(type2)
            if (firstInfo?.isInterface == true || secondInfo?.isInterface == true) {
                return OBJECT_CLASS
            }

            var candidate = firstInfo?.superName
            while (candidate != null) {
                if (isAssignableFrom(candidate, type2)) {
                    return candidate
                }
                candidate = resolve(candidate)?.superName
            }
            return OBJECT_CLASS
        }

        private fun isAssignableFrom(
            target: String,
            source: String,
        ): Boolean = isAssignableFrom(target, source, mutableSetOf())

        private fun isAssignableFrom(
            target: String,
            source: String,
            visited: MutableSet<String>,
        ): Boolean {
            if (target == source) return true
            if (source.startsWith("[")) {
                if (target == OBJECT_CLASS || target == CLONEABLE_CLASS || target == SERIALIZABLE_CLASS) {
                    return true
                }
                if (!target.startsWith("[")) return false
                val targetComponent = target.substring(1)
                val sourceComponent = source.substring(1)
                if (isPrimitiveDescriptor(targetComponent) || isPrimitiveDescriptor(sourceComponent)) {
                    return targetComponent == sourceComponent
                }
                return isAssignableFrom(
                    descriptorToType(targetComponent),
                    descriptorToType(sourceComponent),
                    visited,
                )
            }
            if (target.startsWith("[")) return false
            if (target == OBJECT_CLASS) return true
            if (!visited.add(source)) return false

            val sourceInfo = resolve(source) ?: return false
            return sourceInfo.superName?.let { isAssignableFrom(target, it, visited) } == true ||
                sourceInfo.interfaces.any { isAssignableFrom(target, it, visited) }
        }

        private fun resolve(name: String): ClassInfo? {
            classInfo[name]?.let { return it }
            if (name in missingClasses || name.startsWith("[")) return null

            val resourceName = "$name.class"
            val stream =
                BytecodeEditor::class.java.classLoader?.getResourceAsStream(resourceName)
                    ?: ClassLoader.getSystemResourceAsStream(resourceName)
            val resolved =
                stream?.use { input ->
                    runCatching { ClassReader(input).toClassInfo() }.getOrNull()
                } ?: resolveRuntimeClass(name)
            if (resolved == null) {
                missingClasses += name
            } else {
                classInfo[name] = resolved
            }
            return resolved
        }

        private fun resolveRuntimeClass(name: String): ClassInfo? =
            runCatching {
                val clazz =
                    Class.forName(
                        name.replace('/', '.'),
                        false,
                        BytecodeEditor::class.java.classLoader,
                    )
                ClassInfo(
                    superName = clazz.superclass?.name?.replace('.', '/'),
                    interfaces = clazz.interfaces.map { it.name.replace('.', '/') },
                    isInterface = clazz.isInterface,
                )
            }.getOrNull()

        private fun ClassReader.toClassInfo(): ClassInfo =
            ClassInfo(
                superName = superName,
                interfaces = interfaces.toList(),
                isInterface = access and Opcodes.ACC_INTERFACE != 0,
            )

        private fun isPrimitiveDescriptor(descriptor: String): Boolean =
            descriptor.length == 1 && descriptor[0] in "ZCBSIFJDV"

        private fun descriptorToType(descriptor: String): String =
            if (descriptor.startsWith("L")) {
                Type.getType(descriptor).internalName
            } else {
                descriptor
            }

        private fun typeToDescriptor(type: String): String =
            if (type.startsWith("[")) {
                type
            } else {
                "L$type;"
            }

        private companion object {
            const val OBJECT_CLASS = "java/lang/Object"
            const val CLONEABLE_CLASS = "java/lang/Cloneable"
            const val SERIALIZABLE_CLASS = "java/io/Serializable"
        }
    }

    private fun write(pair: Pair<Path, ByteArray>) {
        Files.write(
            pair.first,
            pair.second,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
