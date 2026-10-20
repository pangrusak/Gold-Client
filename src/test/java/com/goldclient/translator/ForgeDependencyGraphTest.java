package com.goldclient.translator;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ForgeDependencyGraphTest {
    @Test void parsesGenericForgeDependencyClausesAndVersionRanges() {
        var dependencies = ForgeDependencyGraph.parse(
                "required-after:forge@[14.21.0,); after:jei@[4.6.0,); before:exampleaddon; required-before:coremod");
        assertEquals(4, dependencies.size());
        assertEquals("forge", dependencies.get(0).modId());
        assertEquals("[14.21.0,)", dependencies.get(0).versionRange());
        assertEquals("after", dependencies.get(1).kind());
        assertEquals("jei", dependencies.get(1).modId());
    }

    @Test void ordersDependenciesAndHonorsBeforeAndAfterWhenInstalled() {
        var plan = ForgeDependencyGraph.plan(List.of(
                new ForgeDependencyGraph.Mod("addon", "1", "after:core; before:ui"),
                new ForgeDependencyGraph.Mod("ui", "1", ""),
                new ForgeDependencyGraph.Mod("core", "1", "")));
        assertTrue(plan.ready(), plan.blockers().toString());
        assertEquals(List.of("core", "addon", "ui"),
                plan.ordered().stream().map(ForgeDependencyGraph.Mod::id).toList());
    }

    @Test void optionalMissingModsDoNotBlockButRequiredOnesDo() {
        var optional = ForgeDependencyGraph.plan(List.of(
                new ForgeDependencyGraph.Mod("addon", "1", "after:optionalmod; before:othermod")));
        assertTrue(optional.ready(), optional.blockers().toString());
        var required = ForgeDependencyGraph.plan(List.of(
                new ForgeDependencyGraph.Mod("addon", "1", "required-after:core")));
        assertFalse(required.ready());
        assertTrue(required.blockers().get(0).contains("addon -> core"));
    }

    @Test void reportsCyclesAndDuplicateIdsInsteadOfGuessingAnOrder() {
        var cycle = ForgeDependencyGraph.plan(List.of(
                new ForgeDependencyGraph.Mod("a", "1", "after:b"),
                new ForgeDependencyGraph.Mod("b", "1", "after:a")));
        assertFalse(cycle.ready());
        assertTrue(cycle.blockers().get(0).contains("cycle"));
        var duplicate = ForgeDependencyGraph.plan(List.of(
                new ForgeDependencyGraph.Mod("a", "1", ""),
                new ForgeDependencyGraph.Mod("a", "2", "")));
        assertFalse(duplicate.ready());
        assertTrue(duplicate.blockers().get(0).contains("Duplicate"));
    }

    @Test void parsesPresenceOnlyForgeClausesWithoutOrdering() {
        var dependencies = ForgeDependencyGraph.parse(
                "required:forge@[14.23.5.2855,);optional:jei@[4.6.0,);required:core");
        assertEquals(3, dependencies.size());
        assertEquals("required", dependencies.get(0).kind());
        assertTrue(dependencies.get(0).required());
        assertFalse(dependencies.get(0).orders());
        assertEquals("[14.23.5.2855,)", dependencies.get(0).versionRange());
        assertFalse(dependencies.get(1).required());

        // A presence-only clause must not invent a load-order edge.
        var plan = ForgeDependencyGraph.plan(List.of(
                new ForgeDependencyGraph.Mod("addon", "1", "required:core"),
                new ForgeDependencyGraph.Mod("core", "1", "")));
        assertTrue(plan.ready(), plan.blockers().toString());
        assertEquals(List.of("addon", "core"),
                plan.ordered().stream().map(ForgeDependencyGraph.Mod::id).toList());

        // Forge/Minecraft are host-provided, so the 1.12 bootstrap clause is valid.
        var bootstrap = ForgeDependencyGraph.plan(List.of(
                new ForgeDependencyGraph.Mod("addon", "1", "required:forge@[14.23.5.2855,)")));
        assertTrue(bootstrap.ready(), bootstrap.blockers().toString());
    }

    @Test void rejectsUnknownDependencySyntax() {
        assertThrows(IllegalArgumentException.class,
                () -> ForgeDependencyGraph.parse("mystery:othermod"));
    }
}
