package com.github.overmind.graphlib.impl

import com.github.overmind.graphlib.api.GraphOps
import java.util.*
import kotlin.coroutines.experimental.buildSequence

typealias Id = Int

data class IntNode(val id: Id, var value: Int = id, val successors: MutableList<IntNode> = mutableListOf())
data class IntEdge(val from: IntNode, val to: IntNode, val fromIx: Int)

object IntGraphOps: GraphOps<IntGraph, IntNode, Int> {
    override fun emptyGraph() = IntGraph.empty()

    override fun createNode(g: IntGraph, value: Int): IntNode {
        val n = g.createNode()
        n.value = value
        return n
    }

    override fun value(n: IntNode) = n.value

    override fun successors(n: IntNode) = n.successors

    override fun addSuccessor(g: IntGraph, from: IntNode, to: IntNode) {
        from.successors.add(to)
    }

    override fun toDot(start: IntNode, render: (IntNode) -> String): String {
        return IntGraph.toDot(start, render)
    }
}

class IntGraph private constructor() {
    companion object {
        fun empty() = IntGraph()

        fun build(block: IntGraph.() -> IntNode): IntNode {
            return block(empty())
        }

        fun edges(start: IntNode) = dfs(start).flatMap { from ->
            from.successors.asSequence().mapIndexed { ix, to ->
                IntEdge(from, to, ix)
            }
        }

        fun dfs(start: IntNode) = buildSequence {
            val visited = mutableSetOf<Id>()
            val stack = Stack<IntNode>()
            stack.push(start)

            while (!stack.empty()) {
                val node: IntNode = stack.pop()
                if (node.id !in visited) {
                    visited += node.id
                    yield(node)
                }
                node.successors.reversed() /* XXX: allocates */.forEach {
                    if (it.id !in visited) {
                        stack.push(it)
                    }
                }
            }
        }

        // Serialization in dot style
        fun toDot(start: IntNode, render: (IntNode) -> String): String {
            val sb = StringBuilder("digraph IntGraph {\n")
            edges(start).map { "${render(it.from)} -> ${render(it.to)};\n" }.forEach { sb.append(it) }
            sb.append("}")
            return sb.toString()
        }
    }

    private var nextId = 1
    // private val nodes: MutableMap<Id, IntNode> = mutableMapOf()

    fun createNode(): IntNode {
        val id = nextId++
        val node = IntNode(id)
        // nodes[id] = node
        return node
    }
}
