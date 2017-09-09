package com.github.overmind.ssagraph.cli

import com.github.overmind.ssagraph.core.arith.*

fun main(args: Array<String>) {
    fibo()
}

fun fibo() {
    val g = mkG(1) {
        val c1 = addNode(IntLit(1))
        val c2 = addNode(IntLit(2))
        val a1 = addNode(Argument(0))
        val lt = addNode(Lt, a1, c2)
        val sub1 = addNode(Sub, a1, c1)
        val sub2 = addNode(Sub, a1, c2)
        val ifF1 = addNode(KnownApply(g), sub1)
        val ifF2 = addNode(KnownApply(g), sub2)
        val ifF = addNode(Add, ifF1, ifF2)
        addNode(Select, lt, a1, ifF)
    }
    interp(g, emptyList(),10)
}

fun simpleAdd() {
    val funcAdd = mkG(2) {
        val n1 = addNode(Argument(0))
        val n2 = addNode(Argument(1))
        val nif = addNode(Select, n1, n2, n2)
        addNode(Add, n1, nif)
    }
    interp(mkG {
        val n1 = addNode(IntLit(1))
        val n2 = addNode(IntLit(2))
        val na = addNode(KnownApply(funcAdd), n1, n2)
        na
    }, listOf(funcAdd))
}

private fun interp(g: AGraph, moreGraphs: List<AGraph>, vararg args: Int) {
    val interp = ArithInterpreter(g, args)
    println("Unoptimized: ${interp.eval()}")
    (moreGraphs + g).forEach {
        it.edit().reduce(ArithReducer)
    }
    println("Optimized: ${interp.eval()}")
}
