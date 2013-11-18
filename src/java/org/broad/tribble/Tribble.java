/*
 * The MIT License
 *
 * Copyright (c) 2013 The Broad Institute
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
package org.broad.tribble;

import java.io.File;

/**
 * Common, tribble wide constants and static functions
 */
public class Tribble {
    private Tribble() { } // can't be instantiated

    public final static String STANDARD_INDEX_EXTENSION = ".idx";

    /**
     * Return the name of the index file for the provided {@code filename}
     * Does not actually create an index
     * @param filename
     * @return
     */
    public static String indexFile(String filename) {
        return indexFile(filename, STANDARD_INDEX_EXTENSION);
    }

    /**
     * Return the File of the index file for the provided {@code file}
     * Does not actually create an index
     * @param file
     * @return
     */
    public static File indexFile(File file) {
        return new File(file.getAbsoluteFile() + STANDARD_INDEX_EXTENSION);
    }

    /**
     * Add the {@code indexExtension} to the {@code filepath}, preserving
     * query string elements if present. Intended for use where {@code filepath}
     * is a URL. Will behave correctly on regular file paths (just add the extension
     * to the end)
     * @param filepath
     * @param indexExtension
     * @return
     */
    public static String indexFile(String filepath, String indexExtension) {
        if(!filepath.contains(":/")){
            return filepath + indexExtension;
        }
        String[] parts = filepath.split("\\?", 2);
        String indexFile = parts[0] + indexExtension;
        String qs = parts.length == 2 ? parts[1] : null;
        if(qs != null && qs.length() > 0){
            indexFile += "?" + qs;
        }
        return indexFile;
    }
}
