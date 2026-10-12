package com.goldclient.translator;

import java.util.*;

/**
 * Parses Forge 1.12 dependency declarations and orders a supplied set of mods.
 * Version ranges are preserved for runtime-specific validation; this class never
 * pretends to validate a version without the original loader's version API.
 */
public final class ForgeDependencyGraph {
    private static final java.util.regex.Pattern CLAUSE = java.util.regex.Pattern.compile(
            "^(required-after|required-before|after|before):([A-Za-z0-9_.-]+)(?:@(.+))?$");

    private ForgeDependencyGraph() {}

    public record Dependency(String kind, String modId, String versionRange) {
        public boolean required() { return kind.startsWith("required-"); }
    }
    public record Mod(String id, String version, String declarations) {
        public Mod {
            if (id == null || !id.matches("[a-z][a-z0-9_]{0,63}"))
                throw new IllegalArgumentException("Invalid Forge mod ID: " + id);
            version = version == null ? "" : version;
            declarations = declarations == null ? "" : declarations;
        }
    }
    public record Plan(List<Mod> ordered, List<String> blockers) {
        public Plan { ordered = List.copyOf(ordered); blockers = List.copyOf(blockers); }
        public boolean ready() { return blockers.isEmpty(); }
    }

    public static List<Dependency> parse(String declarations) {
        if (declarations == null || declarations.isBlank()) return List.of();
        List<Dependency> result = new ArrayList<>();
        for (String raw : declarations.split(";")) {
            String clause = raw.trim();
            if (clause.isEmpty()) continue;
            var match = CLAUSE.matcher(clause);
            if (!match.matches() || (match.group(3) != null && match.group(3).isBlank()))
                throw new IllegalArgumentException("Unsupported Forge dependency declaration: " + clause);
            result.add(new Dependency(match.group(1), match.group(2),
                    match.group(3) == null ? "" : match.group(3)));
        }
        return List.copyOf(result);
    }

    /** Returns a stable topological order, retaining input order where unconstrained. */
    public static Plan plan(Collection<Mod> mods) {
        List<Mod> input = List.copyOf(mods);
        Map<String, Mod> byId = new LinkedHashMap<>();
        List<String> blockers = new ArrayList<>();
        for (Mod mod : input) {
            if (byId.putIfAbsent(mod.id(), mod) != null)
                blockers.add("Duplicate mod ID in load plan: " + mod.id());
        }
        if (!blockers.isEmpty()) return new Plan(List.of(), blockers);

        Map<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (Mod mod : input) {
            outgoing.put(mod.id(), new LinkedHashSet<>());
            indegree.put(mod.id(), 0);
        }
        for (Mod mod : input) {
            List<Dependency> dependencies;
            try {
                dependencies = parse(mod.declarations());
            } catch (IllegalArgumentException error) {
                blockers.add(mod.id() + ": " + error.getMessage());
                continue;
            }
            for (Dependency dependency : dependencies) {
                String target = dependency.modId();
                // Forge itself is supplied by the loader, not by a mod JAR in this graph.
                if (target.equals("forge")) continue;
                boolean present = byId.containsKey(target);
                if (dependency.required() && !present) {
                    blockers.add("Required dependency missing: " + mod.id() + " -> " + target);
                    continue;
                }
                if (!present) continue; // optional ordering constraints apply only when installed
                String from = dependency.kind().endsWith("after") ? target : mod.id();
                String to = dependency.kind().endsWith("after") ? mod.id() : target;
                if (outgoing.get(from).add(to)) indegree.put(to, indegree.get(to) + 1);
            }
        }
        if (!blockers.isEmpty()) return new Plan(List.of(), blockers);

        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Mod mod : input) if (indegree.get(mod.id()) == 0) ready.addLast(mod.id());
        List<Mod> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            ordered.add(byId.get(id));
            for (String next : outgoing.get(id)) {
                int degree = indegree.merge(next, -1, Integer::sum);
                if (degree == 0) ready.addLast(next);
            }
        }
        if (ordered.size() != input.size()) {
            Set<String> cyclic = new LinkedHashSet<>(byId.keySet());
            ordered.forEach(mod -> cyclic.remove(mod.id()));
            blockers.add("Dependency ordering cycle involving: " + String.join(", ", cyclic));
            return new Plan(List.of(), blockers);
        }
        return new Plan(ordered, List.of());
    }
}
