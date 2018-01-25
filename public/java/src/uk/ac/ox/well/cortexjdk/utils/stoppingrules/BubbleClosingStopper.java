package uk.ac.ox.well.cortexjdk.utils.stoppingrules;

import org.jgrapht.graph.DirectedWeightedPseudograph;
import uk.ac.ox.well.cortexjdk.utils.io.graph.DeBruijnGraph;
import uk.ac.ox.well.cortexjdk.utils.traversal.CortexEdge;
import uk.ac.ox.well.cortexjdk.utils.traversal.CortexVertex;
import uk.ac.ox.well.cortexjdk.utils.traversal.TraversalState;

import java.util.Set;

/**
 * Created by kiran on 08/05/2017.
 */
public class BubbleClosingStopper extends AbstractTraversalStoppingRule<CortexVertex, CortexEdge> {
    @Override
    public boolean hasTraversalSucceeded(TraversalState<CortexVertex> s) {
        return s.getPreviousGraph().containsVertex(s.getCurrentVertex());
    }

    @Override
    public boolean hasTraversalFailed(TraversalState<CortexVertex> s) {
        return s.getCurrentGraphSize() > 10000 || s.getCurrentTraversalDepth() >= 2 || s.getNumAdjacentEdges() == 0;
    }
}
