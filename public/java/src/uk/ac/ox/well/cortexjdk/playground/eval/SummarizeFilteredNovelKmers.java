package uk.ac.ox.well.cortexjdk.playground.eval;

import uk.ac.ox.well.cortexjdk.commands.Module;
import uk.ac.ox.well.cortexjdk.utils.arguments.Argument;
import uk.ac.ox.well.cortexjdk.utils.arguments.Output;
import uk.ac.ox.well.cortexjdk.utils.io.graph.cortex.CortexGraph;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * Created by kiran on 27/08/2017.
 */
public class SummarizeFilteredNovelKmers extends Module {
    @Argument(fullName="graph", shortName="g", doc="Graph")
    public ArrayList<CortexGraph> GRAPHS;

    @Output
    public PrintStream out;

    @Override
    public void execute() {
        Map<String, Long> counts = new TreeMap<>();

        for (CortexGraph cg : GRAPHS) {
            String filterName = cg.getFile().getName();
            filterName = filterName.replaceFirst(".+rois.", "");
            filterName = filterName.replaceAll(".ctx", "");
            filterName = filterName.replaceAll("ctx", "");

            //counts.put(cg.getNumRecords(), filterName);
            counts.put(filterName, cg.getNumRecords());
        }

        out.println("\t" + GRAPHS.get(0).getSampleName(0));

        for (String name : counts.keySet()) {
            String[] s = name.split("\\.");

            if (s[0].equals("")) {
                s[0] = "Unfiltered";
            }

            //log.info("{} {}", s[0], l);

            out.println(s[0] + "\t" + counts.get(name));
        }
    }
}
