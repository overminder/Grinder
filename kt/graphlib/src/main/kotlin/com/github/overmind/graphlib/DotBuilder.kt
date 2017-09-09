package com.github.overmind.graphlib

class DotBuilder {
    private val edges = mutableListOf<Edge>()

    private class Edge(val from: String, val to: String)

    fun addEdge(from: String, to: String) {
        edges += Edge(from, to)
    }

    fun render(): String {
        val sb = StringBuilder("digraph IntGraph {\n")
        edges.forEach {
            sb.append("${it.from} -> ${it.to};\n")
        }
        sb.append("}")
        return sb.toString()
    }
}