/*
 * Copyright 2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.state.mem;

import org.apache.jackrabbit.core.*;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.apache.jackrabbit.core.fs.FileSystemPathUtil;
import org.apache.jackrabbit.core.fs.FileSystemResource;
import org.apache.jackrabbit.core.fs.local.LocalFileSystem;
import org.apache.jackrabbit.core.state.*;
import org.apache.jackrabbit.core.state.obj.BLOBStore;
import org.apache.jackrabbit.core.state.obj.ObjectPersistenceManager;
import org.apache.log4j.Logger;

import javax.jcr.PropertyType;
import java.io.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * <code>InMemPersistenceManager</code> is a very simple <code>HashMap</code>-based
 * <code>PersistenceManager</code> for Jackrabbit that keeps all data in memory
 * and that is capable of storing and loading its contents using a simple custom
 * serialization format.
 * <p/>
 * It is configured through the following properties:
 * <ul>
 * <li><code>initialCapacity</code>: initial capacity of the hash map used to store the data</li>
 * <li><code>loadFactor</code>: load factor of the hash map used to store the data</li>
 * <li><code>persistent</code>: if <code>true</code> the contents of the hash map
 * is loaded on startup and stored on shutdown;
 * if <code>false</code> nothing is persisted</li>
 * </ul>
 * <b>Please note that this class should only be used for testing purposes.</b>
 */
public class InMemPersistenceManager implements BLOBStore, PersistenceManager {

    private static Logger log = Logger.getLogger(InMemPersistenceManager.class);

    protected boolean initialized;

    protected Map stateStore;
    protected Map refsStore;

    // initial size of buffer used to serialize objects
    protected static final int INITIAL_BUFFER_SIZE = 1024;

    // some constants used in serialization
    protected static final String STATE_FILE_PATH = "/data/.state.bin";
    protected static final String REFS_FILE_PATH = "/data/.refs.bin";
    protected static final byte NODE_ENTRY = 0;
    protected static final byte PROP_ENTRY = 1;

    // file system where BLOB data is stored
    protected FileSystem blobFS;

    /**
     * file system where the content of the hash maps are read from/written to
     * (if <code>persistent==true</code>)
     */
    protected FileSystem wspFS;

    // initial capacity
    protected int initialCapacity = 32768;
    // load factor for the hash map
    protected float loadFactor = 0.75f;
    // should hash map be persisted?
    protected boolean persistent = true;

    /**
     * Creates a new <code>InMemPersistenceManager</code> instance.
     */
    public InMemPersistenceManager() {
        initialized = false;
    }

    public void setInitialCapacity(int initialCapacity) {
        this.initialCapacity = initialCapacity;
    }

    public void setInitialCapacity(String initialCapacity) {
        this.initialCapacity = Integer.valueOf(initialCapacity).intValue();
    }

    public String getInitialCapacity() {
        return Integer.toString(initialCapacity);
    }

    public void setLoadFactor(float loadFactor) {
        this.loadFactor = loadFactor;
    }

    public void setLoadFactor(String loadFactor) {
        this.loadFactor = Float.valueOf(loadFactor).floatValue();
    }

    public String getLoadFactor() {
        return Float.toString(loadFactor);
    }

    public boolean isPersistent() {
        return persistent;
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    public void setPersistent(String persistent) {
        this.persistent = Boolean.valueOf(persistent).booleanValue();
    }

    protected static String buildBlobFilePath(String parentUUID, QName propName, int index) {
        StringBuffer sb = new StringBuffer();
        char[] chars = parentUUID.toCharArray();
        int cnt = 0;
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '-') {
                continue;
            }
            //if (cnt > 0 && cnt % 4 == 0) {
            if (cnt == 2 || cnt == 4) {
                sb.append(FileSystem.SEPARATOR_CHAR);
            }
            sb.append(chars[i]);
            cnt++;
        }
        sb.append(FileSystem.SEPARATOR_CHAR);
        sb.append(FileSystemPathUtil.escapeName(propName.toString()));
        sb.append('.');
        sb.append(index);
        sb.append(".bin");
        return sb.toString();
    }

    /**
     * Reads the content of the hash maps from the file system
     *
     * @throws Exception if an error occurs
     */
    public synchronized void loadContents() throws Exception {
        // read item states
        FileSystemResource fsRes = new FileSystemResource(wspFS, STATE_FILE_PATH);
        if (!fsRes.exists()) {
            return;
        }
        BufferedInputStream bis = new BufferedInputStream(fsRes.getInputStream());
        DataInputStream in = new DataInputStream(bis);

        try {
            int n = in.readInt();   // number of entries
            while (n-- > 0) {
                byte type = in.readByte();  // entry type
                ItemId id;
                if (type == NODE_ENTRY) {
                    // entry type: node
                    String s = in.readUTF();    // id
                    id = NodeId.valueOf(s);
                } else {
                    // entry type: node
                    String s = in.readUTF();    // id
                    id = NodeId.valueOf(s);
                }
                int length = in.readInt();  // data length
                byte[] data = new byte[length];
                in.read(data);  // data
                // store in map
                stateStore.put(id, data);
            }
        } finally {
            in.close();
        }

        // read references
        fsRes = new FileSystemResource(wspFS, REFS_FILE_PATH);
        bis = new BufferedInputStream(fsRes.getInputStream());
        in = new DataInputStream(bis);

        try {
            int n = in.readInt();   // number of entries
            while (n-- > 0) {
                String s = in.readUTF();    // target id
                NodeId id = NodeId.valueOf(s);
                int length = in.readInt();  // data length
                byte[] data = new byte[length];
                in.read(data);  // data
                // store in map
                refsStore.put(id, data);
            }
        } finally {
            in.close();
        }
    }

    /**
     * Writes the content of the hash maps to the file system
     *
     * @throws Exception if an error occurs
     */
    public synchronized void storeContents() throws Exception {
        // write item states
        FileSystemResource fsRes = new FileSystemResource(wspFS, STATE_FILE_PATH);
        fsRes.makeParentDirs();
        BufferedOutputStream bos = new BufferedOutputStream(fsRes.getOutputStream());
        DataOutputStream out = new DataOutputStream(bos);

        try {

            out.writeInt(stateStore.size());    // number of entries
            // entries
            Iterator iterKeys = stateStore.keySet().iterator();
            while (iterKeys.hasNext()) {
                ItemId id = (ItemId) iterKeys.next();
                if (id.denotesNode()) {
                    out.writeByte(NODE_ENTRY);  // entry type
                } else {
                    out.writeByte(PROP_ENTRY);  // entry type
                }
                out.writeUTF(id.toString());    // id
                byte[] data = (byte[]) stateStore.get(id);
                out.writeInt(data.length);  // data length
                out.write(data);    // data
            }
        } finally {
            out.close();
        }

        // write references
        fsRes = new FileSystemResource(wspFS, REFS_FILE_PATH);
        fsRes.makeParentDirs();
        bos = new BufferedOutputStream(fsRes.getOutputStream());
        out = new DataOutputStream(bos);

        try {
            out.writeInt(refsStore.size()); // number of entries
            // entries
            Iterator iterKeys = refsStore.keySet().iterator();
            while (iterKeys.hasNext()) {
                NodeId id = (NodeId) iterKeys.next();
                out.writeUTF(id.toString());    // target id
                byte[] data = (byte[]) refsStore.get(id);
                out.writeInt(data.length);  // data length
                out.write(data);    // data
            }
        } finally {
            out.close();
        }
    }

    //------------------------------------------------------------< BLOBStore >
    /**
     * @see BLOBStore#get
     */
    public FileSystemResource get(String blobId) throws Exception {
        return new FileSystemResource(blobFS, blobId);
    }

    /**
     * @see BLOBStore#put
     */
    public String put(PropertyId id, int index, InputStream in, long size) throws Exception {
        String path = buildBlobFilePath(id.getParentUUID(), id.getName(), index);
        OutputStream out = null;
        FileSystemResource internalBlobFile = new FileSystemResource(blobFS, path);
        internalBlobFile.makeParentDirs();
        try {
            out = new BufferedOutputStream(internalBlobFile.getOutputStream());
            byte[] buffer = new byte[8192];
            int read = 0;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            out.close();
        }
        return path;
    }

    /**
     * @see BLOBStore#remove
     */
    public boolean remove(String blobId) throws Exception {
        FileSystemResource res = new FileSystemResource(blobFS, blobId);
        if (!res.exists()) {
            return false;
        }
        // delete resource and prune empty parent folders
        res.delete(true);
        return true;
    }

    //---------------------------------------------------< PersistenceManager >
    /**
     * @see PersistenceManager#init
     */
    public void init(PMContext context) throws Exception {
        if (initialized) {
            throw new IllegalStateException("already initialized");
        }

        stateStore = new HashMap(initialCapacity, loadFactor);
        refsStore = new HashMap(initialCapacity, loadFactor);

        wspFS = context.getWorkspaceConfig().getFileSystem();
        
        /**
         * store blob's in local file system in a sub directory
         * of the workspace home directory
         */
        LocalFileSystem blobFS = new LocalFileSystem();
        blobFS.setPath(context.getWorkspaceConfig().getHomeDir() + "/blobs");
        blobFS.init();
        this.blobFS = blobFS;

        if (persistent) {
            // deserialize contents of state and refs stores
            loadContents();
        }

        initialized = true;
    }

    /**
     * @see PersistenceManager#close
     */
    public synchronized void close() throws Exception {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        try {
            if (persistent) {
                // serialize contents of state and refs stores
                storeContents();
            } else {
                // clean out blob store
                try {
                    String[] folders = blobFS.listFolders("/");
                    for (int i = 0; i < folders.length; i++) {
                        blobFS.deleteFolder(folders[i]);
                    }
                    String[] files = blobFS.listFiles("/");
                    for (int i = 0; i < files.length; i++) {
                        blobFS.deleteFile(files[i]);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            // close blob store
            blobFS.close();
            blobFS = null;

            stateStore.clear();
            stateStore = null;
            refsStore.clear();
            refsStore = null;
        } finally {
            initialized = false;
        }
    }

    /**
     * @see PersistenceManager#load(PersistentNodeState)
     */
    public synchronized void load(PersistentNodeState state)
            throws NoSuchItemStateException, ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        byte[] data = (byte[]) stateStore.get(state.getId());
        if (data == null) {
            throw new NoSuchItemStateException(state.getId().toString());
        }

        ByteArrayInputStream in = new ByteArrayInputStream(data);
        try {
            ObjectPersistenceManager.deserialize(state, in);
            // there's no need to close a ByteArrayInputStream
            //in.close();
        } catch (Exception e) {
            String msg = "failed to read node state: " + state.getId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * @see PersistenceManager#load(PersistentPropertyState)
     */
    public synchronized void load(PersistentPropertyState state)
            throws NoSuchItemStateException, ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        byte[] data = (byte[]) stateStore.get(state.getId());
        if (data == null) {
            throw new NoSuchItemStateException(state.getId().toString());
        }

        ByteArrayInputStream in = new ByteArrayInputStream(data);
        try {
            ObjectPersistenceManager.deserialize(state, in, this);
            // there's no need to close a ByteArrayInputStream
            //in.close();
        } catch (Exception e) {
            String msg = "failed to read property state: " + state.getId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * @see PersistenceManager#store
     */
    public synchronized void store(PersistentNodeState state) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
            // serialize node state
            ObjectPersistenceManager.serialize(state, out);

            // store in serialized format in map for better memory efficiency
            stateStore.put(state.getId(), out.toByteArray());
            // there's no need to close a ByteArrayOutputStream
            //out.close();
        } catch (Exception e) {
            String msg = "failed to write node state: " + state.getId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * @see PersistenceManager#store
     */
    public synchronized void store(PersistentPropertyState state) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
            // serialize property state
            ObjectPersistenceManager.serialize(state, out, this);

            // store in serialized format in map for better memory efficiency
            stateStore.put(state.getId(), out.toByteArray());
            // there's no need to close a ByteArrayOutputStream
            //out.close();
        } catch (Exception e) {
            String msg = "failed to store property state: " + state.getId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * @see PersistenceManager#destroy
     */
    public synchronized void destroy(PersistentNodeState state) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // remove node state
        stateStore.remove(state.getId());
    }

    /**
     * @see PersistenceManager#destroy
     */
    public synchronized void destroy(PersistentPropertyState state) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // delete binary values (stored as files)
        InternalValue[] values = state.getValues();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                InternalValue val = values[i];
                if (val != null) {
                    if (val.getType() == PropertyType.BINARY) {
                        BLOBFileValue blobVal = (BLOBFileValue) val.internalValue();
                        // delete blob file and prune empty parent folders
                        blobVal.delete(true);
                    }
                }
            }
        }

        // remove property state
        stateStore.remove(state.getId());
    }

    /**
     * @see PersistenceManager#load(NodeReferences)
     */
    public synchronized void load(NodeReferences refs)
            throws NoSuchItemStateException, ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        byte[] data = (byte[]) refsStore.get(refs.getTargetId());
        if (data == null) {
            throw new NoSuchItemStateException(refs.getTargetId().toString());
        }

        ByteArrayInputStream in = new ByteArrayInputStream(data);
        try {
            ObjectPersistenceManager.deserialize(refs, in);
            // there's no need to close a ByteArrayInputStream
            //in.close();
        } catch (Exception e) {
            String msg = "failed to load references: " + refs.getTargetId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * @see PersistenceManager#store(NodeReferences)
     */
    public synchronized void store(NodeReferences refs) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
            // serialize references
            ObjectPersistenceManager.serialize(refs, out);

            // store in serialized format in map for better memory efficiency
            stateStore.put(refs.getTargetId(), out.toByteArray());
            // there's no need to close a ByteArrayOutputStream
            //out.close();
        } catch (Exception e) {
            String msg = "failed to store references: " + refs.getTargetId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * @see PersistenceManager#destroy(NodeReferences)
     */
    public synchronized void destroy(NodeReferences refs) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // remove node references
        stateStore.remove(refs.getTargetId());
    }

    /**
     * @see PersistenceManager#exists(ItemId id)
     */
    public boolean exists(ItemId id) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        return stateStore.containsKey(id);
    }

    /**
     * @see PersistenceManager#referencesExist(NodeId targetId)
     */
    public boolean referencesExist(NodeId targetId) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        return refsStore.containsKey(targetId);
    }
}
