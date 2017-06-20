package uk.ac.ox.well.indiana.commands.playground.assembly;

import com.google.common.base.Joiner;
import htsjdk.samtools.reference.FastaSequenceFile;
import htsjdk.samtools.util.Interval;
import uk.ac.ox.well.indiana.commands.Module;
import uk.ac.ox.well.indiana.utils.alignment.kmer.KmerLookup;
import uk.ac.ox.well.indiana.utils.arguments.Argument;
import uk.ac.ox.well.indiana.utils.arguments.Output;
import uk.ac.ox.well.indiana.utils.io.cortex.graph.CortexGraph;
import uk.ac.ox.well.indiana.utils.io.cortex.graph.CortexKmer;
import uk.ac.ox.well.indiana.utils.io.cortex.graph.CortexRecord;
import uk.ac.ox.well.indiana.utils.io.cortex.links.CortexLinksMap;
import uk.ac.ox.well.indiana.utils.io.cortex.links.CortexLinksRecord;
import uk.ac.ox.well.indiana.utils.sequence.SequenceUtils;
import uk.ac.ox.well.indiana.utils.traversal.TraversalEngine;

import java.io.PrintStream;
import java.util.*;

/**
 * Created by kiran on 16/06/2017.
 */
public class StudyValidatedNAHR extends Module {
    @Argument(fullName="graph", shortName="g", doc="Graph")
    public CortexGraph GRAPH;

    @Argument(fullName="parents", shortName="p", doc="Parents")
    public ArrayList<String> PARENTS;

    @Argument(fullName="child", shortName="c", doc="Child")
    public String CHILD;

    @Argument(fullName="roi", shortName="r", doc="ROIs")
    public CortexGraph ROI;

    @Argument(fullName="lookup", shortName="l", doc="Lookup")
    public KmerLookup LOOKUP;

    @Argument(fullName="sequence", shortName="s", doc="Sequence")
    public FastaSequenceFile SEQUENCE;

    @Argument(fullName="links", shortName="links", doc="Links")
    public CortexLinksMap LINKS;

    @Output
    public PrintStream out;

    @Override
    public void execute() {
        int childColor = GRAPH.getColorForSampleName(CHILD);
        List<Integer> parentColors = GRAPH.getColorsForSampleNames(PARENTS);

        Set<Integer> colors = new TreeSet<>();
        colors.add(childColor);
        colors.addAll(parentColors);

        log.info("Colors:");
        for (int c : colors) {
            log.info("  {} ({})", c, GRAPH.getSampleName(c));
        }

        String seq = SEQUENCE.nextSequence().getBaseString();

        for (int i = 0; i <= seq.length() - GRAPH.getKmerSize(); i++) {
            String sk = seq.substring(i, i + GRAPH.getKmerSize());
            CortexKmer ck = new CortexKmer(sk);
            CortexRecord cr = GRAPH.findRecord(ck);
            CortexRecord rr = ROI.findRecord(ck);
            CortexLinksRecord clr = LINKS.containsKey(ck) ? LINKS.get(ck) : null;

            if (cr != null) {
                Map<Integer, Set<String>> pks = TraversalEngine.getAllPrevKmers(cr, ck.isFlipped());
                Map<Integer, Set<String>> nks = TraversalEngine.getAllNextKmers(cr, ck.isFlipped());

                log.info("{} {} {} {} {}", pks.get(childColor).size(), nks.get(childColor).size(), rr != null, clr != null, recordToString(sk, cr, colors));
            } else {
                log.info("{} {} {} {} {}", 0, 0, rr != null, clr != null, null);
            }

            if (clr != null) {
                log.info("{}", clr);
            }
        }
    }

    private String recordToString(String sk, CortexRecord cr, Set<Integer> colors) {
        String kmer = cr.getKmerAsString();
        String cov = "";
        String ed = "";

        boolean fw = sk.equals(kmer);

        if (!fw) {
            kmer = SequenceUtils.reverseComplement(kmer);
        }

        int color = 0;
        for (int coverage : cr.getCoverages()) {
            if (colors.contains(color)) {
                cov += " " + coverage;
            }
            color++;
        }

        color = 0;
        for (String edge : cr.getEdgeAsStrings()) {
            if (colors.contains(color)) {
                ed += " " + (fw ? edge : SequenceUtils.reverseComplement(edge));
            }
            color++;
        }

        Set<String> lss = new TreeSet<>();
        if (LOOKUP != null) {
            Set<Interval> loci = LOOKUP.findKmer(kmer);

            if (loci != null && loci.size() > 0) {
                for (Interval locus : loci) {
                    String ls = locus.getContig() + ":" + locus.getStart() + "-" + locus.getEnd() + ":" + (locus.isPositiveStrand() ? "+" : "-");
                    lss.add(ls);
                }
            }
        }
        String lssCombined = Joiner.on(";").join(lss);

        return kmer + " " + cov + " " + ed + " " + lssCombined;
    }
}
