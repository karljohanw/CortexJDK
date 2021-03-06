package uk.ac.ox.well.cortexjdk.utils.traversal;

import org.apache.commons.math3.util.Pair;
import org.jetbrains.annotations.Nullable;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DirectedWeightedPseudograph;
import uk.ac.ox.well.cortexjdk.Main;
import uk.ac.ox.well.cortexjdk.utils.exceptions.CortexJDKException;
import uk.ac.ox.well.cortexjdk.utils.io.graph.ConnectivityAnnotations;
import uk.ac.ox.well.cortexjdk.utils.io.graph.cortex.CortexRecord;
import uk.ac.ox.well.cortexjdk.utils.kmer.CanonicalKmer;
import uk.ac.ox.well.cortexjdk.utils.kmer.CortexByteKmer;
import uk.ac.ox.well.cortexjdk.utils.stoppingrules.TraversalStoppingRule;

import java.util.*;

import static uk.ac.ox.well.cortexjdk.utils.traversal.TraversalEngineConfiguration.GraphCombinationOperator.OR;
import static uk.ac.ox.well.cortexjdk.utils.traversal.TraversalEngineConfiguration.TraversalDirection.*;

public class TraversalEngine {
    final private TraversalEngineConfiguration ec;

    private CortexByteKmer curKmer = null;
    private CortexByteKmer prevKmer;
    private CortexByteKmer nextKmer;
    private Set<CortexByteKmer> seen;

    private Set<String> kmerSources;
    private Set<ConnectivityAnnotations> specificLinksFiles;
    private LinkStore linkStore;
    private boolean goForward;

    public TraversalEngine(TraversalEngineConfiguration ec) { this.ec = ec; }

    public final TraversalEngineConfiguration getConfiguration() { return ec; }

    public DirectedWeightedPseudograph<CortexVertex, CortexEdge> dfs(Collection<String> sources) {
        return dfs(sources, null);
    }

    public DirectedWeightedPseudograph<CortexVertex, CortexEdge> dfs(Collection<String> sources, Collection<String> sinks) {
        String[] asinks = sinks == null ? new String[0] : sinks.toArray(new String[sinks.size()]);

        DirectedWeightedPseudograph<CortexVertex, CortexEdge> dfs = null;
        for (String source : sources) {
            DirectedWeightedPseudograph<CortexVertex, CortexEdge> g = dfs(source, asinks);

            if (g != null) {
                if (dfs == null) {
                    dfs = g;
                } else {
                    Graphs.addGraph(dfs, g);
                }
            }
        }

        return dfs;
    }

    public DirectedWeightedPseudograph<CortexVertex, CortexEdge> dfs(CanonicalKmer source) {
        return dfs(source.getKmerAsString());
    }

    public DirectedWeightedPseudograph<CortexVertex, CortexEdge> dfs(String source, String... sinks) {
        CortexVertex cv = new CortexVertexFactory()
                .bases(source)
                .record(ec.getGraph().findRecord(source))
                .copyIndex(0)
                .index(0)
                .make();

        DirectedWeightedPseudograph<CortexVertex, CortexEdge> dfsr = (ec.getTraversalDirection() == BOTH || ec.getTraversalDirection() == REVERSE) ? dfs(cv, false, 0, 0, new HashSet<>(), sinks) : null;
        DirectedWeightedPseudograph<CortexVertex, CortexEdge> dfsf = (ec.getTraversalDirection() == BOTH || ec.getTraversalDirection() == FORWARD) ? dfs(cv, true,  0, 0, new HashSet<>(), sinks) : null;

        if (dfsr != null) {
            dfsr.vertexSet().forEach(v -> { if (!v.equals(cv)) { v.setIndex(-1); } });
        }

        if (dfsf != null) {
            dfsf.vertexSet().forEach(v -> { if (!v.equals(cv)) { v.setIndex(1); } });
        }

        DirectedWeightedPseudograph<CortexVertex, CortexEdge> dfs = null;

        if (ec.getGraphCombinationOperator() == OR) {
            if (dfsr != null || dfsf != null) {
                dfs = new DirectedWeightedPseudograph<>(CortexEdge.class);

                if (dfsr != null) { Graphs.addGraph(dfs, dfsr); }
                if (dfsf != null) { Graphs.addGraph(dfs, dfsf); }
            }
        } else {
            if (dfsr != null && dfsf != null) {
                dfs = new DirectedWeightedPseudograph<>(CortexEdge.class);

                Graphs.addGraph(dfs, dfsr);
                Graphs.addGraph(dfs, dfsf);
            }
        }

        if (dfs != null) {
            return addSecondaryColors(dfs);
        }

        return null;
    }

    public List<CortexVertex> walk(CanonicalKmer seed) { return TraversalUtils.toWalk(dfs(seed.getKmerAsString()), seed.getKmerAsString(), ec.getTraversalColors().iterator().next()); }

    public List<CortexVertex> walk(String seed) { return TraversalUtils.toWalk(dfs(seed), seed, ec.getTraversalColors().iterator().next()); }

    public List<CortexVertex> assemble(String seed) {
        List<CortexVertex> contig = new ArrayList<>();

        CortexVertex sv = new CortexVertexFactory()
                .bases(seed)
                .record(ec.getGraph().findRecord(seed))
                .make();

        contig.add(sv);

        contig.addAll(assemble(seed, true));
        contig.addAll(0, assemble(seed, false));

        return contig;
    }

    public List<CortexVertex> assemble(String seed, boolean goForward) {
        List<CortexVertex> contig = new ArrayList<>();

        seek(seed);
        if (goForward) {
            while (hasNext() && contig.size() < ec.getMaxBranchLength()) {
                CortexVertex cv = next();
                contig.add(cv);
            }
        } else {
            while (hasPrevious() && contig.size() < ec.getMaxBranchLength()) {
                CortexVertex cv = previous();
                contig.add(0, cv);
            }
        }

        return contig;
    }

    public Set<CortexVertex> getPrevVertices(CortexByteKmer sk) {
        Set<CortexVertex> prevVertices = new HashSet<>();

        Map<Integer, Set<CortexByteKmer>> prevKmers = getAllPrevKmers(sk);

        Set<CortexByteKmer> combinedPrevKmers = new HashSet<>();
        for (int c : ec.getTraversalColors()) {
            if (prevKmers != null && prevKmers.containsKey(c)) {
                combinedPrevKmers.addAll(prevKmers.get(c));
            }
        }

        if (combinedPrevKmers.size() > 0) {
            for (CortexByteKmer prevKmer : combinedPrevKmers) {
                prevVertices.add(new CortexVertexFactory()
                        .bases(prevKmer)
                        .record(ec.getGraph().findRecord(prevKmer))
                        .make()
                );
            }
        } else {
            Map<CortexByteKmer, Set<Integer>> inKmerMap = new HashMap<>();

            for (int c : ec.getRecruitmentColors()) {
                Set<CortexByteKmer> inKmers = prevKmers.get(c);

                for (CortexByteKmer inKmer : inKmers) {
                    if (!inKmerMap.containsKey(inKmer)) {
                        inKmerMap.put(inKmer, new HashSet<>());
                    }

                    inKmerMap.get(inKmer).add(c);
                }
            }

            for (CortexByteKmer prevKmer : inKmerMap.keySet()) {
                prevVertices.add(new CortexVertexFactory()
                        .bases(prevKmer)
                        .record(ec.getGraph().findRecord(prevKmer))
                        .make()
                );
            }
        }

        return prevVertices;
    }

    public Set<CortexVertex> getNextVertices(CortexByteKmer sk) {
        Set<CortexVertex> nextVertices = new HashSet<>();

        Map<Integer, Set<CortexByteKmer>> nextKmers = getAllNextKmers(sk);

        Set<CortexByteKmer> combinedNextKmers = new HashSet<>();
        for (int c : ec.getTraversalColors()) {
            if (nextKmers != null && nextKmers.containsKey(c)) {
                combinedNextKmers.addAll(nextKmers.get(c));
            }
        }

        if (combinedNextKmers.size() > 0) {
            for (CortexByteKmer nextKmer : combinedNextKmers) {
                nextVertices.add(new CortexVertexFactory()
                        .bases(nextKmer)
                        .record(ec.getGraph().findRecord(nextKmer))
                        .make()
                );
            }
        } else {
            Map<CortexByteKmer, Set<Integer>> outKmerMap = new HashMap<>();

            for (int c : ec.getRecruitmentColors()) {
                Set<CortexByteKmer> outKmers = nextKmers.get(c);

                for (CortexByteKmer outKmer : outKmers) {
                    if (!outKmerMap.containsKey(outKmer)) {
                        outKmerMap.put(outKmer, new HashSet<>());
                    }

                    outKmerMap.get(outKmer).add(c);
                }
            }

            for (CortexByteKmer nextKmer : outKmerMap.keySet()) {
                nextVertices.add(new CortexVertexFactory()
                        .bases(nextKmer)
                        .record(ec.getGraph().findRecord(nextKmer))
                        .make()
                );
            }
        }

        return nextVertices;
    }

    public CortexVertex next() {
        if (nextKmer == null) { throw new NoSuchElementException("No single advance kmer from cursor '" + curKmer + "'"); }
        if (specificLinksFiles == null || !goForward) {
            goForward = true;
            seek(new String(curKmer.getKmer()));

            initializeLinkStore(goForward);
        }

        updateLinkStore(goForward);

        CortexRecord cr = ec.getGraph().findRecord(nextKmer);
        CortexVertex cv = new CortexVertexFactory().bases(nextKmer).record(cr).sources(kmerSources).make();

        prevKmer = curKmer;
        curKmer = nextKmer;

        Set<CortexVertex> nextKmers = getNextVertices(curKmer);
        nextKmer = null;
        kmerSources = null;

        if (nextKmers.size() == 1 && (!seen.contains(nextKmers.iterator().next().getKmerAsByteKmer()) || linkStore.isActive())) {
            nextKmer = nextKmers.iterator().next().getKmerAsByteKmer();

            seen.add(nextKmer);
        } else if (nextKmers.size() > 1) {
            Pair<CortexByteKmer, Set<String>> akp = getAdjacentKmer(curKmer, nextKmers, goForward);
            nextKmer = akp != null ? akp.getFirst() : null;
            kmerSources = akp != null ? akp.getSecond() : null;

            linkStore.incrementAges();
        }

        if (linkStore.numNewPaths() > 0) {
            linkStore.incrementAges();
        }

        return cv;
    }

    public CortexVertex previous() {
        if (prevKmer == null) { throw new NoSuchElementException("No single prev kmer from cursor '" + curKmer + "'"); }
        if (specificLinksFiles == null || goForward) {
            goForward = false;
            seek(new String(curKmer.getKmer()));

            initializeLinkStore(goForward);
        }

        updateLinkStore(goForward);

        CortexRecord cr = ec.getGraph().findRecord(prevKmer);
        CortexVertex cv = new CortexVertexFactory().bases(prevKmer).record(cr).sources(kmerSources).make();

        nextKmer = curKmer;
        curKmer = prevKmer;

        Set<CortexVertex> prevKmers = getPrevVertices(curKmer);
        prevKmer = null;
        kmerSources = null;

        if (prevKmers.size() == 1 && (!seen.contains(prevKmers.iterator().next().getKmerAsByteKmer()) || linkStore.isActive())) {
            prevKmer = prevKmers.iterator().next().getKmerAsByteKmer();

            seen.add(prevKmer);
        } else if (prevKmers.size() > 1) {
            Pair<CortexByteKmer, Set<String>> akp = getAdjacentKmer(curKmer, prevKmers, goForward);
            prevKmer = akp != null ? akp.getFirst() : null;
            kmerSources = akp != null ? akp.getSecond() : null;

            linkStore.incrementAges();
        }

        if (linkStore.numNewPaths() > 0) {
            linkStore.incrementAges();
        }

        return cv;
    }

    public void seek(String sk) {
        if (sk != null) {
            curKmer = new CortexByteKmer(sk.getBytes());

            Set<CortexVertex> prevKmers = getPrevVertices(curKmer);
            prevKmer = (prevKmers.size() == 1) ? prevKmers.iterator().next().getKmerAsByteKmer() : null;

            Set<CortexVertex> nextKmers = getNextVertices(curKmer);
            nextKmer = (nextKmers.size() == 1) ? nextKmers.iterator().next().getKmerAsByteKmer() : null;

            linkStore = new LinkStore();
            seen = new HashSet<>();
            specificLinksFiles = null;
        }
    }

    public boolean hasNext() { return nextKmer != null; }

    public boolean hasPrevious() { return prevKmer != null; }

    private Map<Integer, Set<CortexByteKmer>> getAllPrevKmers(CortexByteKmer sk) {
        CanonicalKmer ck = new CanonicalKmer(sk.getKmer());
        CortexRecord cr = ec.getGraph().findRecord(ck);

        return TraversalUtils.getAllPrevKmers(cr, ck.isFlipped());
    }

    private Map<Integer, Set<CortexByteKmer>> getAllNextKmers(CortexByteKmer sk) {
        CanonicalKmer ck = new CanonicalKmer(sk.getKmer());
        CortexRecord cr = ec.getGraph().findRecord(ck);

        return TraversalUtils.getAllNextKmers(cr, ck.isFlipped());
    }

    @Nullable
    private DirectedWeightedPseudograph<CortexVertex, CortexEdge> dfs(CortexVertex cv, boolean goForward, int currentGraphSize, int currentJunctionDepth, Set<CortexVertex> visitedOld, String... sinks) {
        DirectedWeightedPseudograph<CortexVertex, CortexEdge> g = new DirectedWeightedPseudograph<>(CortexEdge.class);

        // Account for vertices visited in progenitor branches (but not other progeny branches)
        Set<CortexVertex> visited = new HashSet<>(visitedOld);

        // If links are available, reset the state of the LinkStore
        if (!ec.getLinks().isEmpty()) {
            seek(cv.getKmerAsString());
        }

        // Instantiate a new stopping rule per branch
        TraversalStoppingRule<CortexVertex, CortexEdge> stoppingRule = instantiateStopper(ec.getStoppingRule());

        Set<CortexVertex> avs;
        Set<CortexVertex> rvs;

        do {
            Set<CortexVertex> pvs = getPrevVertices(cv.getKmerAsByteKmer());
            Set<CortexVertex> nvs = getNextVertices(cv.getKmerAsByteKmer());
            avs = goForward ? nvs : pvs;
            rvs = goForward ? pvs : nvs;

            if (!ec.getLinks().isEmpty()) {
                // If we have links, then we are permitted to traverse some vertices multiple times.  Include a copy
                // count for those vertices so that we can distinguish each copy in the resulting subgraph.

                CortexVertex qv = null;
                if (goForward && hasNext()) {
                    qv = next();
                } else if (!goForward && hasPrevious()) {
                    qv = previous();
                }

                if (qv != null) {
                    CortexVertex lv = null;
                    do {
                        int copyIndex;
                        if (goForward) { copyIndex = lv == null ? 0 : lv.getCopyIndex() + 1; }
                        else { copyIndex = lv == null ? 0 : lv.getCopyIndex() - 1; }

                        lv = new CortexVertexFactory()
                                .bases(qv.getKmerAsString())
                                .record(qv.getCortexRecord())
                                .copyIndex(copyIndex)
                                .make();
                    } while (visited.contains(lv));

                    avs = new HashSet<>();
                    avs.add(lv);
                }
            }

            // Connect all neighboring vertices to the graph (useful for visualization)
            if (ec.connectAllNeighbors()) {
                connectVertex(g, cv, pvs, nvs);
            }

            // Avoid traversing infinite loops by removing from traversal consideration
            // those vertices that have already been incorporated into the graph.
            Set<CortexVertex> seen = new HashSet<>();
            for (CortexVertex av : avs) {
                if (visited.contains(av)) {
                    seen.add(av);
                }
            }
            avs.removeAll(seen);

            boolean previouslyVisited = visited.contains(cv);
            visited.add(cv);

            // Decide if we should keep exploring the graph or not
            TraversalState<CortexVertex> ts = new TraversalState<>(cv, goForward, ec.getTraversalColors(), ec.getJoiningColors(), currentGraphSize + g.vertexSet().size(), currentJunctionDepth, g.vertexSet().size(), avs.size(), rvs.size(), false, g.vertexSet().size() > ec.getMaxBranchLength(), ec.getRois(), sinks);

            if (!previouslyVisited && stoppingRule.keepGoing(ts)) {
                if (avs.size() == 1) {
                    if (goForward) { connectVertex(g, cv, null, avs); }
                    else           { connectVertex(g, cv, avs, null); }

                    if (getConfiguration().getDebugFlag()) { Main.getLogger().debug("{} {} {} {} {} {}", cv, "branch", pvs.size(), nvs.size(), avs.size(), currentJunctionDepth); }

                    cv = avs.iterator().next();
                } else {
                    boolean childrenWereSuccessful = false;

                    for (CortexVertex av : avs) {
                        DirectedWeightedPseudograph<CortexVertex, CortexEdge> branch = dfs(av, goForward, currentGraphSize + g.vertexSet().size(), currentJunctionDepth + 1, visited, sinks);

                        if (branch != null) {
                            if (goForward) { connectVertex(branch, cv, null, Collections.singleton(av)); }
                            else           { connectVertex(branch, cv, Collections.singleton(av), null); }

                            if (getConfiguration().getDebugFlag()) { Main.getLogger().debug("{} {}", cv, "junction"); }

                            Graphs.addGraph(g, branch);
                            childrenWereSuccessful = true;
                        } else {
                            if (getConfiguration().getDebugFlag()) { Main.getLogger().debug("{} {}", cv, "fail"); }
                            // could mark a rejected traversal here rather than just throwing it away
                        }
                    }

                    TraversalState<CortexVertex> tsChild = new TraversalState<>(cv, goForward, ec.getTraversalColors(), ec.getJoiningColors(), currentGraphSize + g.vertexSet().size(), currentJunctionDepth, g.vertexSet().size(), avs.size(), rvs.size(), true, g.vertexSet().size() > ec.getMaxBranchLength(), ec.getRois(), sinks);

                    if (childrenWereSuccessful || stoppingRule.hasTraversalSucceeded(tsChild)) {
                        if (getConfiguration().getDebugFlag()) { Main.getLogger().debug("complete branch subtraversal"); }

                        return g;
                    } else {
                        if (getConfiguration().getDebugFlag()) { Main.getLogger().debug("abort branch subtraversal"); }

                        // could mark a rejected traversal here rather than just throwing it away
                    }
                }
            } else if (stoppingRule.traversalSucceeded()) {
                if (getConfiguration().getDebugFlag()) { Main.getLogger().debug("complete branch traversal"); }

                return g;
            } else {
                if (getConfiguration().getDebugFlag()) { Main.getLogger().debug("abort branch traversal"); }

                return null;
            }
        } while (avs.size() == 1);

        return null;
    }

    private TraversalStoppingRule<CortexVertex, CortexEdge> instantiateStopper(Class<? extends TraversalStoppingRule<CortexVertex, CortexEdge>> stopperClass) {
        try {
            return stopperClass.newInstance();
        } catch (InstantiationException e) {
            throw new CortexJDKException("Could not instantiate stoppingRule: ", e);
        } catch (IllegalAccessException e) {
            throw new CortexJDKException("Illegal access while trying to instantiate stoppingRule: ", e);
        }
    }

    private void connectVertex(DirectedWeightedPseudograph<CortexVertex, CortexEdge> g, CortexVertex cv, Set<CortexVertex> pvs, Set<CortexVertex> nvs) {
        g.addVertex(cv);

        if (pvs != null) {
            for (CortexVertex pv : pvs) {
                g.addVertex(pv);

                if (!g.containsEdge(pv, cv)) {
                    g.addEdge(pv, cv, new CortexEdge(pv, cv, ec.getTraversalColors().iterator().next(), 1.0));
                }
            }
        }

        if (nvs != null) {
            for (CortexVertex nv : nvs) {
                g.addVertex(nv);

                if (!g.containsEdge(cv, nv)) {
                    g.addEdge(cv, nv, new CortexEdge(cv, nv, ec.getTraversalColors().iterator().next(), 1.0));
                }
            }
        }
    }

    private Pair<CortexByteKmer, Set<String>> getAdjacentKmer(CortexByteKmer kmer, Set<CortexVertex> adjKmers, boolean goForward) {
        Pair<String, Set<String>> choicePair = linkStore.getNextJunctionChoice();
        String choice = choicePair.getFirst();
        Set<String> sources = choicePair.getSecond();

        if (choice != null) {
            Set<CortexByteKmer> adjKmersStr = new HashSet<>();
            for (CortexVertex cv : adjKmers) {
                adjKmersStr.add(cv.getKmerAsByteKmer());
            }

            byte[] bAdjKmer = new byte[kmer.length()];
            if (goForward) {
                System.arraycopy(kmer.getKmer(), 1, bAdjKmer, 0, kmer.length() - 1);
                bAdjKmer[bAdjKmer.length - 1] = choice.getBytes()[0];
            } else {
                bAdjKmer[0] = choice.getBytes()[0];
                System.arraycopy(kmer.getKmer(), 0, bAdjKmer, 1, kmer.length() - 1);
            }

            CortexByteKmer adjKmer = new CortexByteKmer(bAdjKmer);

            if (adjKmersStr.contains(adjKmer)) {
                return new Pair<>(adjKmer, sources);
            }
        }

        return null;
    }

    private void initializeLinkStore(boolean goForward) {
        specificLinksFiles = new HashSet<>();

        Set<String> traversalSamples = new HashSet<>();
        for (int c : ec.getTraversalColors()) {
            traversalSamples.add(ec.getGraph().getSampleName(c));
        }

        if (!ec.getLinks().isEmpty()) {
            for (ConnectivityAnnotations lm : ec.getLinks()) {
                if (traversalSamples.contains(lm.getHeader().getSampleNameForColor(0))) {
                    specificLinksFiles.add(lm);

                    CanonicalKmer ck = new CanonicalKmer(curKmer.getKmer());
                    if (lm.containsKey(ck)) {
                        linkStore.add(curKmer, lm.get(ck), goForward, lm.getSource());
                    }
                }
            }
        }
    }

    private void updateLinkStore(boolean goForward) {
        Set<String> traversalSamples = new HashSet<>();
        for (int c : ec.getTraversalColors()) {
            traversalSamples.add(ec.getGraph().getSampleName(c));
        }

        specificLinksFiles = new HashSet<>();
        if (!ec.getLinks().isEmpty()) {
            for (ConnectivityAnnotations lm : ec.getLinks()) {
                //if (lm.getHeader().getSampleNameForColor(0).equals(ec.getGraph().getSampleName(ec.getTraversalColors()))) {
                if (traversalSamples.contains(lm.getHeader().getSampleNameForColor(0))) {
                    specificLinksFiles.add(lm);

                    if (goForward) {
                        CanonicalKmer nk = nextKmer == null ? null : new CanonicalKmer(nextKmer.getKmer());
                        if (nextKmer != null && lm.containsKey(nk)) {
                            linkStore.add(nextKmer, lm.get(nk), true, lm.getSource());
                        }
                    } else {
                        CanonicalKmer pk = prevKmer == null ? null : new CanonicalKmer(prevKmer.getKmer());
                        if (prevKmer != null && lm.containsKey(pk)) {
                            linkStore.add(prevKmer, lm.get(pk), false, lm.getSource());
                        }
                    }
                }
            }
        }
    }

    private DirectedWeightedPseudograph<CortexVertex, CortexEdge> addSecondaryColors(DirectedWeightedPseudograph<CortexVertex, CortexEdge> g) {
        DirectedWeightedPseudograph<CortexVertex, CortexEdge> m = new DirectedWeightedPseudograph<>(CortexEdge.class);
        Graphs.addGraph(m, g);

        if (!ec.getSecondaryColors().isEmpty()) {
            Map<CortexByteKmer, Map<Integer, Set<CortexByteKmer>>> pkscache = new HashMap<>();
            Map<CortexByteKmer, Map<Integer, Set<CortexByteKmer>>> nkscache = new HashMap<>();

            for (int c : ec.getSecondaryColors()) {
                if (!ec.getTraversalColors().contains(c)) {
                    DirectedWeightedPseudograph<CortexVertex, CortexEdge> g2 = new DirectedWeightedPseudograph<>(CortexEdge.class);

                    for (CortexVertex v : g.vertexSet()) {
                        Map<Integer, Set<CortexByteKmer>> pks = pkscache.containsKey(v.getKmerAsByteKmer()) ? pkscache.get(v.getKmerAsByteKmer()) : getAllPrevKmers(v.getKmerAsByteKmer());
                        Map<Integer, Set<CortexByteKmer>> nks = nkscache.containsKey(v.getKmerAsByteKmer()) ? nkscache.get(v.getKmerAsByteKmer()) : getAllNextKmers(v.getKmerAsByteKmer());

                        pkscache.put(v.getKmerAsByteKmer(), pks);
                        nkscache.put(v.getKmerAsByteKmer(), nks);

                        g2.addVertex(v);

                        for (CortexByteKmer pk : pks.get(c)) {
                            CortexVertex pv = new CortexVertexFactory().bases(pk).record(ec.getGraph().findRecord(pk)).make();

                            g2.addVertex(pv);
                            if (!g2.containsEdge(pv, v)) {
                                g2.addEdge(pv, v, new CortexEdge(pv, v, c, 1.0));
                            }
                        }

                        for (CortexByteKmer nk : nks.get(c)) {
                            CortexVertex nv = new CortexVertexFactory().bases(nk).record(ec.getGraph().findRecord(nk)).make();

                            g2.addVertex(nv);
                            if (!g2.containsEdge(v, nv)) {
                                g2.addEdge(v, nv, new CortexEdge(v, nv, c, 1.0));
                            }
                        }
                    }

                    Graphs.addGraph(m, g2);
                }
            }
        }

        return m;
    }
}
