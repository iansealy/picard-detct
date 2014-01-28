/*
* Copyright (c) 2012 The Broad Institute
* 
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
* 
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.variant.variantcontext.writer;

import net.sf.samtools.Defaults;
import net.sf.samtools.SAMSequenceDictionary;
import net.sf.samtools.util.BlockCompressedOutputStream;
import org.broad.tribble.index.IndexCreator;

import java.io.*;
import java.util.EnumSet;

/**
 * Factory methods to create VariantContext writers
 *
 * @author depristo
 * @since 5/12
 */
public class VariantContextWriterFactory {

    public static final EnumSet<Options> DEFAULT_OPTIONS = EnumSet.of(Options.INDEX_ON_THE_FLY);
    public static final EnumSet<Options> NO_OPTIONS = EnumSet.noneOf(Options.class);

    static {
        if (Defaults.USE_ASYNC_IO) {
            DEFAULT_OPTIONS.add(Options.USE_ASYNC_IO);
        }
    }

    private VariantContextWriterFactory() {}

    public static VariantContextWriter create(final File location, final SAMSequenceDictionary refDict) {
        return create(location, openOutputStream(location), refDict, DEFAULT_OPTIONS);
    }

    public static VariantContextWriter create(final File location, final SAMSequenceDictionary refDict, final EnumSet<Options> options) {
        return create(location, openOutputStream(location), refDict, options);
    }

    public static VariantContextWriter create(final File location,
                                              final OutputStream output,
                                              final SAMSequenceDictionary refDict) {
        return create(location, output, refDict, DEFAULT_OPTIONS);
    }

    public static VariantContextWriter create(final OutputStream output,
                                              final SAMSequenceDictionary refDict,
                                              final EnumSet<Options> options) {
        return create(null, output, refDict, options);
    }

    public static VariantContextWriter create(final File location,
                                              final OutputStream output,
                                              final SAMSequenceDictionary refDict,
                                              final EnumSet<Options> options) {
        final boolean enableBCF = isBCFOutput(location, options);

        final VariantContextWriter ret;
        if ( enableBCF )
            ret = new BCF2Writer(location, output, refDict,
                    options.contains(Options.INDEX_ON_THE_FLY),
                    options.contains(Options.DO_NOT_WRITE_GENOTYPES));
        else {
            ret =  new VCFWriter(location, maybeBgzfWrapOutputStream(location, output, options), refDict,
                    options.contains(Options.INDEX_ON_THE_FLY),
                    options.contains(Options.DO_NOT_WRITE_GENOTYPES),
                    options.contains(Options.ALLOW_MISSING_FIELDS_IN_HEADER));
        }
        if (options.contains(Options.USE_ASYNC_IO)) return new AsyncVariantContextWriter(ret, AsyncVariantContextWriter.DEFAULT_QUEUE_SIZE);
        else return ret;
    }

    public static VariantContextWriter create(final File location,
                                              final OutputStream output,
                                              final SAMSequenceDictionary refDict,
                                              final IndexCreator indexCreator,
                                              final EnumSet<Options> options) {
        final boolean enableBCF = isBCFOutput(location, options);

        final VariantContextWriter ret;
        if ( enableBCF )
            ret = new BCF2Writer(location, output, refDict, indexCreator,
                    options.contains(Options.INDEX_ON_THE_FLY),
                    options.contains(Options.DO_NOT_WRITE_GENOTYPES));
        else {
            ret =  new VCFWriter(location, maybeBgzfWrapOutputStream(location, output, options), refDict, indexCreator,
                    options.contains(Options.INDEX_ON_THE_FLY),
                    options.contains(Options.DO_NOT_WRITE_GENOTYPES),
                    options.contains(Options.ALLOW_MISSING_FIELDS_IN_HEADER));
        }
        if (options.contains(Options.USE_ASYNC_IO)) return new AsyncVariantContextWriter(ret, AsyncVariantContextWriter.DEFAULT_QUEUE_SIZE);
        else return ret;
    }

    private static OutputStream maybeBgzfWrapOutputStream(final File location, OutputStream output,
                                                          final EnumSet<Options> options) {
        if (options.contains(Options.INDEX_ON_THE_FLY) &&
                (isCompressedVcf(location) || output instanceof BlockCompressedOutputStream)) {
            throw new IllegalArgumentException("VCF index creation not supported for vcf.gz output format.");
        }
        if (!(output instanceof BlockCompressedOutputStream)) {
            if (isCompressedVcf(location)) {
                output = new BlockCompressedOutputStream(output, location);
            }
        }
        return output;
    }

    /**
     * Should we output a BCF file based solely on the name of the file at location?
     *
     * @param location
     * @return
     */
    public static boolean isBCFOutput(final File location) {
        return isBCFOutput(location, EnumSet.noneOf(Options.class));
    }

    public static boolean isBCFOutput(final File location, final EnumSet<Options> options) {
        return options.contains(Options.FORCE_BCF) || (location != null && location.getName().contains(".bcf"));
    }

    public static boolean isCompressedVcf(final File location) {
        return location != null && location.getName().endsWith(".gz");
    }

    public static VariantContextWriter sortOnTheFly(final VariantContextWriter innerWriter, final int maxCachingStartDistance) {
        return sortOnTheFly(innerWriter, maxCachingStartDistance, false);
    }

    public static VariantContextWriter sortOnTheFly(final VariantContextWriter innerWriter, final int maxCachingStartDistance, final boolean takeOwnershipOfInner) {
        return new SortingVariantContextWriter(innerWriter, maxCachingStartDistance, takeOwnershipOfInner);
    }

    /**
     * Returns a output stream writing to location, or throws an exception if this fails
     * @param location
     * @return
     */
    protected static OutputStream openOutputStream(final File location) {
        try {
            return new FileOutputStream(location);
        } catch (final FileNotFoundException e) {
            throw new RuntimeException(location + ": Unable to create VCF writer", e);
        }
    }
}
