package sample

// A deliberately messy file used to demo the "Copy All Problems" action.
// Opening this in the IDE surfaces a mix of ERROR / WARNING / WEAK WARNING
// diagnostics that you can then copy to the clipboard as a single block.

import java.util.ArrayList          // unused import -> WARNING
import java.io.File                 // unused import -> WARNING

class SampleWithProblems {

    // Property is never used -> WEAK WARNING
    private val unusedField: Int = 42

    // 'name' parameter is never used -> WEAK WARNING
    fun greet(name: String) {
        val message = "Hello"       // unused local variable -> WARNING
        println("Hi there")
    }

    fun compute(): Int {
        var total = 0
        for (i in 1..10) {
            total += i
        }
        return total
        println("never runs")       // unreachable code -> WARNING
    }

    // Type mismatch: assigning a String to an Int -> ERROR
    fun broken(): Int {
        val result: Int = "not a number"
        return result
    }

    // Calls a function that does not exist -> ERROR (unresolved reference)
    fun callMissing() {
        doesNotExist()
    }

    // Redundant nullable check on a non-null type -> WEAK WARNING
    fun redundantCheck(value: String) {
        if (value != null) {
            println(value)
        }
    }

    // Comparison that is always false -> WARNING
    fun alwaysFalse(): Boolean {
        val x = 5
        return x == 5 && x == 6
    }
}
