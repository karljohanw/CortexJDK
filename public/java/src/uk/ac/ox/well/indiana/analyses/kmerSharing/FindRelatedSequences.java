package uk.ac.ox.well.indiana.analyses.kmerSharing;

import com.google.common.base.Joiner;
import uk.ac.ox.well.indiana.tools.Tool;
import uk.ac.ox.well.indiana.utils.arguments.Argument;
import uk.ac.ox.well.indiana.utils.arguments.Output;
import uk.ac.ox.well.indiana.utils.io.cortex.CortexGraph;
import uk.ac.ox.well.indiana.utils.io.cortex.CortexRecord;
import uk.ac.ox.well.indiana.utils.io.utils.TableReader;
import uk.ac.ox.well.indiana.utils.performance.PerformanceUtils;
import uk.ac.ox.well.indiana.utils.sequence.SequenceUtils;

import java.io.File;
import java.io.PrintStream;
import java.util.*;

public class FindRelatedSequences extends Tool {
    @Argument(fullName="kmerReferencePanel", shortName="krp", doc="Kmer reference panel")
    public File KMER_REFERENCE_PANEL;

    @Argument(fullName="cortexGraph", shortName="cg", doc="Cortex graph to process")
    public CortexGraph CORTEX_GRAPH;

    @Output
    public PrintStream out;

    private class KmerInfo {
        public HashSet<String> genes = new HashSet<String>();
        public HashSet<String> domains = new HashSet<String>();

        public String getGenes() {
            return Joiner.on(",").join(genes);
        }

        public String getDomains() {
            return Joiner.on(",").join(domains);
        }
    }

    private HashMap<String, KmerInfo> loadKmerReferencePanel() {
        HashMap<String, KmerInfo> panel = new HashMap<String, KmerInfo>();

        TableReader tr = new TableReader(KMER_REFERENCE_PANEL);

        for (HashMap<String, String> entry : tr) {
            String kmer = entry.get("kmer");
            String[] genes = entry.get("genes").split(",");
            String[] domains = entry.get("domains").split(",");

            KmerInfo ki = new KmerInfo();
            ki.genes.addAll(Arrays.asList(genes));
            ki.domains.addAll(Arrays.asList(domains));

            panel.put(kmer, ki);
        }

        return panel;
    }

    private HashMap<String, CortexRecord> loadCortexRecords() {
        HashMap<String, CortexRecord> records = new HashMap<String, CortexRecord>();

        int numRecords = 0;
        for (CortexRecord cr : CORTEX_GRAPH) {
            if (numRecords % (CORTEX_GRAPH.getNumRecords()/10) == 0) {
                log.info("processed {}/{} records", numRecords, CORTEX_GRAPH.getNumRecords());
            }
            numRecords++;

            String kmer = cr.getKmerAsString();

            records.put(kmer, cr);
        }

        return records;
    }

    public String getSuperNode(String startingKmer, int color, HashMap<String, CortexRecord> records) {
        String superNode = null;

        if (records.containsKey(startingKmer)) {
            superNode = startingKmer;

            CortexRecord startingRecord = records.get(startingKmer);
            String startingEdges = startingRecord.getEdgeAsStrings()[color];

            // First do in kmers
            List<String> inRawKmers = new ArrayList<String>();
            for (int i = 0; i < 4; i++) {
                if (startingEdges.charAt(i) != '.') {
                    String inRawKmer = (startingEdges.charAt(i) + startingKmer.substring(0, startingKmer.length() - 1)).toUpperCase();

                    inRawKmers.add(inRawKmer);
                }
            }

            HashSet<String> seenKmers = new HashSet<String>();

            while (inRawKmers.size() == 1) {
                String inRawKmer = inRawKmers.get(0);
                inRawKmers.clear();

                superNode = inRawKmer.charAt(0) + superNode;

                String fw = SequenceUtils.alphanumericallyLowestOrientation(inRawKmer);

                if (seenKmers.contains(fw)) {
                    log.info("in kmer '{}' has already been seen", fw);
                }

                if (records.containsKey(fw) && !seenKmers.contains(fw)) {
                    String rc = SequenceUtils.reverseComplement(fw);

                    String currentKmer = null;

                    String edges = records.get(fw).getEdgeAsStrings()[color];

                    if (fw.substring(0, fw.length()).equalsIgnoreCase(superNode.substring(0, fw.length()))) {
                        currentKmer = fw;
                    } else if (rc.substring(0, rc.length()).equalsIgnoreCase(superNode.substring(0, rc.length()))) {
                        currentKmer = rc;
                        edges = SequenceUtils.reverseComplement(edges);
                    }

                    if (currentKmer != null) {
                        for (int i = 0; i < 4; i++) {
                            if (edges.charAt(i) != '.') {
                                String newInRawKmer = (edges.charAt(i) + currentKmer.substring(0, currentKmer.length() - 1)).toUpperCase();
                                inRawKmers.add(newInRawKmer);
                            }
                        }
                    }

                    seenKmers.add(currentKmer);
                }

                if (inRawKmers.size() > 1) {
                    log.info("in kmer '{}' has more than one edge ({})", fw, inRawKmers);
                }
            }

            // Now do out kmers
            List<String> outRawKmers = new ArrayList<String>();
            for (int i = 4; i < 8; i++) {
                if (startingEdges.charAt(i) != '.') {
                    String outRawKmer = (startingKmer.substring(1, startingKmer.length()) + startingEdges.charAt(i)).toUpperCase();

                    outRawKmers.add(outRawKmer);
                }
            }

            seenKmers.clear();

            while (outRawKmers.size() == 1) {
                String outRawKmer = outRawKmers.get(0);
                outRawKmers.clear();

                superNode = superNode + outRawKmer.charAt(outRawKmer.length() - 1);

                String fw = SequenceUtils.alphanumericallyLowestOrientation(outRawKmer);

                if (seenKmers.contains(fw)) {
                    log.info("out kmer '{}' has already been seen", fw);
                }

                if (records.containsKey(fw) && !seenKmers.contains(fw)) {
                    String rc = SequenceUtils.reverseComplement(fw);

                    String currentKmer = null;

                    String edges = records.get(fw).getEdgeAsStrings()[color];

                    if (fw.substring(0, fw.length()).equalsIgnoreCase(superNode.substring(superNode.length() - fw.length(), superNode.length()))) {
                        currentKmer = fw;
                    } else if (rc.substring(0, rc.length()).equalsIgnoreCase(superNode.substring(superNode.length() - rc.length(), superNode.length()))) {
                        currentKmer = rc;
                        edges = SequenceUtils.reverseComplement(edges);
                    }

                    if (currentKmer != null) {
                        for (int i = 4; i < 8; i++) {
                            if (edges.charAt(i) != '.') {
                                String newOutRawKmer = (currentKmer.substring(1, currentKmer.length()) + edges.charAt(i)).toUpperCase();
                                outRawKmers.add(newOutRawKmer);
                            }
                        }
                    }

                    seenKmers.add(currentKmer);
                }

                if (outRawKmers.size() > 1) {
                    log.info("out kmer '{}' has more than one edge ({})", fw, outRawKmers);
                }
            }
        }

        return superNode;
    }

    private void getSuperNodes(HashMap<String, KmerInfo> panel, HashMap<String, CortexRecord> records) {
        out.println("color\tkmer\tgenes\tdomains\tsuperNode");

        int numKmers = 0;
        for (String kmer : panel.keySet()) {
            //if (numKmers % (panel.keySet().size()/10) == 0) {
            //    log.info("processed {}/{} kmers", numKmers, panel.keySet().size());
            //}
            //numKmers++;

            for (int color = 0; color < CORTEX_GRAPH.getNumColors(); color++) {
                String superNode = getSuperNode(kmer, color, records);

                if (superNode != null) {
                    out.format("%d\t%s\t%s\t%s\t%s\n", color, kmer, panel.get(kmer).getGenes(), panel.get(kmer).getDomains(), superNode);
                }
            }
        }
    }

    @Override
    public void execute() {
        log.info("[performance] {}", PerformanceUtils.getMemoryUsageStats());

        log.info("Loading kmer reference panel from '{}'", KMER_REFERENCE_PANEL.getAbsolutePath());
        HashMap<String, KmerInfo> panel = loadKmerReferencePanel();

        log.info("Loading Cortex records from '{}'", CORTEX_GRAPH.getCortexFile().getAbsolutePath());
        HashMap<String, CortexRecord> records = loadCortexRecords();

        log.info("Getting supernodes");
        getSuperNodes(panel, records);

        log.info("[performance] {}", PerformanceUtils.getMemoryUsageStats());
    }
}
