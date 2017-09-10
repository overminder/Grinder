package com.github.overmind.grinder.asm.tests

import com.github.overmind.grinder.asm.AsmRunner
import com.github.overmind.grinder.asm.AtntSyntax
import com.github.overmind.grinder.asm.autoDelete
import java.io.Closeable
import java.io.File


object AsmTestUtils {
    class Dylib internal constructor(val dylibPath: File): Closeable {
        fun call(arg: Long): Long {
            return AsmRunner.runDylibLL(dylibPath, arg)
        }

        override fun close() {
            dylibPath.delete()
        }
    }

    fun run(source: String, block: Dylib.() -> Unit) {
        val dylib = AsmRunner.compileAndLinkDylib(source)
        block(Dylib(dylib))
    }
}