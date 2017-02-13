/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.sf.picard.sam;

import net.sf.picard.PicardException;
import net.sf.picard.cmdline.CommandLineProgram;
import net.sf.picard.cmdline.Option;
import net.sf.picard.cmdline.StandardOptionDefinitions;
import net.sf.picard.cmdline.Usage;
import net.sf.picard.fastq.FastqRecord;
import net.sf.picard.fastq.FastqWriter;
import net.sf.picard.fastq.FastqWriterFactory;
import net.sf.picard.io.IoUtil;
import net.sf.picard.util.Log;
import net.sf.picard.util.ProgressLogger;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMReadGroupRecord;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMUtils;
import net.sf.samtools.util.SequenceUtil;
import net.sf.samtools.util.StringUtil;

import java.io.File;
import java.util.*;

/**
 * $Id$
 * <p/>
 * Extracts read sequences and qualities from the input SAM/BAM file and writes them into
 * the output file in Sanger fastq format.
 * See <a href="http://maq.sourceforge.net/fastq.shtml">MAQ FastQ specification</a> for details.
 * In the RC mode (default is True), if the read is aligned and the alignment is to the reverse strand on the genome,
 * the read's sequence from input sam file will be reverse-complemented prior to writing it to fastq in order restore correctly
 * the original read sequence as it was generated by the sequencer.
 */
public class SamToFastq extends CommandLineProgram {
    @Usage
    public String USAGE = "Extracts read sequences and qualities from the input SAM/BAM file and writes them into "+
        "the output file in Sanger fastq format. In the RC mode (default is True), if the read is aligned and the alignment is to the reverse strand on the genome, "+
        "the read's sequence from input SAM file will be reverse-complemented prior to writing it to fastq in order restore correctly "+
        "the original read sequence as it was generated by the sequencer.";

    @Option(doc="Input SAM/BAM file to extract reads from", shortName=StandardOptionDefinitions.INPUT_SHORT_NAME)
    public File INPUT ;

    @Option(shortName="F", doc="Output fastq file (single-end fastq or, if paired, first end of the pair fastq).", mutex={"OUTPUT_PER_RG"})
    public File FASTQ ;

    @Option(shortName="F2", doc="Output fastq file (if paired, second end of the pair fastq).", optional=true, mutex={"OUTPUT_PER_RG"})
    public File SECOND_END_FASTQ ;

    @Option(shortName="OPRG", doc="Output a fastq file per read group (two fastq files per read group if the group is paired).", optional=true, mutex={"FASTQ", "SECOND_END_FASTQ"})
    public boolean OUTPUT_PER_RG ;

    @Option(shortName="ODIR", doc="Directory in which to output the fastq file(s).  Used only when OUTPUT_PER_RG is true.", optional=true)
    public File OUTPUT_DIR;

    @Option(shortName="RC", doc="Re-reverse bases and qualities of reads with negative strand flag set before writing them to fastq", optional=true)
    public boolean RE_REVERSE = true;

    @Option(shortName="NON_PF", doc="Include non-PF reads from the SAM file into the output FASTQ files.")
    public boolean INCLUDE_NON_PF_READS = false;

    @Option(shortName="CLIP_ATTR", doc="The attribute that stores the position at which " +
            "the SAM record should be clipped", optional=true)
    public String CLIPPING_ATTRIBUTE;

    @Option(shortName="CLIP_ACT", doc="The action that should be taken with clipped reads: " +
            "'X' means the reads and qualities should be trimmed at the clipped position; " +
            "'N' means the bases should be changed to Ns in the clipped region; and any " +
            "integer means that the base qualities should be set to that value in the " +
            "clipped region.", optional=true)
    public String CLIPPING_ACTION;

    @Option(shortName="R1_TRIM", doc="The number of bases to trim from the beginning of read 1.")
    public int READ1_TRIM = 0;

    @Option(shortName="R1_MAX_BASES", doc="The maximum number of bases to write from read 1 after trimming. " +
            "If there are fewer than this many bases left after trimming, all will be written.  If this " +
            "value is null then all bases left after trimming will be written.", optional=true)
    public Integer READ1_MAX_BASES_TO_WRITE;

    @Option(shortName="R2_TRIM", doc="The number of bases to trim from the beginning of read 2.")
    public int READ2_TRIM = 0;

    @Option(shortName="R2_MAX_BASES", doc="The maximum number of bases to write from read 2 after trimming. " +
            "If there are fewer than this many bases left after trimming, all will be written.  If this " +
            "value is null then all bases left after trimming will be written.", optional=true)
    public Integer READ2_MAX_BASES_TO_WRITE;

    @Option(doc="If true, include non-primary alignments in the output.  Support of non-primary alignments in SamToFastq " +
    "is not comprehensive, so there may be exceptions if this is set to true and there are paired reads with non-primary alignments.")
    public boolean INCLUDE_NON_PRIMARY_ALIGNMENTS=false;

    private final Log log = Log.getInstance(SamToFastq.class);
    
    public static void main(final String[] argv) {
        System.exit(new SamToFastq().instanceMain(argv));
    }

    protected int doWork() {
        IoUtil.assertFileIsReadable(INPUT);
        final SAMFileReader reader = new SAMFileReader(IoUtil.openFileForReading(INPUT));
        final Map<String,SAMRecord> firstSeenMates = new HashMap<String,SAMRecord>();
        final FastqWriterFactory factory = new FastqWriterFactory();
        final Map<SAMReadGroupRecord, List<FastqWriter>> writers = getWriters(reader.getFileHeader().getReadGroups(), factory);

        final ProgressLogger progress = new ProgressLogger(log);
        for (final SAMRecord currentRecord : reader) {
            if (currentRecord.getNotPrimaryAlignmentFlag() && !INCLUDE_NON_PRIMARY_ALIGNMENTS)
                continue;

            // Skip non-PF reads as necessary
            if (currentRecord.getReadFailsVendorQualityCheckFlag() && !INCLUDE_NON_PF_READS)
                continue;

            final List<FastqWriter> fq = writers.get(currentRecord.getReadGroup());

            if (currentRecord.getReadPairedFlag()) {
                final String currentReadName = currentRecord.getReadName();
                final SAMRecord firstRecord = firstSeenMates.remove(currentReadName);
                if (firstRecord == null) {
                    firstSeenMates.put(currentReadName, currentRecord);
                } else {
                    assertPairedMates(firstRecord, currentRecord);

                    if (fq.size() == 1) {
                        if (OUTPUT_PER_RG) {
                            fq.add(factory.newWriter(makeReadGroupFile(currentRecord.getReadGroup(), "_2")));
                        } else {
                            throw new PicardException("Input contains paired reads but no SECOND_END_FASTQ specified.");
                        }
                    }

                    final SAMRecord read1 =
                        currentRecord.getFirstOfPairFlag() ? currentRecord : firstRecord;
                    final SAMRecord read2 =
                        currentRecord.getFirstOfPairFlag() ? firstRecord : currentRecord;
                    writeRecord(read1, 1, fq.get(0), READ1_TRIM, READ1_MAX_BASES_TO_WRITE);
                    writeRecord(read2, 2, fq.get(1), READ2_TRIM, READ2_MAX_BASES_TO_WRITE);

                }
            } else {
                writeRecord(currentRecord, null, fq.get(0), READ1_TRIM, READ1_MAX_BASES_TO_WRITE);
            }

            progress.record(currentRecord);
        }

        reader.close();

        // Close all the fastq writers being careful to close each one only once!
        final IdentityHashMap<FastqWriter,FastqWriter> seen = new IdentityHashMap<FastqWriter, FastqWriter>();
        for (final List<FastqWriter> listOfWriters : writers.values()) {
            for (final FastqWriter w : listOfWriters) {
                if (!seen.containsKey(w)) {
                    w.close();
                    seen.put(w,w);
                }
            }
        }

        if (firstSeenMates.size() > 0) {
            throw new PicardException("Found " + firstSeenMates.size() + " unpaired mates");
        }

        return 0;
    }

    /**
     * Gets the pair of writers for a given read group or, if we are not sorting by read group,
     * just returns the single pair of writers.
     */
    private Map<SAMReadGroupRecord, List<FastqWriter>> getWriters(final List<SAMReadGroupRecord> samReadGroupRecords,
                                                                  final FastqWriterFactory factory) {

        final Map<SAMReadGroupRecord, List<FastqWriter>> writerMap = new HashMap<SAMReadGroupRecord, List<FastqWriter>>();

        if (!OUTPUT_PER_RG) {
            // If we're not outputting by read group, there's only
            // one writer for each end.
            final List<FastqWriter> fqw = new ArrayList<FastqWriter>();

            IoUtil.assertFileIsWritable(FASTQ);
            IoUtil.openFileForWriting(FASTQ);
            fqw.add(factory.newWriter(FASTQ));

            if (SECOND_END_FASTQ != null) {
                IoUtil.assertFileIsWritable(SECOND_END_FASTQ);
                IoUtil.openFileForWriting(SECOND_END_FASTQ);
                fqw.add(factory.newWriter(SECOND_END_FASTQ));
            }
            // Store in map with null key, in case there are reads without read group.
            writerMap.put(null, fqw);
            // Also store for every read group in header.
            for (final SAMReadGroupRecord rg : samReadGroupRecords) {
                writerMap.put(rg, fqw);
            }
        } else {
            for (final SAMReadGroupRecord rg : samReadGroupRecords) {
                final List<FastqWriter> fqw = new ArrayList<FastqWriter>();

                fqw.add(factory.newWriter(makeReadGroupFile(rg, "_1")));
                writerMap.put(rg, fqw);
            }
        }
        return writerMap;
    }


    private File makeReadGroupFile(final SAMReadGroupRecord readGroup, final String preExtSuffix) {
        String fileName = readGroup.getPlatformUnit();
        if (fileName == null) fileName = readGroup.getReadGroupId();
        fileName = IoUtil.makeFileNameSafe(fileName);
        if(preExtSuffix != null) fileName += preExtSuffix;
        fileName += ".fastq";

        final File result = (OUTPUT_DIR != null)
                          ? new File(OUTPUT_DIR, fileName)
                          : new File(fileName);
        IoUtil.assertFileIsWritable(result);
        return result;
    }

    void writeRecord(final SAMRecord read, final Integer mateNumber, final FastqWriter writer,
                     final int basesToTrim, final Integer maxBasesToWrite) {
        final String seqHeader = mateNumber==null ? read.getReadName() : read.getReadName() + "/"+ mateNumber;
        String readString = read.getReadString();
        String baseQualities = read.getBaseQualityString();

        // If we're clipping, do the right thing to the bases or qualities
        if (CLIPPING_ATTRIBUTE != null) {
            final Integer clipPoint = (Integer)read.getAttribute(CLIPPING_ATTRIBUTE);
            if (clipPoint != null) {
                if (CLIPPING_ACTION.equalsIgnoreCase("X")) {
                    readString = clip(readString, clipPoint, null,
                            !read.getReadNegativeStrandFlag());
                    baseQualities = clip(baseQualities, clipPoint, null,
                            !read.getReadNegativeStrandFlag());

                }
                else if (CLIPPING_ACTION.equalsIgnoreCase("N")) {
                    readString = clip(readString, clipPoint, 'N',
                            !read.getReadNegativeStrandFlag());
                }
                else {
                    final char newQual = SAMUtils.phredToFastq(
                            new byte[] { (byte)Integer.parseInt(CLIPPING_ACTION)}).charAt(0);
                    baseQualities = clip(baseQualities, clipPoint, newQual,
                            !read.getReadNegativeStrandFlag());
                }
            }
        }
        if ( RE_REVERSE && read.getReadNegativeStrandFlag() ) {
            readString = SequenceUtil.reverseComplement(readString);
            baseQualities = StringUtil.reverseString(baseQualities);
        }
        if (basesToTrim > 0) {
            readString = readString.substring(basesToTrim);
            baseQualities = baseQualities.substring(basesToTrim);
        }

        if (maxBasesToWrite != null && maxBasesToWrite < readString.length()) {
            readString = readString.substring(0, maxBasesToWrite);
            baseQualities = baseQualities.substring(0, maxBasesToWrite);
        }

        if (read.getAttribute("BC") != null &&
            read.getAttribute("QT") != null &&
            read.getAttribute("br") != null &&
            (read.getAttribute("qr") != null || read.getAttribute("bq") != null)) {
            readString = (String)read.getAttribute("br") + (String)read.getAttribute("BC") + readString;
            baseQualities = (String)read.getAttribute("QT") + baseQualities;
            if (read.getAttribute("qr") != null) {
                baseQualities = (String)read.getAttribute("qr") + baseQualities;
            }
            else if (read.getAttribute("bq") != null) {
                baseQualities = (String)read.getAttribute("bq") + baseQualities;
            }
        }

        writer.write(new FastqRecord(seqHeader, readString, "", baseQualities));

    }

    /**
     * Utility method to handle the changes required to the base/quality strings by the clipping
     * parameters.
     *
     * @param src           The string to clip
     * @param point         The 1-based position of the first clipped base in the read
     * @param replacement   If non-null, the character to replace in the clipped positions
     *                      in the string (a quality score or 'N').  If null, just trim src
     * @param posStrand     Whether the read is on the positive strand
     * @return String       The clipped read or qualities
     */
    private String clip(final String src, final int point, final Character replacement, final boolean posStrand) {
        final int len = src.length();
        String result = posStrand ? src.substring(0, point-1) : src.substring(len-point+1);
        if (replacement != null) {
            if (posStrand) {
                for (int i = point; i <= len; i++ ) {
                    result += replacement;
                }
            }
            else {
                for (int i = 0; i <= len-point; i++) {
                    result = replacement + result;
                }
            }
        }
        return result;
    }

    private void assertPairedMates(final SAMRecord record1, final SAMRecord record2) {
        if (! (record1.getFirstOfPairFlag() && record2.getSecondOfPairFlag() ||
               record2.getFirstOfPairFlag() && record1.getSecondOfPairFlag() ) ) {
            throw new PicardException("Illegal mate state: " + record1.getReadName());
        }
    }


    /**
    * Put any custom command-line validation in an override of this method.
    * clp is initialized at this point and can be used to print usage and access argv.
     * Any options set by command-line parser can be validated.
    * @return null if command line is valid.  If command line is invalid, returns an array of error
    * messages to be written to the appropriate place.
    */
    protected String[] customCommandLineValidation() {
        if ((CLIPPING_ATTRIBUTE != null && CLIPPING_ACTION == null) ||
            (CLIPPING_ATTRIBUTE == null && CLIPPING_ACTION != null)) {
            return new String[] {
                    "Both or neither of CLIPPING_ATTRIBUTE and CLIPPING_ACTION should be set." };
        }
        if (CLIPPING_ACTION != null) {
            if (CLIPPING_ACTION.equals("N") || CLIPPING_ACTION.equals("X")) {
                // Do nothing, this is fine
            }
            else {
                try {
                    Integer.parseInt(CLIPPING_ACTION);
                }
                catch (NumberFormatException nfe) {
                    return new String[] {"CLIPPING ACTION must be one of: N, X, or an integer"};
                }
            }
        }
        if ((OUTPUT_PER_RG && OUTPUT_DIR == null) || ((!OUTPUT_PER_RG) && OUTPUT_DIR != null)) {
            return new String[] {
                    "If OUTPUT_PER_RG is true, then OUTPUT_DIR should be set. " +
                    "If " };
        }


        return null;
    }
}
