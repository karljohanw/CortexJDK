package uk.ac.ox.well.cortexjdk.commands.call.call;

import com.google.common.base.Joiner;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import org.apache.commons.math3.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DirectedWeightedPseudograph;
import uk.ac.ox.well.cortexjdk.commands.Module;
import uk.ac.ox.well.cortexjdk.utils.alignment.reference.IndexedReference;
import uk.ac.ox.well.cortexjdk.utils.arguments.Argument;
import uk.ac.ox.well.cortexjdk.utils.arguments.Output;
import uk.ac.ox.well.cortexjdk.utils.io.graph.cortex.CortexGraph;
import uk.ac.ox.well.cortexjdk.utils.io.graph.cortex.CortexRecord;
import uk.ac.ox.well.cortexjdk.utils.io.graph.links.CortexLinks;
import uk.ac.ox.well.cortexjdk.utils.kmer.CanonicalKmer;
import uk.ac.ox.well.cortexjdk.utils.kmer.CortexByteKmer;
import uk.ac.ox.well.cortexjdk.utils.progress.ProgressMeter;
import uk.ac.ox.well.cortexjdk.utils.progress.ProgressMeterFactory;
import uk.ac.ox.well.cortexjdk.utils.sequence.SequenceUtils;
import uk.ac.ox.well.cortexjdk.utils.stoppingrules.BubbleClosingStopper;
import uk.ac.ox.well.cortexjdk.utils.stoppingrules.NovelContinuationStopper;
import uk.ac.ox.well.cortexjdk.utils.traversal.CortexEdge;
import uk.ac.ox.well.cortexjdk.utils.traversal.CortexVertex;
import uk.ac.ox.well.cortexjdk.utils.traversal.TraversalEngine;
import uk.ac.ox.well.cortexjdk.utils.traversal.TraversalEngineFactory;

import java.io.PrintStream;
import java.util.*;

import static uk.ac.ox.well.cortexjdk.utils.traversal.TraversalEngineConfiguration.GraphCombinationOperator.OR;
import static uk.ac.ox.well.cortexjdk.utils.traversal.TraversalEngineConfiguration.TraversalDirection.FORWARD;

/**
 * Created by kiran on 30/08/2017.
 */
public class Call extends Module {
    @Argument(fullName = "graph", shortName = "g", doc = "Graph")
    public CortexGraph GRAPH;

    @Argument(fullName = "links", shortName = "l", doc = "Links", required=false)
    public ArrayList<CortexLinks> LINKS;

    @Argument(fullName = "references", shortName = "R", doc = "References")
    public HashMap<String, IndexedReference> REFERENCES;

    @Argument(fullName = "roi", shortName = "r", doc = "ROI")
    public CortexGraph ROI;

    @Argument(fullName = "maxWalkLength", shortName = "m", doc = "Max walk length")
    public Integer MAX_WALK_LENGTH = Integer.MAX_VALUE;

    @Output
    public PrintStream out;

    @Output(fullName="fout", shortName="fo", doc="Fasta out")
    public PrintStream fout;

    @Override
    public void execute() {
        Map<CanonicalKmer, Boolean> seen = new HashMap<>();
        for (CortexRecord cr : ROI) {
            seen.put(cr.getCanonicalKmer(), false);
        }

        TraversalEngine e = new TraversalEngineFactory()
                .traversalColor(GRAPH.getColorForSampleName(ROI.getSampleName(0)))
                .joiningColors(GRAPH.getColorsForSampleNames(REFERENCES.keySet()))
                .combinationOperator(OR)
                .stoppingRule(NovelContinuationStopper.class)
                .graph(GRAPH)
                .links(LINKS)
                .references(REFERENCES.values())
                .rois(ROI)
                .maxWalkLength(MAX_WALK_LENGTH)
                .make();

        ProgressMeter pm = new ProgressMeterFactory()
                .header("Processing novel kmers")
                .message("processed")
                .maxRecord(seen.size())
                .make(log);

        Map<CanonicalKmer, List<CortexVertex>> longWalks = new HashMap<>();

        for (CanonicalKmer ck : seen.keySet()) {
            if (!seen.get(ck)) {
                List<CortexVertex> l = longWalk(seen, e, ck);

                for (CortexVertex v : l) {
                    if (seen.containsKey(v.getCanonicalKmer())) {
                        if (!longWalks.containsKey(v.getCanonicalKmer()) || longWalks.get(v.getCanonicalKmer()).size() < l.size()) {
                            longWalks.put(v.getCanonicalKmer(), l);
                        }

                        seen.put(v.getCanonicalKmer(), true);
                    }
                }
            }

            pm.update();
        }

        pm = new ProgressMeterFactory()
                .header("Reducing contig set...")
                .message("processed")
                .maxRecord(longWalks.size())
                .make(log);

        Map<String, List<CortexVertex>> longContigs = new HashMap<>();
        for (List<CortexVertex> l : longWalks.values()) {
            String longContig = SequenceUtils.alphanumericallyLowestOrientation(TraversalEngine.toContig(l));
            longContigs.put(longContig, l);

            pm.update();
        }
        log.info("  {} contigs remaining", longContigs.size());

        log.info("Calling mutations in contigs:");
        int contigIndex = 0;
        for (String longContig : longContigs.keySet()) {
            fout.println(">" + contigIndex);
            fout.println(longContig);

            List<CortexVertex> l = longContigs.get(longContig);

            log.info("  index={} length={}", contigIndex, longContig.length());

            log.info("  - novels before bubble closing {}/{}", numNovels(l, seen), numNovels(longContigs.get(longContig), seen));
            List<CortexVertex> p = closeBubbles(l, seen, contigIndex);
            log.info("  - novels after bubble closing {}/{}", numNovels(p, seen), numNovels(longContigs.get(longContig), seen));

            if (numNovels(p, seen) > 10) {
                Pair<List<List<CortexVertex>>, List<Pair<Integer, Integer>>> m = breakContigs(p, seen);
                List<List<CortexVertex>> s = m.getFirst();
                List<Pair<Integer, Integer>> b = m.getSecond();

                List<SAMRecord> srs = new ArrayList<>();
                Set<String> chrs = new HashSet<>();
                int numPieces = 0;
                int maxLength = 0;
                for (int i = 0; i < s.size(); i++) {
                    List<CortexVertex> q = s.get(i);
                    String contig = TraversalEngine.toContig(q);

                    SAMRecord sr = chooseBestAlignment(contig, 10);
                    srs.add(sr);

                    if (sr != null) {
                        chrs.add(sr.getReferenceName());
                        numPieces++;
                        if (contig.length() > maxLength) {
                            maxLength = contig.length();
                        }
                    }
                }

                if (chrs.size() > 1 && numPieces > 1 && maxLength >= GRAPH.getKmerSize() + 1) {
                    for (int i = 0; i < s.size(); i++) {
                        SAMRecord sr = srs.get(i);

                        String chr = "unknown";
                        int start = 0;
                        int stop = 0;
                        String type = "BRK";
                        String refAllele = ".";
                        String cigar = ".";
                        String strand = "+";

                        if (sr != null) {
                            chr = sr.getReferenceName();
                            start = sr.getAlignmentStart();
                            stop = sr.getAlignmentEnd();
                            refAllele = sr.getReadString();
                            cigar = sr.getCigarString();
                            strand = sr.getReadNegativeStrandFlag() ? "-" : "+";
                        }

                        log.info("      brk {} {} {} {} {} {} {} {} {} {} {}", contigIndex, l.size(), s.get(i).size(), b.get(i).getFirst(), b.get(i).getSecond(), chr, start, stop, strand, type, cigar);
                        out.println(Joiner.on("\t").join(contigIndex, l.size(), s.get(i).size(), b.get(i).getFirst(), b.get(i).getSecond(), chr, start, stop, strand, type, cigar, refAllele));
                    }
                }
            }

            contigIndex++;
        }
    }

    private Pair<List<List<CortexVertex>>, List<Pair<Integer, Integer>>> breakContigs(List<CortexVertex> w, Map<CanonicalKmer, Boolean> seen) {
        Set<CanonicalKmer> breakpoints = new HashSet<>();

        boolean inNovelRun = false;
        for (CortexVertex v : w) {
            if (seen.containsKey(v.getCanonicalKmer())) {
                if (!inNovelRun) {
                    breakpoints.add(v.getCanonicalKmer());
                }
                inNovelRun = true;
            } else {
                if (inNovelRun) {
                    breakpoints.add(v.getCanonicalKmer());
                }
                inNovelRun = false;
            }
        }

        List<List<CortexVertex>> s = new ArrayList<>();

        List<CortexVertex> a = new ArrayList<>();
        for (int i = 0; i < w.size(); i++) {
            CortexVertex v = w.get(i);

            if (breakpoints.contains(v.getCanonicalKmer())) {
                s.add(a);
                a = new ArrayList<>();
            }

            a.add(v);
        }

        if (a.size() > 0) {
            s.add(a);
        }

        List<List<CortexVertex>> r = new ArrayList<>();
        List<Pair<Integer, Integer>> e = new ArrayList<>();
        int start = 0;
        for (List<CortexVertex> q : s) {
            if (q.size() > 0 && !seen.containsKey(q.get(0).getCanonicalKmer())) {
                r.add(q);
                e.add(new Pair<>(start, start + q.size()));
            }

            start += q.size();
        }

        //log.info("    {} {}", r.size(), e.size());

        return new Pair<>(r, e);
    }

    private int numNovels(List<CortexVertex> w, Map<CanonicalKmer, Boolean> seen) {
        int numNovels = 0;

        for (CortexVertex v : w) {
            if (seen.containsKey(v.getCanonicalKmer())) {
                numNovels++;
            }
        }

        return numNovels;
    }

    @NotNull
    private List<CortexVertex> longWalk(Map<CanonicalKmer, Boolean> seen, TraversalEngine e, CanonicalKmer ck) {
        List<CortexVertex> w = e.walk(ck.getKmerAsString());
        //log.info("w: {}", w.size());

        boolean extended;
        do {
            extended = false;
            List<List<CortexVertex>> extFwd = new ArrayList<>();

            Set<CortexVertex> nvs = e.getNextVertices(w.get(w.size() - 1).getKmerAsByteKmer());
            for (CortexVertex cv : nvs) {
                List<CortexVertex> wn = e.walk(cv.getKmerAsString(), true);
                wn.add(0, cv);
                //log.info("  wn: {}", wn.size());

                boolean hasNovels = false;

                for (CortexVertex v : wn) {
                    if (seen.containsKey(v.getCanonicalKmer()) && !seen.get(v.getCanonicalKmer())) {
                        hasNovels = true;
                        break;
                    }
                }

                if (hasNovels) {
                    extFwd.add(wn);
                }
            }

            if (extFwd.size() == 1) {
                w.addAll(extFwd.get(0));
                extended = true;

                for (CortexVertex v : extFwd.get(0)) {
                    if (seen.containsKey(v.getCanonicalKmer())) {
                        seen.put(v.getCanonicalKmer(), true);
                    }
                }
            }
        } while (extended);

        do {
            extended = false;
            List<List<CortexVertex>> extRev = new ArrayList<>();

            Set<CortexVertex> pvs = e.getPrevVertices(w.get(0).getKmerAsByteKmer());
            for (CortexVertex cv : pvs) {
                List<CortexVertex> wp = e.walk(cv.getKmerAsString(), false);
                wp.add(cv);
                //log.info("  wp: {}", wp.size());

                boolean hasNovels = false;

                for (CortexVertex v : wp) {
                    if (seen.containsKey(v.getCanonicalKmer()) && !seen.get(v.getCanonicalKmer())) {
                        hasNovels = true;
                        break;
                    }
                }

                if (hasNovels) {
                    extRev.add(wp);
                }
            }

            if (extRev.size() == 1) {
                w.addAll(0, extRev.get(0));
                extended = true;

                for (CortexVertex v : extRev.get(0)) {
                    if (seen.containsKey(v.getCanonicalKmer())) {
                        seen.put(v.getCanonicalKmer(), true);
                    }
                }
            }
        } while (extended);

        return w;
    }

    private static String[] contigsToAlleles(String s0, String s1) {
        int s0start = 0, s0end = s0.length();
        int s1start = 0, s1end = s1.length();

        for (int i = 0, j = 0; i < s0.length() && j < s1.length(); i++, j++) {
            if (s0.charAt(i) != s1.charAt(j)) {
                s0start = i;
                s1start = j;
                break;
            }
        }

        for (int i = s0.length() - 1, j = s1.length() - 1; i >= 0 && j >= 0; i--, j--) {
            if (s0.charAt(i) != s1.charAt(j) || i == s0start - 1 || j == s1start - 1) {
                s0end = i + 1;
                s1end = j + 1;
                break;
            }
        }

        String[] pieces = new String[4];
        pieces[0] = s0.substring(0, s0start);
        pieces[1] = s0.substring(s0start, s0end);
        pieces[2] = s1.substring(s1start, s1end);
        pieces[3] = s0.substring(s0end, s0.length());

        return pieces;
    }

    private class LittleBubble implements Comparable<LittleBubble> {
        public String refContig;
        public String altContig;
        public List<CortexVertex> refPath;
        public List<CortexVertex> altPath;
        public Integer start;
        public Integer stop;

        @Override
        public int compareTo(@NotNull LittleBubble o) {
            return start.compareTo(o.start);
        }

        @Override
        public String toString() {
            return "LittleBubble{" +
                    "refContig='" + refContig + '\'' +
                    ", altContig='" + altContig + '\'' +
                    ", refPath=" + refPath +
                    ", altPath=" + altPath +
                    ", start=" + start +
                    ", stop=" + stop +
                    '}';
        }
    }

    private List<CortexVertex> closeBubbles(List<CortexVertex> w, Map<CanonicalKmer, Boolean> seen, int contigIndex) {
        DirectedWeightedPseudograph<CortexVertex, CortexEdge> g = new DirectedWeightedPseudograph<>(CortexEdge.class);
        Map<CortexVertex, Integer> indices = new HashMap<>();
        indices.put(w.get(0), 0);

        for (int i = 1; i < w.size(); i++) {
            CortexVertex v0 = w.get(i - 1);
            CortexVertex v1 = w.get(i);

            g.addVertex(v0);
            g.addVertex(v1);
            g.addEdge(v0, v1, new CortexEdge());

            indices.put(w.get(i), i);
        }

        TraversalEngine e = new TraversalEngineFactory()
                .joiningColors(GRAPH.getColorForSampleName(ROI.getSampleName(0)))
                .traversalDirection(FORWARD)
                .combinationOperator(OR)
                .stoppingRule(BubbleClosingStopper.class)
                .graph(GRAPH)
                .links(LINKS)
                .references(REFERENCES.values())
                .make();

        Map<Integer, LittleBubble> l = new TreeMap<>();

        for (String parent : REFERENCES.keySet()) {
            e.getConfiguration().setTraversalColor(GRAPH.getColorForSampleName(parent));
            e.getConfiguration().setRecruitmentColors(GRAPH.getColorForSampleName(REFERENCES.get(parent).getSources().iterator().next()));

            for (int i = 0; i < w.size() - 1; i++) {
                CortexVertex vi = w.get(i);

                if (seen.containsKey(vi.getCanonicalKmer())) {
                    List<CortexVertex> roots = new ArrayList<>();
                    List<CortexVertex> sources = new ArrayList<>();

                    int lowerLimit = i - 3 * vi.getCortexRecord().getKmerSize() >= 0 ? i - 3 * vi.getCortexRecord().getKmerSize() : 0;
                    for (int j = i - 1; j >= lowerLimit; j--) {
                        CortexVertex vj = w.get(j);
                        CortexVertex vk = w.get(j + 1);

                        if (!seen.containsKey(vj.getCanonicalKmer())) {
                            Set<CortexVertex> nvs = e.getNextVertices(new CortexByteKmer(vj.getKmerAsString()));

                            for (CortexVertex cv : nvs) {
                                if (!cv.equals(vk)) {
                                    roots.add(vj);
                                    sources.add(cv);
                                }
                            }
                        }
                    }

                    DirectedWeightedPseudograph<CortexVertex, CortexEdge> sinks = new DirectedWeightedPseudograph<>(CortexEdge.class);
                    int distanceFromLastNovel = 0;
                    for (int j = i + 1; j < w.size() && distanceFromLastNovel < 3 * vi.getCortexRecord().getKmerSize(); j++) {
                        CortexVertex vj = w.get(j);
                        sinks.addVertex(vj);

                        if (seen.containsKey(vj.getCanonicalKmer())) {
                            distanceFromLastNovel = 0;
                        } else {
                            distanceFromLastNovel++;
                        }
                    }

                    e.getConfiguration().setPreviousTraversal(sinks);

                    for (int q = 0; q < sources.size(); q++) {
                        CortexVertex root = roots.get(q);
                        CortexVertex source = sources.get(q);

                        DirectedWeightedPseudograph<CortexVertex, CortexEdge> b = e.dfs(source.getKmerAsString());

                        if (b != null) {
                            CortexVertex sink = null;
                            int sinkIndex = -1;
                            for (CortexVertex v : b.vertexSet()) {
                                if (indices.containsKey(v) && indices.get(v) > sinkIndex) {
                                    sink = v;
                                    sinkIndex = indices.get(sink);
                                }
                            }

                            GraphPath<CortexVertex, CortexEdge> gp = DijkstraShortestPath.findPathBetween(b, source, sink);

                            List<CortexVertex> refPath = new ArrayList<>();
                            refPath.add(root);

                            for (CortexVertex v : gp.getVertexList()) {
                                refPath.add(v);
                            }

                            List<CortexVertex> altPath = new ArrayList<>();

                            for (int j = indices.get(root); j <= indices.get(sink); j++) {
                                altPath.add(w.get(j));
                            }

                            String refContig = TraversalEngine.toContig(refPath);
                            String altContig = TraversalEngine.toContig(altPath);

                            LittleBubble lb = new LittleBubble();
                            lb.refContig = refContig;
                            lb.altContig = altContig;
                            lb.refPath = refPath;
                            lb.altPath = altPath;
                            lb.start = indices.get(root);
                            lb.stop = indices.get(sink);

                            l.put(lb.start, lb);

                            i = lb.stop - 1;
                        }
                    }
                }
            }
        }

        List<CortexVertex> wp = new ArrayList<>();
        int usedBubbles = 0;

        for (int i = 0; i < w.size(); i++) {
            if (!l.containsKey(i) || l.get(i).stop < i) {
                wp.add(w.get(i));
            } else {
                LittleBubble lb = l.get(i);

                usedBubbles++;

                for (CortexVertex v : lb.refPath) {
                    wp.add(v);
                }

                SAMRecord sr = chooseBestAlignment(lb.refContig, 10);

                //log.info("  {}", lb);
                //log.info("  - {}", sr == null ? "null" : sr.getSAMString().trim());

                if (sr != null) {
                    String[] pieces = contigsToAlleles(lb.refContig, lb.altContig);
                    String refAllele = pieces[1];
                    String altAllele = pieces[2];
                    int start = sr.getAlignmentStart() + pieces[0].length();
                    if (sr.getReadNegativeStrandFlag()) {
                        refAllele = SequenceUtils.reverseComplement(pieces[1]);
                        altAllele = SequenceUtils.reverseComplement(pieces[2]);
                        start = sr.getAlignmentStart() + pieces[3].length();
                    }
                    int stop = start + altAllele.length();

                    String type = "UKN";
                    if (refAllele.length() == 1 && altAllele.length() == 1) {
                        type = "SNV";
                    } else if (refAllele.length() > 1 && altAllele.length() > 1) {
                        type = "MNP";
                    } else if (refAllele.length() == 0 && altAllele.length() > 0) {
                        type = "INS";
                    } else if (refAllele.length() > 0 && altAllele.length() == 0) {
                        type = "DEL";
                    }
                    String strand = "+";

                    if (refAllele.isEmpty()) { refAllele = "."; }
                    if (altAllele.isEmpty()) { altAllele = "."; }

                    log.info("      bub {} {} {} {} {} {} {} {} {} {} {} {}", contigIndex, w.size(), altAllele.length(), i, i + altAllele.length(), sr.getReferenceName(), start, stop, strand, type, altAllele, refAllele);
                    out.println(Joiner.on("\t").join(contigIndex, w.size(), w.size(), i, i + altAllele.length(), sr.getReferenceName(), start, stop, strand, type, altAllele, refAllele));
                }

                i = lb.stop;
            }
        }

        //log.info("  - closed {}/{} bubbles", usedBubbles, l.size());

        return wp;
    }

    private SAMRecord chooseBestAlignment(String contig, int mqThreshold) {
        SAMRecord bestRecord = null;
        int bestRecordScore = Integer.MAX_VALUE;

        for (IndexedReference kl : REFERENCES.values()) {
            List<SAMRecord> recs = kl.getAligner().align(contig);
            List<SAMRecord> filteredRecs = new ArrayList<>();

            for (SAMRecord rec : recs) {
                if (rec.getMappingQuality() >= mqThreshold) {
                    filteredRecs.add(rec);
                }
            }

            if (filteredRecs.size() == 1) {
                SAMRecord filteredRec = filteredRecs.get(0);
                int filteredRecScore = scoreAlignment(filteredRec);

                if (filteredRecScore < bestRecordScore) {
                    bestRecord = filteredRec;
                    bestRecordScore = filteredRecScore;
                }
            }
        }

        return bestRecord;
    }

    private int scoreAlignment(SAMRecord record) {
        int basesChanged = record.getIntegerAttribute("NM");

        for (CigarElement ce : record.getCigar()) {
            if (ce.getOperator().equals(CigarOperator.SOFT_CLIP) ||
                    ce.getOperator().equals(CigarOperator.INSERTION) ||
                    ce.getOperator().equals(CigarOperator.DELETION)) {
                basesChanged += ce.getLength();
            }
        }

        return basesChanged;
    }
}
