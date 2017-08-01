package uk.ac.ox.well.indiana.commands.caller.call;

import com.google.common.base.Joiner;
import htsjdk.samtools.util.Interval;
import htsjdk.samtools.util.IntervalTreeMap;
import org.apache.commons.math3.util.Pair;
import uk.ac.ox.well.indiana.commands.Module;
import uk.ac.ox.well.indiana.utils.alignment.kmer.KmerLookup;
import uk.ac.ox.well.indiana.utils.arguments.Argument;
import uk.ac.ox.well.indiana.utils.arguments.Output;
import uk.ac.ox.well.indiana.utils.containers.ContainerUtils;
import uk.ac.ox.well.indiana.utils.exceptions.IndianaException;
import uk.ac.ox.well.indiana.utils.io.cortex.graph.CortexGraph;
import uk.ac.ox.well.indiana.utils.io.cortex.graph.CortexKmer;
import uk.ac.ox.well.indiana.utils.io.cortex.graph.CortexRecord;
import uk.ac.ox.well.indiana.utils.io.table.TableReader;
import uk.ac.ox.well.indiana.utils.progress.ProgressMeter;
import uk.ac.ox.well.indiana.utils.progress.ProgressMeterFactory;
import uk.ac.ox.well.indiana.utils.traversal.TraversalEngine;

import java.io.File;
import java.io.PrintStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static uk.ac.ox.well.indiana.utils.sequence.SequenceUtils.computeSplits;
import static uk.ac.ox.well.indiana.utils.sequence.SequenceUtils.splitAtPositions;

/**
 * Created by kiran on 23/06/2017.
 */
public class Call extends Module {
    @Argument(fullName="graph", shortName="g", doc="Graph")
    public CortexGraph GRAPH;

    @Argument(fullName="child", shortName="c", doc="Child")
    public String CHILD;

    @Argument(fullName="parents", shortName="p", doc="Parents")
    public ArrayList<String> PARENTS;

    @Argument(fullName="roi", shortName="r", doc="ROI")
    public CortexGraph ROI;

    @Argument(fullName="drafts", shortName="d", doc="Drafts")
    public HashMap<String, KmerLookup> LOOKUPS;

    @Argument(fullName="reference", shortName="R", doc="Reference")
    public KmerLookup REFERENCE;

    @Argument(fullName="annotations", shortName="a", doc="Annotated contigs")
    public File ANNOTATIONS;

    @Output
    public PrintStream out;

    @Override
    public void execute() {
        int childColor = GRAPH.getColorForSampleName(CHILD);
        List<Integer> parentColors = GRAPH.getColorsForSampleNames(PARENTS);

        Map<String, List<Map<String, String>>> allAnnotations = loadAnnotations();

        ProgressMeter pm = new ProgressMeterFactory()
                .header("Processing contigs")
                .message("contigs processed")
                .maxRecord(allAnnotations.size())
                .make(log);

        Map<CortexKmer, Boolean> rois = loadRois();
        Set<CortexKmer> newRois = new HashSet<>();

        for (String contigName : allAnnotations.keySet()) {
            for (Map<String, String> e : allAnnotations.get(contigName)) {
                CortexKmer ck = new CortexKmer(e.get("kmer"));
                boolean isNovel = e.get("code").equals(".");

                if (isNovel) {
                    if (rois.containsKey(ck)) {
                        rois.put(ck, true);
                    } else {
                        newRois.add(ck);
                    }
                }
            }

            pm.update();
        }

        log.info("{} {}", rois.size(), newRois.size());

        for (CortexKmer rk : rois.keySet()) {
            log.info("rk: {} {}", rk, rois.get(rk));
        }
    }

    private Map<CortexKmer, Boolean> loadRois() {
        Map<CortexKmer, Boolean> rois = new HashMap<>();

        for (CortexRecord cr : ROI) {
            rois.put(cr.getCortexKmer(), false);
        }

        return rois;
    }

    private Map<String, List<Map<String, String>>> loadAnnotations() {
        TableReader tr = new TableReader(ANNOTATIONS);

        Map<String, List<Map<String, String>>> contigs = new TreeMap<>();

        for (Map<String, String> m : tr) {
            if (!contigs.containsKey(m.get("contigName"))) {
                contigs.put(m.get("contigName"), new ArrayList<>());
            }
            contigs.get(m.get("contigName")).add(m);
        }

        return contigs;
    }
}
