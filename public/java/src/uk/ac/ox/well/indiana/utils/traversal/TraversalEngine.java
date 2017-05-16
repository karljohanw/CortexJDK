package uk.ac.ox.well.indiana.utils.traversal;

import org.jetbrains.annotations.Nullable;
import org.jgrapht.DirectedGraph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.cycle.DirectedSimpleCycles;
import org.jgrapht.alg.cycle.SzwarcfiterLauerSimpleCycles;
import org.jgrapht.graph.DefaultDirectedGraph;
import uk.ac.ox.well.indiana.utils.exceptions.IndianaException;
import uk.ac.ox.well.indiana.utils.io.cortex.graph.CortexKmer;
import uk.ac.ox.well.indiana.utils.io.cortex.graph.CortexRecord;

import java.util.*;

import static uk.ac.ox.well.indiana.utils.traversal.TraversalEngineConfiguration.GraphCombinationOperator.OR;
import static uk.ac.ox.well.indiana.utils.traversal.TraversalEngineConfiguration.TraversalDirection.BOTH;
import static uk.ac.ox.well.indiana.utils.traversal.TraversalEngineConfiguration.TraversalDirection.FORWARD;
import static uk.ac.ox.well.indiana.utils.traversal.TraversalEngineConfiguration.TraversalDirection.REVERSE;

public class TraversalEngine {
    private TraversalEngineConfiguration ec;

    public TraversalEngine(TraversalEngineConfiguration ec) { this.ec = ec; }

    public TraversalEngineConfiguration getConfiguration() { return ec; }

    public DirectedGraph<CortexVertex, CortexEdge> dfs(String sk) {
        if (sk.length() != ec.getGraph().getKmerSize()) {
            throw new IndianaException("Graph traversal starting kmer is not equal to graph kmer size (" + sk.length() + " vs " + ec.getGraph().getKmerSize() + ")");
        }

        DirectedGraph<CortexVertex, CortexEdge> dfsr = (ec.getTraversalDirection() == BOTH || ec.getTraversalDirection() == REVERSE) ? dfs(sk, false, 0, new HashSet<>()) : null;
        DirectedGraph<CortexVertex, CortexEdge> dfsf = (ec.getTraversalDirection() == BOTH || ec.getTraversalDirection() == FORWARD) ? dfs(sk, true,  0, new HashSet<>()) : null;

        DirectedGraph<CortexVertex, CortexEdge> dfs = new DefaultDirectedGraph<>(CortexEdge.class);

        if (ec.getGraphCombinationOperator() == OR) {
            if (dfsr != null || dfsf != null) {
                if (dfsr != null) { Graphs.addGraph(dfs, dfsr); }
                if (dfsf != null) { Graphs.addGraph(dfs, dfsf); }

                return dfs;
            }
        } else {
            if (dfsr != null && dfsf != null) {
                Graphs.addGraph(dfs, dfsr);
                Graphs.addGraph(dfs, dfsf);

                return dfs;
            }
        }

        return null;
    }

    public String getContig(DirectedGraph<CortexVertex, CortexEdge> goriginal, String kmer, int color) {
        DirectedGraph<CortexVertex, CortexEdge> gcopy = new DefaultDirectedGraph<>(CortexEdge.class);
        Graphs.addGraph(gcopy, goriginal);

        CortexVertex cv = new CortexVertex(kmer, ec.getGraph().findRecord(new CortexKmer(kmer)), color);
        Set<String> history = new HashSet<>();

        DirectedSimpleCycles<CortexVertex, CortexEdge> cf = new SzwarcfiterLauerSimpleCycles<>(gcopy);
        List<List<CortexVertex>> cycles = cf.findSimpleCycles();

        Map<String, String> cycleExpansions = new HashMap<>();

        for (int i = 0; i < cycles.size(); i++) {
            int startIndex = 0;
            for (int j = 0; j < cycles.get(i).size(); j++) {
                CortexVertex v = cycles.get(i).get(j);

                if (gcopy.outDegreeOf(v) != 1) {
                    startIndex = j;
                    break;
                }
            }

            StringBuilder sb = new StringBuilder();
            List<CortexVertex> cyclesAligned = new ArrayList<>();
            Set<CortexEdge> edgesToRemove = new HashSet<>();
            for (int j = 0, offset = startIndex + j + 1; j < cycles.get(i).size(); j++, offset++) {
                if (offset == cycles.get(i).size()) { offset = 0; }

                CortexVertex v = cycles.get(i).get(offset);
                cyclesAligned.add(v);

                sb.append(v.getSk().substring(v.getSk().length() - 1, v.getSk().length()));

                if (gcopy.inDegreeOf(v) == 1 && gcopy.outDegreeOf(Graphs.predecessorListOf(gcopy, v).get(0)) > 1) {
                    edgesToRemove.addAll(gcopy.incomingEdgesOf(v));
                }

                if (gcopy.outDegreeOf(v) == 1 && gcopy.inDegreeOf(Graphs.successorListOf(gcopy, v).get(0)) > 1) {
                    edgesToRemove.addAll(gcopy.outgoingEdgesOf(v));
                }

                if (gcopy.outDegreeOf(v) > 0) {
                    for (CortexEdge e : gcopy.outgoingEdgesOf(v)) {
                        if (gcopy.getEdgeTarget(e).equals(v)) {
                            edgesToRemove.add(e);
                        }
                    }
                }
            }

            gcopy.removeAllEdges(edgesToRemove);

            cycleExpansions.put(cycles.get(i).get(startIndex).getSk(), sb.toString());
        }

        List<String> contigKmers = new ArrayList<>();
        contigKmers.add(cv.getSk());

        while (gcopy.inDegreeOf(cv) == 1 && !history.contains(cv.getSk())) {
            history.add(cv.getSk());

            cv = Graphs.predecessorListOf(gcopy, cv).iterator().next();
            contigKmers.add(0, cv.getSk());
        }

        cv = new CortexVertex(kmer, ec.getGraph().findRecord(new CortexKmer(kmer)), color);
        history.clear();

        while (gcopy.outDegreeOf(cv) == 1 && !history.contains(cv.getSk())) {
            history.add(cv.getSk());

            cv = Graphs.successorListOf(gcopy, cv).iterator().next();
            contigKmers.add(cv.getSk());
        }

        StringBuilder sb = new StringBuilder(contigKmers.get(0));
        for (int i = 1; i < contigKmers.size(); i++) {
            String sk = contigKmers.get(i);
            int prevLength = contigKmers.get(i-1).length();
            String b = sk.substring(prevLength - 1, sk.length());

            sb.append(b);

            if (cycleExpansions.containsKey(sk)) {
                sb.append(cycleExpansions.get(sk));
            }
        }

        return sb.toString();
    }

    @Nullable
    private DirectedGraph<CortexVertex, CortexEdge> dfs(String sk, boolean goForward, int currentTraversalDepth, Set<CortexVertex> visited) {
        DirectedGraph<CortexVertex, CortexEdge> g = new DefaultDirectedGraph<>(CortexEdge.class);

        CortexRecord cr = ec.getGraph().findRecord(new CortexKmer(sk));
        CortexVertex cv = new CortexVertex(sk, cr, ec.getTraversalColor());

        Set<CortexVertex> avs;

        do {
            Set<CortexVertex> pvs = getPrevVertices(cv.getSk());
            Set<CortexVertex> nvs = getNextVertices(cv.getSk());
            avs = goForward ? nvs : pvs;

            // Connect the new vertices to the graph
            if (ec.connectAllNeighbors()) {
                connectVertex(g, cv, pvs,  nvs);
            } else if (goForward) {
                connectVertex(g, cv, null, nvs);
            } else {
                connectVertex(g, cv, pvs,  null);
            }

            visited.add(cv);

            // Avoid traversing infinite loops by removing from traversal consideration
            // those vertices that have already been incorporated into the graph.
            Set<CortexVertex> seen = new HashSet<>();
            for (CortexVertex av : avs) {
                if (visited.contains(av)) {
                    seen.add(av);
                }
            }
            avs.removeAll(seen);

            // Decide if we should keep exploring the graph or not
            if (ec.getStoppingRule().keepGoing(cv, goForward, ec.getTraversalColor(), ec.getJoiningColors(), currentTraversalDepth, g.vertexSet().size(), avs.size(), ec.getPreviousTraversal()) /*&& !history.contains(cv)*/) {
                if (avs.size() == 1) {
                    cv = avs.iterator().next();
                } else if (avs.size() != 1) {
                    boolean childrenWereSuccessful = false;

                    for (CortexVertex av : avs) {
                        DirectedGraph<CortexVertex, CortexEdge> branch = dfs(av.getSk(), goForward, currentTraversalDepth + 1, visited);

                        if (branch != null) {
                            Graphs.addGraph(g, branch);
                            childrenWereSuccessful = true;
                        } else {
                            // could mark a rejected traversal here rather than just throwing it away
                        }
                    }

                    if (childrenWereSuccessful || ec.getStoppingRule().hasTraversalSucceeded(cv, goForward, ec.getTraversalColor(), ec.getJoiningColors(), currentTraversalDepth, g.vertexSet().size(), avs.size(), ec.getPreviousTraversal())) {
                        return g;
                    } else {
                        // could mark a rejected traversal here rather than just throwing it away
                    }
                }
            } else if (ec.getStoppingRule().traversalSucceeded()) {
                return g;
            } else {
                return null;
            }
        } while (avs.size() == 1);

        return null;
    }

    private void connectVertex(DirectedGraph<CortexVertex, CortexEdge> g, CortexVertex cv, Set<CortexVertex> pvs, Set<CortexVertex> nvs) {
        g.addVertex(cv);

        if (pvs != null) {
            for (CortexVertex pv : pvs) {
                g.addVertex(pv);
                g.addEdge(pv, cv, new CortexEdge());
            }
        }

        if (nvs != null) {
            for (CortexVertex nv : nvs) {
                g.addVertex(nv);
                g.addEdge(cv, nv, new CortexEdge());
            }
        }
    }

    private Set<CortexVertex> getPrevVertices(String sk) {
        Set<CortexVertex> prevVertices = new HashSet<>();

        Map<Integer, Set<String>> prevKmers = getAllPrevKmers(sk);

        if (prevKmers.get(ec.getTraversalColor()).size() > 0) {
            for (String prevKmer : prevKmers.get(ec.getTraversalColor())) {
                prevVertices.add(new CortexVertex(prevKmer, ec.getGraph().findRecord(new CortexKmer(prevKmer)), ec.getTraversalColor()));
            }
        } else {
            Map<String, Set<Integer>> inKmerMap = new HashMap<>();

            for (int c : ec.getRecruitmentColors()) {
                Set<String> inKmers = prevKmers.get(c);

                for (String inKmer : inKmers) {
                    if (!inKmerMap.containsKey(inKmer)) {
                        inKmerMap.put(inKmer, new HashSet<>());
                    }

                    inKmerMap.get(inKmer).add(c);
                }
            }

            for (String prevKmer : inKmerMap.keySet()) {
                prevVertices.add(new CortexVertex(prevKmer, ec.getGraph().findRecord(new CortexKmer(prevKmer)), inKmerMap.get(prevKmer)));
            }
        }

        return prevVertices;
    }

    private Set<CortexVertex> getNextVertices(String sk) {
        Set<CortexVertex> nextVertices = new HashSet<>();

        Map<Integer, Set<String>> nextKmers = getAllNextKmers(sk);
        if (nextKmers.get(ec.getTraversalColor()).size() > 0) {
            for (String nextKmer : nextKmers.get(ec.getTraversalColor())) {
                nextVertices.add(new CortexVertex(nextKmer, ec.getGraph().findRecord(new CortexKmer(nextKmer)), ec.getTraversalColor()));
            }
        } else {
            Map<String, Set<Integer>> outKmerMap = new HashMap<>();

            for (int c : ec.getRecruitmentColors()) {
                Set<String> outKmers = nextKmers.get(c);

                for (String outKmer : outKmers) {
                    if (!outKmerMap.containsKey(outKmer)) {
                        outKmerMap.put(outKmer, new HashSet<>());
                    }

                    outKmerMap.get(outKmer).add(c);
                }
            }

            for (String nextKmer : outKmerMap.keySet()) {
                nextVertices.add(new CortexVertex(nextKmer, ec.getGraph().findRecord(new CortexKmer(nextKmer)), outKmerMap.get(nextKmer)));
            }
        }

        return nextVertices;
    }

    private Map<Integer, Set<String>> getAllPrevKmers(String sk) {
        CortexKmer ck = new CortexKmer(sk);
        CortexRecord cr = ec.getGraph().findRecord(ck);

        Map<Integer, Set<String>> prevKmers = new HashMap<>();

        if (cr == null) {
            cr = ec.getGraph().findRecord(ck);
        }

        if (cr != null) {
            String suffix = sk.substring(0, sk.length() - 1);

            Map<Integer, Set<String>> inEdges = getInEdges(cr, ck.isFlipped());

            for (int c = 0; c < cr.getNumColors(); c++) {
                Set<String> inKmers = new HashSet<>();

                for (String inEdge : inEdges.get(c)) {
                    inKmers.add(inEdge + suffix);
                }

                prevKmers.put(c, inKmers);
            }
        }

        return prevKmers;
    }

    private Map<Integer, Set<String>> getAllNextKmers(String sk) {
        CortexKmer ck = new CortexKmer(sk);
        CortexRecord cr = ec.getGraph().findRecord(ck);

        Map<Integer, Set<String>> nextKmers = new HashMap<>();

        if (cr != null) {
            String prefix = sk.substring(1, sk.length());

            Map<Integer, Set<String>> outEdges = getOutEdges(cr, ck.isFlipped());

            for (int c = 0; c < cr.getNumColors(); c++) {
                Set<String> outKmers = new HashSet<>();

                for (String outEdge : outEdges.get(c)) {
                    outKmers.add(prefix + outEdge);
                }

                nextKmers.put(c, outKmers);
            }
        }

        return nextKmers;
    }

    private Map<Integer, Set<String>> getInEdges(CortexRecord cr, boolean kmerIsFlipped) {
        Map<Integer, Set<String>> inEdges = new HashMap<>();

        if (!kmerIsFlipped) {
            for (int c = 0; c < cr.getNumColors(); c++) {
                inEdges.put(c, new HashSet<>(cr.getInEdgesAsStrings(c, false)));
            }
        } else {
            for (int c = 0; c < cr.getNumColors(); c++) {
                inEdges.put(c, new HashSet<>(cr.getOutEdgesAsStrings(c, true)));
            }
        }

        return inEdges;
    }

    private Map<Integer, Set<String>> getOutEdges(CortexRecord cr, boolean kmerIsFlipped) {
        Map<Integer, Set<String>> outEdges = new HashMap<>();

        if (!kmerIsFlipped) {
            for (int c = 0; c < cr.getNumColors(); c++) {
                outEdges.put(c, new HashSet<>(cr.getOutEdgesAsStrings(c, false)));
            }
        } else {
            for (int c = 0; c < cr.getNumColors(); c++) {
                outEdges.put(c, new HashSet<>(cr.getInEdgesAsStrings(c, true)));
            }
        }

        return outEdges;
    }
}
