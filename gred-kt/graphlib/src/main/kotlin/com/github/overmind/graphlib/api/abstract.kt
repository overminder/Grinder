package com.github.overmind.graphlib.api

interface GraphOps<G, N: Any, NV> {
    fun emptyGraph(): G

    fun createNode(g: G, value: NV): N
    fun value(n: N): NV
    fun name(n: N): String = n.toString()
    fun successors(n: N): List<N>
    fun addSuccessor(g: G, from: N, to: N)

    fun toDot(start: N, render: (N) -> String = this::name): String
}