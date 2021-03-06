 /*
 * The MIT License
 *
 * Copyright (c) 2012 The Broad Institute
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
package net.sf.picard.illumina.parser.readers;

 import net.sf.picard.PicardException;
 import net.sf.picard.util.UnsignedTypeUtil;
 import net.sf.samtools.util.BlockCompressedInputStream;
 import net.sf.samtools.util.CloseableIterator;
 import net.sf.samtools.util.CloserUtil;
 import net.sf.samtools.util.IOUtil;

 import java.io.EOFException;
 import java.io.File;
 import java.io.FileInputStream;
 import java.io.FileNotFoundException;
 import java.io.IOException;
 import java.io.InputStream;
 import java.nio.ByteBuffer;
 import java.nio.ByteOrder;
 import java.util.zip.GZIPInputStream;

/**
 * BCL Files are base call and quality score binary files containing a (base,quality) pair for successive clusters.
 * The file is structured as followed:
 *  Bytes 1-4 : unsigned int numClusters
 *  Bytes 5-numClusters + 5 : 1 byte base/quality score
 *
 *  The base/quality scores are organized as follows (with one exception, SEE BELOW):
 *  The right 2 most bits (these are the LEAST significant bits) indicate the base, where
 *  A=00(0x00), C=01(0x01), G=10(0x02), and T=11(0x03)
 *
 *  The remaining bytes compose the quality score which is an unsigned int.
 *
 *  EXCEPTION: If a byte is entirely 0 (e.g. byteRead == 0) then it is a no call, the base
 *  becomes '.' and the Quality becomes 2, the default illumina masking value
 *
 *  (E.g. if we get a value in binary of 10001011 it gets transformed as follows:
 *
 *  Value read: 10001011(0x8B)
 *
 *  Quality     Base
 *
 *  100010      11
 *  00100010    0x03
 *  0x22        T
 *  34          T
 *
 *  So the output base/quality will be a (T/34)
 */
public class BclReader implements CloseableIterator<BclReader.BclValue> {
    /** The size of the opening header (consisting solely of numClusters*/
    private static final int HEADER_SIZE = 4;
    
    private final BclQualityEvaluationStrategy bclQualityEvaluationStrategy;

    /** The number of clusters provided in this BCL */
    public final long numClusters;

    private final InputStream inputStream;
    private final String filePath;

    /* This strips off the leading bits in order to compare the read byte against the static values below */
    private final static byte BASE_MASK = 0x0003;
    private final static byte A_VAL = 0x00;
    private final static byte C_VAL = 0x01;
    private final static byte G_VAL = 0x02;
    private final static byte T_VAL = 0x03;

    /** The index to the next cluster that will be returned by this reader */
    private long nextCluster;

    public static boolean isGzipped(final File file) {
        return file.getAbsolutePath().endsWith(".gz");
    }

    public static boolean isBlockGzipped(final File file) {
        return file.getAbsolutePath().endsWith(".bgzf");
    }

    public static long getNumberOfClusters(final File file) {
        InputStream stream = null;
        try {
            if (isBlockGzipped(file)) stream = new BlockCompressedInputStream(IOUtil.maybeBufferedSeekableStream(file));
            else if (isGzipped(file)) stream = new GZIPInputStream(IOUtil.maybeBufferInputStream(new FileInputStream(file)));
            else stream = IOUtil.maybeBufferInputStream(new FileInputStream(file));

            return getNumberOfClusters(file.getAbsolutePath(), stream);

        } catch (final IOException ioe) {
            throw new PicardException("Could not open file " + file.getAbsolutePath() + " to get its cluster count: " + ioe.getMessage(), ioe);
        } finally {
            CloserUtil.close(stream);
        }
    }

    private static long getNumberOfClusters(final String filePath, final InputStream inputStream) {
        final byte[] header = new byte[HEADER_SIZE];

        try {
            final int headerBytesRead = inputStream.read(header);
            if (headerBytesRead != HEADER_SIZE) {
                throw new PicardException("Malformed file, expected header of size " + HEADER_SIZE + " but received " + headerBytesRead);
            }
        } catch (IOException ioe) {
            throw new PicardException("Unable to read header for file (" + filePath + ")", ioe);
        }

        final ByteBuffer headerBuf = ByteBuffer.wrap(header);
        headerBuf.order(ByteOrder.LITTLE_ENDIAN);
        return UnsignedTypeUtil.uIntToLong(headerBuf.getInt());
    }

    public class BclValue {
        public final byte base;
        public final byte quality;

        public BclValue(final byte base, final byte quality) {
            this.base = base;
            this.quality = quality;
        }
    }

    public static BclReader make(final File file, final BclQualityEvaluationStrategy bclQualityEvaluationStrategy) {
        return new BclReader(file, bclQualityEvaluationStrategy, false);    
    }

    /** 
     * Produces a {@link net.sf.picard.illumina.parser.readers.BclReader} appropriate for when the consumer intends to call 
     * {@link net.sf.picard.illumina.parser.readers.BclReader#seek(long)}.  If this functionality is not required, call
     * {@link net.sf.picard.illumina.parser.readers.BclReader#make(java.io.File, BclQualityEvaluationStrategy)}.
     */
    public static BclReader makeSeekable(final File file, final BclQualityEvaluationStrategy bclQualityEvaluationStrategy) {
        return new BclReader(file, bclQualityEvaluationStrategy, true);
    }

    BclReader(final File file, final BclQualityEvaluationStrategy bclQualityEvaluationStrategy, final boolean requiresSeekability) {
        this.bclQualityEvaluationStrategy = bclQualityEvaluationStrategy;

        filePath = file.getAbsolutePath();
        final boolean isGzip = BclReader.isGzipped(file);
        final boolean isBgzf = BclReader.isBlockGzipped(file);

        try {
            // Open up a buffered stream to read from the file and optionally wrap it in a gzip stream
            // if necessary
            if (isBgzf) {
                // Only BlockCompressedInputStreams can seek, and only if they are fed a SeekableStream.
                inputStream = new BlockCompressedInputStream(IOUtil.maybeBufferedSeekableStream(file));
            } else if (isGzip) {
                if (requiresSeekability) { 
                    throw new IllegalArgumentException(
                        String.format("Cannot create a seekable reader for gzip bcl: %s.", filePath)
                    );
                }
                inputStream = new GZIPInputStream(IOUtil.maybeBufferInputStream(new FileInputStream(file)));
            } else {
                if (requiresSeekability) {
                    throw new IllegalArgumentException(
                        String.format("Cannot create a seekable reader for provided bcl: %s.", filePath)
                    );
                }
                inputStream = IOUtil.maybeBufferInputStream(new FileInputStream(file));
            }
        } catch (FileNotFoundException fnfe) {
            throw new PicardException("File not found: (" + filePath + ")", fnfe);
        } catch (IOException ioe) {
            throw new PicardException("Error reading file: (" + filePath + ")", ioe);
        }

        // numClusters is used both for the file structure checks and in hasNext()
        numClusters = getNumClusters();

        if (file.length() == 0) {
            throw new PicardException("Zero length BCL file detected: " + filePath);
        }
        if (!isGzip && !isBgzf) {
            // The file structure checks rely on the file size (as information is stored as individual bytes) but
            // we can't reliably know the number of uncompressed bytes in the file ahead of time for gzip files. Only
            // run the main check
            assertProperFileStructure(file);
        }

        nextCluster = 0;
    }

    private long getNumClusters() {
        return getNumberOfClusters(filePath, inputStream);
    }

    protected void assertProperFileStructure(final File file) {
        final long elementsInFile = file.length() - HEADER_SIZE;
        if (numClusters != elementsInFile) {
            throw new PicardException("Expected " + numClusters + " in file " + filePath + " but found " + elementsInFile);
        }
    }

    public final boolean hasNext() {
        return this.nextCluster < this.numClusters;
    }

    public BclValue next() {
        // TODO(ish) There are multiple optimizations that could be made here if we find that this becomes a pinch
        // point. For instance base & quality could be moved into BclValue, element & elements could be moved to be
        // class members - all in an attempt to reduce the number of allocations being made.
        final byte base;
        final byte quality;

        final byte element;
        final byte[] elements = new byte[1];
        try {
            if (inputStream.read(elements) != 1) {
                throw new PicardException("Error when reading byte from file (" + filePath + ")");
            }
        } catch (EOFException eofe) {
            throw new PicardException("Attempted to read byte from file but none were available: (" + filePath + ")", eofe);
        }catch (IOException ioe) {
            throw new PicardException("Error when reading byte from file (" + filePath + ")", ioe);
        }

        element = elements[0];
        if(element == 0) { //NO CALL, don't confuse with an A call
            base = '.';
            quality = 2;
        } else {
            switch(element & BASE_MASK) {
                case A_VAL:
                    base = 'A';
                    break;

                case C_VAL:
                    base = 'C';
                    break;

                case G_VAL:
                    base = 'G';
                    break;

                case T_VAL:
                    base = 'T';
                    break;

                default:
                    throw new PicardException("Impossible case! BCL Base value neither A, C, G, nor T! Value(" + (element & BASE_MASK) + ") + in file(" + filePath + ")");
            }

            quality = bclQualityEvaluationStrategy.reviseAndConditionallyLogQuality((byte)(UnsignedTypeUtil.uByteToInt(element) >>> 2));
        }

        ++nextCluster;
        return new BclValue(base, quality);
    }

    public void close() {
        CloserUtil.close(inputStream);
    }

    public void seek(final long virtualFilePointer) {
        if (!(inputStream instanceof BlockCompressedInputStream)) {
            throw new UnsupportedOperationException("Seeking only allowed on bzgf");
        } else {
            final BlockCompressedInputStream bcis = (BlockCompressedInputStream)inputStream;
            try {
                ((BlockCompressedInputStream) inputStream).seek(virtualFilePointer);
            } catch (IOException e) {
                throw new PicardException("Problem seeking in " + filePath, e);
            }
        }
    }

    public void remove() {
        throw new UnsupportedOperationException();
    }
}

