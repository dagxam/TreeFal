package me.dagxam.treefall;

import org.bukkit.block.Block;

import java.util.Set;

public record TreeBlocks(Set<Block> logs, Set<Block> leaves, boolean truncated) {

    public TreeBlocks(Set<Block> logs, Set<Block> leaves) {
        this(logs, leaves, false);
    }
}
