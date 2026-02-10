package me.dagxam.treefall;

import org.bukkit.block.Block;
import java.util.Set;

public record TreeBlocks(Set<Block> logs, Set<Block> leaves) {}
