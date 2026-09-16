package me.jackcw.duels.stats;

import java.util.UUID;

public record HeadToHead(UUID playerA, UUID playerB, int winsA, int winsB)
{
}
