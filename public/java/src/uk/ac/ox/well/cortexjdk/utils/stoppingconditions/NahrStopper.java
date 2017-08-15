package uk.ac.ox.well.cortexjdk.utils.stoppingconditions;

import org.jgrapht.DirectedGraph;
import org.jgrapht.graph.DirectedWeightedPseudograph;
import uk.ac.ox.well.cortexjdk.utils.io.cortex.graph.CortexGraph;
import uk.ac.ox.well.cortexjdk.utils.io.cortex.graph.CortexRecord;
import uk.ac.ox.well.cortexjdk.utils.traversal.CortexEdge;
import uk.ac.ox.well.cortexjdk.utils.traversal.CortexVertex;

import java.util.Set;

/**
 * Created by kiran on 08/05/2017.
 */
public class NahrStopper extends AbstractTraversalStopper<CortexVertex, CortexEdge> {
    private boolean foundNovels = false;
    private int distanceFromLastNovel = 0;

    @Override
    public boolean hasTraversalSucceeded(CortexVertex cv, boolean goForward, int traversalColor, Set<Integer> joiningColors, int currentTraversalDepth, int currentGraphSize, int numAdjacentEdges, boolean childrenAlreadyTraversed, DirectedWeightedPseudograph<CortexVertex, CortexEdge> previousGraph, CortexGraph rois) {
        if (foundNovels) {
            distanceFromLastNovel++;
        }

        if (rois.findRecord(cv.getCr().getCortexKmer()) != null) {
            foundNovels = true;
            distanceFromLastNovel++;
        }

        return foundNovels && (distanceFromLastNovel >= 1000 || currentTraversalDepth >= 5 || numAdjacentEdges == 0 || childrenAlreadyTraversed);
    }

    @Override
    public boolean hasTraversalFailed(CortexVertex cv, boolean goForward, int traversalColor, Set<Integer> joiningColors, int currentTraversalDepth, int currentGraphSize, int numAdjacentEdges, boolean childrenAlreadyTraversed, DirectedWeightedPseudograph<CortexVertex, CortexEdge> previousGraph, CortexGraph rois) {
        return !foundNovels && (currentGraphSize >= 1000 || currentTraversalDepth >= 2 || numAdjacentEdges == 0);
    }
}