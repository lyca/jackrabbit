/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jackrabbit.aws.ext;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.jcr.RepositoryException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jackrabbit.core.data.LazyFileInputStream;
import org.apache.jackrabbit.util.TransientFileFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements a LRU cache used by {@link CachingDataStore}. If cache
 * size exceeds limit, this cache goes in purge mode. In purge mode any
 * operation to cache is no-op. After purge cache size would be less than
 * cachePurgeResizeFactor * maximum size.
 */
public class LocalCache {

    /**
     * Logger instance.
     */
    static final Logger LOG = LoggerFactory.getLogger(LocalCache.class);

    /**
     * The file names of the files that need to be deleted.
     */
    final Set<String> toBeDeleted = new HashSet<String>();

    /**
     * The filename Vs file size LRU cache.
     */
    LRUCache cache;

    /**
     * The directory where the files are created.
     */
    private final File directory;

    /**
     * The directory where tmp files are created.
     */
    private final File tmp;

    /**
     * The maximum size of cache in bytes.
     */
    private long maxSize;

    /**
     * If true cache is in purgeMode and not available. All operation would be
     * no-op.
     */
    private volatile boolean purgeMode;

    /**
     * Build LRU cache of files located at 'path'. It uses lastModified property
     * of file to build LRU cache. If cache size exceeds limit size, this cache
     * goes in purge mode. In purge mode any operation to cache is no-op.
     * 
     * @param path file system path
     * @param tmpPath temporary directory used by cache.
     * @param maxSize maximum size of cache.
     * @param cachePurgeTrigFactor factor which triggers cache to purge mode.
     * That is if current size exceed (cachePurgeTrigFactor * maxSize), the
     * cache will go in auto-purge mode.
     * @param cachePurgeResizeFactor after cache purge size of cache will be
     * just less (cachePurgeResizeFactor * maxSize).
     * @throws RepositoryException
     */
    public LocalCache(final String path, final String tmpPath,
            final long maxSize, final double cachePurgeTrigFactor,
            final double cachePurgeResizeFactor) throws RepositoryException {
        this.maxSize = maxSize;
        directory = new File(path);
        tmp = new File(tmpPath);
        cache = new LRUCache(maxSize, cachePurgeTrigFactor,
            cachePurgeResizeFactor);
        ArrayList<File> allFiles = new ArrayList<File>();

        Iterator<File> it = FileUtils.iterateFiles(directory, null, true);
        while (it.hasNext()) {
            File f = it.next();
            allFiles.add(f);
        }
        Collections.sort(allFiles, new Comparator<File>() {
            @Override
            public int compare(final File o1, final File o2) {
                long l1 = o1.lastModified(), l2 = o2.lastModified();
                return l1 < l2 ? -1 : l1 > l2 ? 1 : 0;
            }
        });
        String dataStorePath = directory.getAbsolutePath();
        long time = System.currentTimeMillis();
        int count = 0;
        int deletecount = 0;
        for (File f : allFiles) {
            if (f.exists()) {
                long length = f.length();
                String name = f.getPath();
                if (name.startsWith(dataStorePath)) {
                    name = name.substring(dataStorePath.length());
                }
                // convert to java path format
                name = name.replace("\\", "/");
                if (name.startsWith("/") || name.startsWith("\\")) {
                    name = name.substring(1);
                }
                if ((cache.currentSizeInBytes + length) < cache.maxSizeInBytes) {
                    count++;
                    cache.put(name, length);
                } else {
                    if (tryDelete(name)) {
                        deletecount++;
                    }
                }
                long now = System.currentTimeMillis();
                if (now > time + 5000) {
                    LOG.info("Processed {" + (count + deletecount) + "}/{"
                        + allFiles.size() + "}");
                    time = now;
                }
            }
        }
        LOG.info("Cached {" + count + "}/{" + allFiles.size()
            + "} , currentSizeInBytes = " + cache.currentSizeInBytes);
        LOG.info("Deleted {" + deletecount + "}/{" + allFiles.size()
            + "} files .");
    }

    /**
     * Store an item in the cache and return the input stream. If cache is in
     * purgeMode or file doesn't exists, inputstream from a
     * {@link TransientFileFactory#createTransientFile(String, String, File)} is
     * returned. Otherwise inputStream from cached file is returned. This method
     * doesn't close the incoming inputstream.
     * 
     * @param fileName the key of cache.
     * @param in the inputstream.
     * @return the (new) input stream.
     */
    public synchronized InputStream store(String fileName, final InputStream in)
            throws IOException {
        fileName = fileName.replace("\\", "/");
        File f = getFile(fileName);
        long length = 0;
        if (!f.exists() || isInPurgeMode()) {
            OutputStream out = null;
            File transFile = null;
            try {
                TransientFileFactory tff = TransientFileFactory.getInstance();
                transFile = tff.createTransientFile("s3-", "tmp", tmp);
                out = new BufferedOutputStream(new FileOutputStream(transFile));
                length = IOUtils.copyLarge(in, out);
            } finally {
                IOUtils.closeQuietly(out);
            }
            // rename the file to local fs cache
            if (canAdmitFile(length)
                && (f.getParentFile().exists() || f.getParentFile().mkdirs())
                && transFile.renameTo(f) && f.exists()) {
                if (transFile.exists() && transFile.delete()) {
                    LOG.warn("tmp file = " + transFile.getAbsolutePath()
                        + " not deleted successfully");
                }
                transFile = null;
                toBeDeleted.remove(fileName);
                if (cache.get(fileName) == null) {
                    cache.put(fileName, f.length());
                }
            } else {
                f = transFile;
            }
        } else {
            // f.exists and not in purge mode
            f.setLastModified(System.currentTimeMillis());
            toBeDeleted.remove(fileName);
            if (cache.get(fileName) == null) {
                cache.put(fileName, f.length());
            }
        }
        cache.tryPurge();
        return new LazyFileInputStream(f);
    }

    /**
     * Store an item along with file in cache. Cache size is increased by
     * {@link File#length()} If file already exists in cache,
     * {@link File#setLastModified(long)} is updated with current time.
     * 
     * @param fileName the key of cache.
     * @param src file to be added to cache.
     * @throws IOException
     */
    public synchronized void store(String fileName, final File src)
            throws IOException {
        fileName = fileName.replace("\\", "/");
        File dest = getFile(fileName);
        File parent = dest.getParentFile();
        if (src.exists() && !dest.exists() && !src.equals(dest)
            && canAdmitFile(src.length())
            && (parent.exists() || parent.mkdirs()) && (src.renameTo(dest))) {
            toBeDeleted.remove(fileName);
            if (cache.get(fileName) == null) {
                cache.put(fileName, dest.length());
            }

        } else if (dest.exists()) {
            dest.setLastModified(System.currentTimeMillis());
            toBeDeleted.remove(fileName);
            if (cache.get(fileName) == null) {
                cache.put(fileName, dest.length());
            }
        }
        cache.tryPurge();
    }

    /**
     * Return the inputstream from from cache, or null if not in the cache.
     * 
     * @param fileName name of file.
     * @return  stream or null.
     */
    public InputStream getIfStored(String fileName) throws IOException {

        fileName = fileName.replace("\\", "/");
        File f = getFile(fileName);
        synchronized (this) {
            if (!f.exists() || isInPurgeMode()) {
                log("purgeMode true or file doesn't exists: getIfStored returned");
                return null;
            }
            f.setLastModified(System.currentTimeMillis());
            return new LazyFileInputStream(f);
        }
    }

    /**
     * Delete file from cache. Size of cache is reduced by file length. The
     * method is no-op if file doesn't exist in cache.
     * 
     * @param fileName file name that need to be removed from cache.
     */
    public synchronized void delete(String fileName) {
        if (isInPurgeMode()) {
            log("purgeMode true :delete returned");
            return;
        }
        fileName = fileName.replace("\\", "/");
        cache.remove(fileName);
    }

    /**
     * Returns length of file if exists in cache else returns null.
     * @param fileName name of the file.
     */
    public Long getFileLength(String fileName) {
        fileName = fileName.replace("\\", "/");
        File f = getFile(fileName);
        synchronized (this) {
            if (!f.exists() || isInPurgeMode()) {
                log("purgeMode true or file doesn't exists: getFileLength returned");
                return null;
            }
            f.setLastModified(System.currentTimeMillis());
            return f.length();
        }
    }

    /**
     * Close the cache. Cache maintain set of files which it was not able to
     * delete successfully. This method will an attempt to delete all
     * unsuccessful delete files.
     */
    public void close() {
        log("close");
        deleteOldFiles();
    }

    /**
     * Check if cache can admit file of given length.
     * @param length of the file.
     * @return true if yes else return false.
     */
    private synchronized boolean canAdmitFile(final long length) {
        // order is important here
        boolean value = !isInPurgeMode() && cache.canAdmitFile(length);
        if (!value) {
            log("cannot admit file of length=" + length
                + " and currentSizeInBytes=" + cache.currentSizeInBytes);
        }
        return value;
    }

    /**
     * Return true if cache is in purge mode else return false.
     */
    synchronized boolean isInPurgeMode() {
        return purgeMode || maxSize == 0;
    }

    /**
     * Set purge mode. If set to true all cache operation will be no-op. If set
     * to false, all operations to cache are available.
     * 
     * @param purgeMode purge mode
     */
    synchronized void setPurgeMode(final boolean purgeMode) {
        this.purgeMode = purgeMode;
    }

    File getFile(final String fileName) {
        return new File(directory, fileName);
    }

    private void deleteOldFiles() {
        int initialSize = toBeDeleted.size();
        int count = 0;
        for (String n : new ArrayList<String>(toBeDeleted)) {
            if (tryDelete(n)) {
                count++;
            }
        }
        LOG.info("deleted [" + count + "]/[" + initialSize + "] files");
    }

    /**
     * This method tries to delete a file. If it is not able to delete file due
     * to any reason, it add it toBeDeleted list.
     * 
     * @param fileName name of the file which will be deleted.
     * @return true if this method deletes file successfuly else return false.
     */
    boolean tryDelete(final String fileName) {
        log("cache delete " + fileName);
        File f = getFile(fileName);
        if (f.exists() && f.delete()) {
            log(fileName + "  deleted successfully");
            toBeDeleted.remove(fileName);
            while (true) {
                f = f.getParentFile();
                if (f.equals(directory) || f.list().length > 0) {
                    break;
                }
                // delete empty parent folders (except the main directory)
                f.delete();
            }
            return true;
        } else if (f.exists()) {
            LOG.info("not able to delete file = " + f.getAbsolutePath());
            toBeDeleted.add(fileName);
            return false;
        }
        return true;
    }

    static int maxSizeElements(final long bytes) {
        // after a CQ installation, the average item in
        // the data store is about 52 KB
        int count = (int) (bytes / 65535);
        count = Math.max(1024, count);
        count = Math.min(64 * 1024, count);
        return count;
    }

    static void log(final String s) {
        LOG.debug(s);
    }

    /**
     * A LRU based extension {@link LinkedHashMap}. The key is file name and
     * value is length of file.
     */
    private class LRUCache extends LinkedHashMap<String, Long> {
        private static final long serialVersionUID = 1L;

        volatile long currentSizeInBytes;

        final long maxSizeInBytes;

        long cachePurgeResize;
        
        private long cachePurgeTrigSize;

        public LRUCache(final long maxSizeInBytes,
                final double cachePurgeTrigFactor,
                final double cachePurgeResizeFactor) {
            super(maxSizeElements(maxSizeInBytes), (float) 0.75, true);
            this.maxSizeInBytes = maxSizeInBytes;
            this.cachePurgeTrigSize = new Double(cachePurgeTrigFactor
                * maxSizeInBytes).longValue();
            this.cachePurgeResize = new Double(cachePurgeResizeFactor
                * maxSizeInBytes).longValue();
        }

        /**
         * Overridden {@link Map#remove(Object)} to delete corresponding file
         * from file system.
         */
        @Override
        public synchronized Long remove(final Object key) {
            String fileName = (String) key;
            fileName = fileName.replace("\\", "/");
            Long flength = null;
            if (tryDelete(fileName)) {
                flength = super.remove(key);
                if (flength != null) {
                    log("cache entry { " + fileName + "} with size {" + flength
                        + "} removed.");
                    currentSizeInBytes -= flength.longValue();
                }
            } else if (!getFile(fileName).exists()) {
                // second attempt. remove from cache if file doesn't exists
                flength = super.remove(key);
                if (flength != null) {
                    log(" file not exists. cache entry { " + fileName
                        + "} with size {" + flength + "} removed.");
                    currentSizeInBytes -= flength.longValue();
                }
            }
            return flength;
        }

        @Override
        public synchronized Long put(final String key, final Long value) {
            long flength = value.longValue();
            currentSizeInBytes += flength;
            return super.put(key.replace("\\", "/"), value);
        }

        /**
         * This method tries purging of local cache. It checks if local cache
         * has exceeded the defined limit then it triggers purge cache job in a
         * seperate thread.
         */
        synchronized void tryPurge() {
            if (currentSizeInBytes > cachePurgeTrigSize && !isInPurgeMode()) {
                setPurgeMode(true);
                LOG.info("currentSizeInBytes[" + cache.currentSizeInBytes
                    + "] exceeds (cachePurgeTrigSize)["
                    + cache.cachePurgeTrigSize + "]");
                new Thread(new PurgeJob()).start();
            }
        }
        /**
         * This method check if cache can admit file of given length. 
         * @param length length of file.
         * @return true if cache size + length is less than maxSize.
         */
        synchronized boolean canAdmitFile(final long length) {
            return cache.currentSizeInBytes + length < cache.maxSizeInBytes;
        }
    }

    /**
     * This class performs purging of local cache. It implements
     * {@link Runnable} and should be invoked in a separate thread.
     */
    private class PurgeJob implements Runnable {
        public PurgeJob() {
            // TODO Auto-generated constructor stub
        }

        /**
         * This method purges local cache till its size is less than
         * cacheResizefactor * maxSize
         */
        @Override
        public void run() {
            try {
                synchronized (cache) {
                    LOG.info(" cache purge job started");
                    // first try to delete toBeDeleted files
                    int initialSize = cache.size();
                    for (String fileName : new ArrayList<String>(toBeDeleted)) {
                        cache.remove(fileName);
                    }
                    Iterator<Map.Entry<String, Long>> itr = cache.entrySet().iterator();
                    while (itr.hasNext()) {
                        Map.Entry<String, Long> entry = itr.next();
                        if (entry.getKey() != null) {
                            if (cache.currentSizeInBytes > cache.cachePurgeResize) {
                                itr.remove();

                            } else {
                                break;
                            }
                        }

                    }
                    LOG.info(" cache purge job completed: cleaned ["
                        + (initialSize - cache.size())
                        + "] files and currentSizeInBytes = [ "
                        + cache.currentSizeInBytes + "]");
                }
            } catch (Exception e) {
                LOG.error("error in purge jobs:", e);
            } finally {
                setPurgeMode(false);
            }
        }
    }
}
