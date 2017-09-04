package com.github.overmind.grinder.asm

import jnr.ffi.LibraryLoader
import java.io.Closeable
import java.io.File
import java.nio.file.Path

fun File.autoDelete() = Closeable {
    File@this.delete()
}

object AsmRunner {
    fun compileAndLinkDylib(source: String): File {
        return createTempFile("AsmRunner.Dylib", ".so").apply {
            compileAndLinkDylib(source, this)
        }
    }

    fun compileAndLinkDylib(source: String, dst: File) {
        val f = createTempFile("AsmRunner.Source", ".s")
        f.autoDelete().use {
            f.writeText(source)
            val cmd = arrayOf("gcc", "-shared", "-fPIC", f.absolutePath, "-o", dst.absolutePath)
            Runtime.getRuntime().exec(cmd).waitFor()
        }
    }

    fun runDylibLL(src: File, arg: Long): Long {
        val lib = LibraryLoader.create(GrinderDylib::class.java).load(src.absolutePath)
        return lib.grinderEntry(arg)
    }

    val ENTRY_NAME = if (isMac()) {
        "_" + ENTRY_NAME_RAW
    } else {
        ENTRY_NAME_RAW
    }
}

private fun isMac(): Boolean {
    val os = System.getProperty("os.name").toLowerCase()
    return "mac" in os
}

interface GrinderDylib {
    fun grinderEntry(arg: Long): Long
}

private val ENTRY_NAME_RAW = "grinderEntry"