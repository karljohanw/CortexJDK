package uk.ac.ox.well.indiana.commands.primary;

import uk.ac.ox.well.indiana.commands.Module;
import uk.ac.ox.well.indiana.utils.arguments.Argument;
import uk.ac.ox.well.indiana.utils.arguments.Output;
import uk.ac.ox.well.indiana.utils.io.cortex.graph.CortexGraph;

import java.io.PrintStream;

public class head extends Module {
    @Argument(fullName="graph", shortName="g", doc="Graph")
    public CortexGraph GRAPH;

    @Argument(fullName="numRecords", shortName="n", doc="Num records")
    public Long NUM_RECORDS = 10L;

    @Output
    public PrintStream out;

    @Override
    public void execute() {
        for (long i = 0; i < (NUM_RECORDS < GRAPH.getNumRecords() ? NUM_RECORDS : GRAPH.getNumRecords()); i++) {
            out.println(GRAPH.getRecord(i));
        }
    }
}