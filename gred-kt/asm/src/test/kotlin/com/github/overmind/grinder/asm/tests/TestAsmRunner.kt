package com.github.overmind.grinder.asm.tests

import com.github.overmind.grinder.asm.AsmRunner
import com.github.overmind.grinder.asm.autoDelete
import org.junit.Assert
import org.junit.Test

class TestAsmRunner {
    @Test
    fun testX64Ret() {
        val dylib = AsmRunner.compileAndLinkDylib("""
            .globl ${AsmRunner.ENTRY_NAME}
            ${AsmRunner.ENTRY_NAME}:
                leal 1(%edi), %eax
                ret
        """.trimIndent())
        dylib.autoDelete().use {
            val res = AsmRunner.runDylibLL(dylib, 41)
            Assert.assertEquals(42, res)
        }
    }
}
