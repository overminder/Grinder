package com.github.overmind.ssagraph.core

import java.util.*
import kotlin.coroutines.experimental.buildIterator

data class Graph<A: Operator>(internal val nodes: MutableMap<Id, Node<A>> = mutableMapOf(),
                              internal val argc: Int = 0) {
    internal var idGen: Int = INVALID_ID + 1
    // XXX: How about mutable nodes? Nodes that are killed?
    internal val nodeCache = mutableMapOf<Any, Node<A>>()

    companion object {
        val INVALID_ID = 0
    }

    fun nodeAt(id: Id) = nodes[id]!!

    fun edit() = GraphEditor(this)

    interface Reducer<A: Operator> {
        fun reduce(g: Graph<A>, id: Id): Reduction
    }

    fun <B: A> checkOp(klass: Class<B>, id: Id): Node<B>? {
        val n = nodeAt(id)
        if (klass.isInstance(n.op)) {
            @Suppress("UNCHECKED_CAST")
            return n as Node<B>
        } else {
            return null
        }
    }

    fun <B: A> findOp(klass: Class<B>): Iterator<Node<B>> = buildIterator {
        nodes.values.forEach {
            if (klass.isInstance(it.op)) {
                @Suppress("UNCHECKED_CAST")
                yield(it as Node<B>)
            }
        }
    }
}

data class GraphEditor<A: Operator>(val g: Graph<A>) {
    fun addNode(op: A, vararg inputs: Id): Id {
        val key = op.cacheKey(inputs)
        if (key != null) {
            val cached = g.nodeCache[key]
            if (cached != null && Arrays.equals(cached.inputs.toIntArray(), inputs)) {
                // As the node can be
                return cached.id
            }
        }
        val id = g.idGen++
        val n = Node(id, op)
        g.nodes[id] = n
        inputs.forEach {
            addInput(it, id)
        }
        if (key != null) {
            g.nodeCache[key] = n
        }
        return id
    }

    fun skipIds(n: Int) {
        g.idGen += n
    }

    fun addInput(input: Id, on: Id) {
        val onNode = nodeAt(on)
        val ix = onNode.inputs.size
        onNode.inputs.add(input)
        addUse(Use(on, ix), input)
    }

    fun addInputs(inputs: Sequence<Id>, on: Id) {
        inputs.forEach { addInput(it, on) }
    }

    fun replaceInput(nth: Int, with: Id, on: Id) {
        val onNode = nodeAt(on)
        val old = onNode.inputs[nth]
        if (old == with) {
            return
        }
        val use = Use(target = on, inputIx = nth)
        // Need to add new use before removing old use since the old use might be the only reference
        // to the new use so the other way around will kill the new use immediately.
        addUse(use, with)
        onNode.inputs[nth] = with
        removeUse(use, old)
    }

    private fun addUse(use: Use, on: Id) {
        if (isInvalid(on)) {
            return
        }
        nodeAt(on).uses.addFirst(use)
    }

    private fun removeUse(use: Use, on: Id) {
        if (isInvalid(on)) {
            return
        }
        val onNode = nodeAt(on)
        val it = onNode.uses.iterator()
        var found = false
        while (it.hasNext()) {
            // XXX: Use a HashSet instead?
            val u = it.next()
            if (u == use) {
                it.remove()
                found = true
                break
            }
        }
        if (!found) {
            error("No such use on $on: $use")
        }

        if (onNode.uses.isEmpty()) {
            // Select no one is using this node: kill it.
            kill(on)
        }
    }

    private fun isInvalid(id: Id): Boolean = id == Graph.INVALID_ID

    private fun kill(on: Id) {
        val onNode = nodeAt(on)
        val inputs = onNode.inputs.toList()

        inputs.forEachIndexed { ix, _ ->
            replaceInput(ix, Graph.INVALID_ID, on)
        }
        g.nodes.remove(on)
    }

    fun nodeAt(id: Id) = g.nodeAt(id)

    fun replace(id: Id, with: Id) {
        if (id == with) {
            return
        }
        nodeAt(id).uses.toList().forEach {
            replaceInput(it.inputIx, with, it.target)
        }
    }

    fun reduce(r: Graph.Reducer<A>) {
        val ids = g.nodes.keys.toMutableSet()
        while (true) {
            val id = ids.firstOrNull() ?: break
            ids.remove(id)
            if (!g.nodes.containsKey(id)) {
                // Unvisited nodes could be removed in a previous loop where all its uses were removed.
                continue
            }
            val result= r.reduce(g, id)
            if (result is Reduction.Changed) {
                replace(id, result.id)
                ids += nodeAt(result.id).uses.map { it.target }
                ids += result.id
            }
        }
    }
}

sealed class Reduction {
    object Unchanged: Reduction()
    data class Changed(val id: Id): Reduction()
}

