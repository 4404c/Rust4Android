package dev.rustdroid.ide.ui.editor

import dev.rustdroid.ide.model.FileNode

/**
 * R4A: one visible row of the Files drawer, with what its `tree`-style guide
 * lines need. The project root is an implicit parent, so top-level entries
 * get connectors too (like `tree` under "."):
 *
 *   ├── src/
 *   │   └── main.rs
 *   └── Cargo.toml
 *
 * [continues] has one flag per ancestor column (0 until depth): draw a full
 * vertical line there when that ancestor still has siblings below. [isLast]
 * picks └ (last child) or ├ for the row's own connector column.
 */
data class TreeRow(
    val node: FileNode,
    val isLast: Boolean,
    val continues: List<Boolean>,
    val expanded: Boolean,
)

object FileTreeGuides {

    /**
     * [nodes] is the repository's pre-order list (each directory followed by
     * its contents). Children of a directory in [collapsed] (relative paths)
     * are hidden. Pure — unit-tested on the JVM.
     */
    fun rows(nodes: List<FileNode>, collapsed: Set<String>): List<TreeRow> {
        val visible = nodes.filter { n ->
            collapsed.none { c -> n.relativePath.startsWith("$c/") || n.relativePath.startsWith("$c\\") }
        }

        // isLast: walking backwards, a node is last when no sibling at the
        // same depth was seen since the last shallower node.
        val isLast = BooleanArray(visible.size)
        val hasNext = HashMap<Int, Boolean>()
        for (i in visible.indices.reversed()) {
            val d = visible[i].depth
            isLast[i] = hasNext[d] != true
            hasNext[d] = true
            hasNext.keys.filter { it > d }.forEach { hasNext.remove(it) }
        }

        // continues: forward stack of each open ancestor's isLast.
        val ancestorLast = ArrayList<Boolean>()
        return visible.mapIndexed { i, node ->
            while (ancestorLast.size > node.depth) ancestorLast.removeAt(ancestorLast.size - 1)
            val row = TreeRow(
                node = node,
                isLast = isLast[i],
                continues = ancestorLast.map { !it },
                expanded = node.isDirectory && node.relativePath !in collapsed,
            )
            if (node.isDirectory) ancestorLast.add(isLast[i])
            row
        }
    }
}
