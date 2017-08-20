package com.github.overmind.ssagraph.core

import java.util.*

data class Node<out A>(val id: Int,
                       val op: A,
                       val inputs: MutableList<Id> = mutableListOf(),
                       val uses: LinkedList<Use> = LinkedList())

data class Use(val target: Id,
               val inputIx: Int)

typealias Id = Int