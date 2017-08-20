package com.github.overmind.ssagraph.cli

import com.github.overmind.ssagraph.core.arith.*

fun main(args: Array<String>) {
    val funcAdd = mkG {
        val n1 = addNode(Argument(0))
        val n2 = addNode(Argument(1))
        val nif = addNode(If, n1, n2, n2)
        addNode(Add, n1, nif)
    }.copy(argc = 2)
    interp(mkG {
        val n1 = addNode(IntLit(1))
        val n2 = addNode(IntLit(2))
        val na = addNode(InlineCall(funcAdd), n1, n2)
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
