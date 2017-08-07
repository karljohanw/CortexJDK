package uk.ac.ox.well.indiana.commands.inheritance;

import htsjdk.samtools.*;
import uk.ac.ox.well.indiana.commands.Module;
import uk.ac.ox.well.indiana.utils.arguments.Argument;
import uk.ac.ox.well.indiana.utils.arguments.Output;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by kiran on 07/08/2017.
 */
public class ChooseBestAlignment extends Module {
    @Argument(fullName="sam", shortName="s", doc="SAM file")
    public ArrayList<SamReader> SAMS;

    //@Output
    //public File out;

    @Override
    public void execute() {
        Map<String, SAMRecord> contigs = new HashMap<>();

        for (SamReader sam : SAMS) {
            for (SAMRecord sr : sam) {
                if (!contigs.containsKey(sr.getReadName())) {
                    contigs.put(sr.getReadName(), sr);
                } else {
                    contigs.put(sr.getReadName(), chooseBetterAlignment(contigs.get(sr.getReadName()), sr));
                }
            }
        }

        for (String contigName : contigs.keySet()) {
            log.info("{} {}", contigName, contigs.get(contigName));
        }
    }

    private SAMRecord chooseBetterAlignment(SAMRecord s0, SAMRecord s1) {
        int d0 = 0, d1 = 0;
        int l0 = 0, l1 = 0;

        for (CigarElement ce : s0.getCigar().getCigarElements()) {
            if (ce.getOperator().isIndelOrSkippedRegion()) {
                d0 += ce.getLength();
            }
            l0 += ce.getLength();
        }

        for (CigarElement ce : s1.getCigar().getCigarElements()) {
            if (ce.getOperator().isIndelOrSkippedRegion()) {
                d1 += ce.getLength();
            }
            l1 += ce.getLength();
        }

        double pctId0 = 100.0 * (double) d0 / (double) l0;
        double pctId1 = 100.0 * (double) d1 / (double) l1;

        return pctId0 > pctId1 ? s0 : s1;
    }
}