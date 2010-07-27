/*
 * The MIT License
 *
 * Copyright (c) 2010 The Broad Institute
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

package net.sf.samtools;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Class for writing binary BAM index files
 */
class BinaryBAMIndexWriter extends AbstractBAMIndexWriter {  // note - only package visibility

    private final int bufferSize = 1048576; // faster if power of 2
    private final ByteBuffer bb;
    private final BufferedOutputStream boStream;
    private final boolean sortBins;

    /**
     * constructor
     *
     * @param n_ref    Number of reference sequences
     * @param output   BAM Index output file
     * @param sortBins Whether to sort the bins - useful for comparison to c-generated index
     */
    public BinaryBAMIndexWriter(final int n_ref, final File output, boolean sortBins) {
        super(output, n_ref);

        this.sortBins = sortBins;

        try {
            boStream = new BufferedOutputStream(new FileOutputStream(output));
            bb = ByteBuffer.allocate(bufferSize);
            bb.order(ByteOrder.LITTLE_ENDIAN);
        } catch (FileNotFoundException e) {
            throw new SAMException("Can't find output file " + output, e);
        }
    }

    public void writeHeader() {
        // magic string
        final byte[] magic = BAMFileConstants.BAM_INDEX_MAGIC;
        bb.put(magic);
        bb.putInt(n_ref);
    }

    /**
     * Write this content as binary output
     */
    public void writeReference(final BAMIndexContent content, int reference) {

        if (content == null) {
            writeNullContent(bb);
            return;
        }

        final Bin[] originalBins = content.getOriginalBins();
        if (originalBins != null){
             // good, we avoided copying the original array to a list

            final int size = content.getNumberOfBins();
            bb.putInt(size);

            // Note, originalBins is naturally sorted, so no sort is needed
            int count = 0;
            for (Bin bin : originalBins) {
                if (bin == null) continue;
                count++;
                writeBin(bin);
            }

        } else {
            // bins are only available as a list

            final List<Bin> bins = content.getBins();
            final int size = bins.size();

            if (bins == null || size == 0) {
                writeNullContent(bb);
                return;
            }

            bb.putInt(size);

            if (sortBins) {
                // copy bins into an array so that it can be sorted for text comparisons
                final Bin[] binArray = new Bin[size];
                if (size != 0) {
                    bins.toArray(binArray);
                }
                Arrays.sort(binArray);

                for (Bin bin : binArray) {
                    writeBin(bin);
                }
            } else {
                for (Bin bin : bins) {
                    writeBin(bin);
                }
            }
        }
        writeChunkMetaData(content.getMetaDataChunks());

        final LinearIndex linearIndex = content.getLinearIndex();
        final long[] entries = linearIndex == null ? null : linearIndex.getIndexEntries();
        final int indexStart = linearIndex == null ? 0 : linearIndex.getIndexStart();
        final int n_intv = entries == null ? indexStart : entries.length + indexStart;
        bb.putInt(n_intv);
        if (entries == null) {
            return;
        }

        for (int i = 0; i < indexStart; i++) {
            bb.putLong(0);          // todo uint32_t vs int32_t in spec?
        }
        for (int k = 0; k < entries.length; k++) {
            bb.putLong(entries[k]); // todo uint32_t vs int32_t in spec?
        }
        // write out data and reset the buffer for each reference
        bb.flip();       // sets position to 0

        try {
            byte[] bytesToWrite = bb.array();
            boStream.write(bytesToWrite, bb.arrayOffset(), bb.limit());
            boStream.flush();
        } catch (IOException e) {
            throw new SAMException("IOException in BinaryBAMIndexWriter reference " + reference, e);
        }

        bb.position(0);
        bb.limit(bufferSize);
    }

    private void writeBin(Bin bin) {
        if (bin.getBinNumber() == BAMIndex.MAX_BINS)  return; // meta data
        
        bb.putInt(bin.getBinNumber()); // todo uint32_t vs int32_t in spec?
        if (bin.getChunkList() == null){
            bb.putInt(0);
            return;
        }
        final List<Chunk> chunkList = bin.getChunkList();
        final int n_chunk = chunkList.size();
        bb.putInt(n_chunk);
        for (final Chunk c : chunkList) {
            bb.putLong(c.getChunkStart());   // todo uint32_t vs int32_t in spec?
            bb.putLong(c.getChunkEnd());     // todo uint32_t vs int32_t in spec?
        }
    }

    /**
     * Write the meta data represented by the chunkLists associated with bin MAX_BINS 37450
     *
     * @param chunkList contains metadata describing numAligned records, numUnAligned, etc
     */
    private void writeChunkMetaData(List<Chunk> chunkList) {
        bb.putInt(BAMIndex.MAX_BINS);
        final int n_chunk = chunkList.size();   // should be 2
        if (n_chunk != 2){
            System.err.println("Unexpected # chunks of meta data= " + n_chunk); // throw new SAMException
        }
        bb.putInt(n_chunk);
        for (final Chunk c : chunkList) {
            bb.putLong(c.getChunkStart());   // todo uint32_t vs int32_t in spec?
            bb.putLong(c.getChunkEnd());     // todo uint32_t vs int32_t in spec?
        }
   }


    private static void writeNullContent(ByteBuffer bb) {
        bb.putLong(0);  // 0 bins , 0 intv
    }

    public void close(Long noCoordinateCount) {
        bb.putLong(noCoordinateCount == null ? 0 : noCoordinateCount);
        bb.flip();
        try {
            boStream.write(bb.array(),bb.arrayOffset(), bb.limit());
            boStream.flush();
            boStream.close();
        } catch (IOException e) {
            throw new SAMException("IOException in BinaryBAMIndexWriter ", e);
        }
    }
}
