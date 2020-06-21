/*
 *
 * MIT License
 *
 * Copyright (c) 2020 cloud.upcwangying.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package com.upcwangying.cloud.samples.oss.server.service.impl;

import com.google.common.base.Strings;
import com.upcwangying.cloud.samples.oss.common.ObjectListResult;
import com.upcwangying.cloud.samples.oss.common.ObjectMetaData;
import com.upcwangying.cloud.samples.oss.common.OosObject;
import com.upcwangying.cloud.samples.oss.common.OosObjectSummary;
import com.upcwangying.cloud.samples.oss.common.utils.JsonUtil;
import com.upcwangying.cloud.samples.oss.server.service.IHdfsService;
import com.upcwangying.cloud.samples.oss.server.service.IOosStoreService;
import com.upcwangying.cloud.samples.oss.server.utils.OosUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.locks.Reaper;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.ColumnPrefixFilter;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FilterList.Operator;
import org.apache.hadoop.hbase.filter.PageFilter;
import org.apache.hadoop.hbase.filter.QualifierFilter;
import org.apache.hadoop.hbase.io.ByteBufferInputStream;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * @author WANGY
 */
public class OosStoreServiceImpl implements IOosStoreService {
    private static Logger logger = Logger.getLogger(OosStoreServiceImpl.class);
    private Connection connection = null;
    private IHdfsService fileStore;
    private String zkUrls;
    private CuratorFramework zkClient;
    private Reaper reaper;

    public OosStoreServiceImpl(Connection connection, IHdfsService fileStore, String zkurls)
            throws IOException {
        this.connection = connection;
        this.fileStore = fileStore;
        this.zkUrls = zkurls;
        zkClient = CuratorFrameworkFactory.newClient(zkUrls, new ExponentialBackoffRetry(20, 5));
        zkClient.start();
        reaper = new Reaper(zkClient, 2000);
        try {
            reaper.start();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void createSeqTable() throws IOException {
        Admin admin = connection.getAdmin();
        TableName tableName = TableName.valueOf(OosUtil.BUCKET_DIR_SEQ_TABLE);
        if (admin.tableExists(tableName)) {
            return;
        }
        HBaseService.createTable(connection, OosUtil.BUCKET_DIR_SEQ_TABLE,
                new String[]{OosUtil.BUCKET_DIR_SEQ_CF});
    }

    @Override
    public void put(String bucket, String key, ByteBuffer input, long length, String mediaType,
                    Map<String, String> properties) throws Exception {
        InterProcessMutex lock = null;
        String lockPath = null;
        try {
            if (key.endsWith("/")) {
                //put dir object
                putDir(bucket, key);
                return;
            }
            //put dir
            String dir = key.substring(0, key.lastIndexOf("/") + 1);
            String hash = null;
            while (hash == null) {
                if (!dirExist(bucket, dir)) {
                    hash = putDir(bucket, dir);
                } else {
                    hash = this.getDirSeqId(bucket, dir);
                }
            }
            String lockey = key.replaceAll("/", "_");
            lockPath = "/mos/" + bucket + "/" + lockey;
            lock = new InterProcessMutex(this.zkClient, lockPath);
            lock.acquire();
            //put
            String fileKey = hash + "_" + key.substring(key.lastIndexOf("/") + 1);
            Put contentPut = new Put(fileKey.getBytes());
            if (!Strings.isNullOrEmpty(mediaType)) {
                contentPut.addColumn(OosUtil.OBJ_META_CF_BYTES,
                        OosUtil.OBJ_MEDIATYPE_QUALIFIER, mediaType.getBytes());
            }
            if (properties != null) {
                String props = JsonUtil.toJson(properties);
                contentPut.addColumn(OosUtil.OBJ_META_CF_BYTES,
                        OosUtil.OBJ_PROPS_QUALIFIER, props.getBytes());
            }
            contentPut.addColumn(OosUtil.OBJ_META_CF_BYTES,
                    OosUtil.OBJ_LEN_QUALIFIER, Bytes.toBytes((long) length));

            if (length <= OosUtil.FILE_STORE_THRESHOLD) {
                ByteBuffer qualifierBuffer = ByteBuffer.wrap(OosUtil.OBJ_CONT_QUALIFIER);
                contentPut.addColumn(OosUtil.OBJ_CONT_CF_BYTES,
                        qualifierBuffer, System.currentTimeMillis(), input);
                qualifierBuffer.clear();
            }
            HBaseService.putRow(connection, OosUtil.getObjTableName(bucket), contentPut);
            if (length > OosUtil.FILE_STORE_THRESHOLD) {
                String fileDir = OosUtil.FILE_STORE_ROOT + "/" + bucket + "/" + hash;
                String name = key.substring(key.lastIndexOf("/") + 1);
                InputStream inputStream = new ByteBufferInputStream(input);
                this.fileStore.saveFile(fileDir, name, inputStream, length, getBucketReplication(bucket));
            }
        } finally {
            if (lock != null) {
                reaper.addPath(lockPath, Reaper.Mode.REAP_UNTIL_DELETE);
                lock.release();
            }
        }
    }

    @Override
    public OosObjectSummary getSummary(String bucket, String key) throws IOException {
        if (key.endsWith("/")) {
            Result result = HBaseService
                    .getRow(connection, OosUtil.getDirTableName(bucket), key);
            if (!result.isEmpty()) {
                return this.dirObjectToSummary(result, bucket, key);
            } else {
                return null;
            }
        }
        String dir = key.substring(0, key.lastIndexOf("/") + 1);
        String seq = this.getDirSeqId(bucket, dir);
        if (seq == null) {
            return null;
        }
        String objKey = seq + "_" + key.substring(key.lastIndexOf("/") + 1);
        Result result = HBaseService
                .getRow(connection, OosUtil.getObjTableName(bucket), objKey);
        if (result.isEmpty()) {
            return null;
        }
        return this.resultToObjectSummary(result, bucket, dir);
    }

    @Override
    public List<OosObjectSummary> list(String bucket, String startKey, String endKey)
            throws IOException {

        String dir1 = startKey.substring(0, startKey.lastIndexOf("/") + 1).trim();
        if (dir1.length() == 0) {
            dir1 = "/";
        }
        String dir2 = endKey.substring(0, startKey.lastIndexOf("/") + 1).trim();
        if (dir2.length() == 0) {
            dir2 = "/";
        }
        String name1 = startKey.substring(startKey.lastIndexOf("/") + 1);
        String name2 = endKey.substring(startKey.lastIndexOf("/") + 1);
        String seqId = this.getDirSeqId(bucket, dir1);
        //查询dir1中大于name1的全部文件
        List<OosObjectSummary> keys = new ArrayList<>();
        if (seqId != null && name1.length() > 0) {
            byte[] max = Bytes.createMaxByteArray(100);
            byte[] tail = Bytes.add(Bytes.toBytes(seqId), max);
            if (dir1.equals(dir2)) {
                tail = (seqId + "_" + name2).getBytes();
            }
            byte[] start = (seqId + "_" + name1).getBytes();
            ResultScanner scanner1 = HBaseService
                    .scanner(connection, OosUtil.getObjTableName(bucket), start, tail);
            Result result = null;
            while ((result = scanner1.next()) != null) {
                OosObjectSummary summary = this.resultToObjectSummary(result, bucket, dir1);
                keys.add(summary);
            }
            if (scanner1 != null) {
                scanner1.close();
            }
        }
        //startkey~endkey之间的全部目录
        ResultScanner scanner2 = HBaseService
                .scanner(connection, OosUtil.getDirTableName(bucket), startKey, endKey);
        Result result = null;
        while ((result = scanner2.next()) != null) {
            String seqId2 = Bytes.toString(result.getValue(OosUtil.DIR_META_CF_BYTES,
                    OosUtil.DIR_SEQID_QUALIFIER));
            if (seqId2 == null) {
                continue;
            }
            String dir = Bytes.toString(result.getRow());
            keys.add(dirObjectToSummary(result, bucket, dir));
            getDirAllFiles(bucket, dir, seqId2, keys, endKey);
        }
        if (scanner2 != null) {
            scanner2.close();
        }
        Collections.sort(keys);
        return keys;
    }

    @Override
    public ObjectListResult listDir(String bucket, String dir, String start, int maxCount)
            throws IOException {
        if (start == null) {
            start = "";
        }
        Get get = new Get(Bytes.toBytes(dir));
        get.addFamily(OosUtil.DIR_SUBDIR_CF_BYTES);
        if (start.length() > 0) {
            get.setFilter(new QualifierFilter(CompareOp.GREATER_OR_EQUAL,
                    new BinaryComparator(Bytes.toBytes(start))));
        }
        int maxCount1 = maxCount + 2;
        Result dirResult = HBaseService
                .getRow(connection, OosUtil.getDirTableName(bucket), get);
        List<OosObjectSummary> subDirs = null;
        if (!dirResult.isEmpty()) {
            subDirs = new ArrayList<>();
            for (Cell cell : dirResult.rawCells()) {
                OosObjectSummary summary = new OosObjectSummary();
                byte[] qualifierBytes = new byte[cell.getQualifierLength()];
                CellUtil.copyQualifierTo(cell, qualifierBytes, 0);
                String name = Bytes.toString(qualifierBytes);
                summary.setKey(dir + name + "/");
                summary.setName(name);
                summary.setLastModifyTime(cell.getTimestamp());
                summary.setMediaType("");
                summary.setBucket(bucket);
                summary.setLength(0);
                subDirs.add(summary);
                if (subDirs.size() >= maxCount1) {
                    break;
                }
            }
        }

        String dirSeq = this.getDirSeqId(bucket, dir);
        byte[] objStart = Bytes.toBytes(dirSeq + "_" + start);
        Scan objScan = new Scan();
        objScan.setRowPrefixFilter(Bytes.toBytes(dirSeq + "_"));
        objScan.setFilter(new PageFilter(maxCount + 1));
        objScan.setStartRow(objStart);
        objScan.setMaxResultsPerColumnFamily(maxCount1);
        objScan.addFamily(OosUtil.OBJ_META_CF_BYTES);
        logger.info("scan start: " + Bytes.toString(objStart) + " - ");
        ResultScanner objScanner = HBaseService
                .scanner(connection, OosUtil.getObjTableName(bucket), objScan);
        List<OosObjectSummary> objectSummaryList = new ArrayList<>();
        Result result = null;
        while (objectSummaryList.size() < maxCount1 && (result = objScanner.next()) != null) {
            OosObjectSummary summary = this.resultToObjectSummary(result, bucket, dir);
            objectSummaryList.add(summary);
        }
        if (objScanner != null) {
            objScanner.close();
        }
        logger.info("scan complete: " + Bytes.toString(objStart) + " - ");
        if (subDirs != null && subDirs.size() > 0) {
            objectSummaryList.addAll(subDirs);
        }
        Collections.sort(objectSummaryList);
        ObjectListResult listResult = new ObjectListResult();
        OosObjectSummary nextMarkerObj =
                objectSummaryList.size() > maxCount ? objectSummaryList.get(objectSummaryList.size() - 1)
                        : null;
        if (nextMarkerObj != null) {
            listResult.setNextMarker(nextMarkerObj.getKey());
        }
        if (objectSummaryList.size() > maxCount) {
            objectSummaryList = objectSummaryList.subList(0, maxCount);
        }
        listResult.setMaxKeyNumber(maxCount);
        if (objectSummaryList.size() > 0) {
            listResult.setMinKey(objectSummaryList.get(0).getKey());
            listResult.setMaxKey(objectSummaryList.get(objectSummaryList.size() - 1).getKey());
        }
        listResult.setObjectCount(objectSummaryList.size());
        listResult.setObjectList(objectSummaryList);
        listResult.setBucket(bucket);

        return listResult;
    }

    @Override
    public ObjectListResult listByPrefix(String bucket, String dir, String keyPrefix, String start,
                                         int maxCount) throws IOException {
        if (start == null) {
            start = "";
        }
        FilterList filterList = new FilterList(Operator.MUST_PASS_ALL);
        filterList.addFilter(new ColumnPrefixFilter(keyPrefix.getBytes()));
        if (start.length() > 0) {
            filterList.addFilter(new QualifierFilter(CompareOp.GREATER_OR_EQUAL,
                    new BinaryComparator(Bytes.toBytes(start))));
        }
        int maxCount1 = maxCount + 2;
        Result dirResult = HBaseService
                .getRow(connection, OosUtil.getDirTableName(bucket), dir, filterList);
        List<OosObjectSummary> subDirs = null;
        if (!dirResult.isEmpty()) {
            subDirs = new ArrayList<>();
            for (Cell cell : dirResult.rawCells()) {
                OosObjectSummary summary = new OosObjectSummary();
                byte[] qualifierBytes = new byte[cell.getQualifierLength()];
                CellUtil.copyQualifierTo(cell, qualifierBytes, 0);
                String name = Bytes.toString(qualifierBytes);
                summary.setKey(dir + name + "/");
                summary.setName(name);
                summary.setLastModifyTime(cell.getTimestamp());
                summary.setMediaType("");
                summary.setBucket(bucket);
                summary.setLength(0);
                subDirs.add(summary);
                if (subDirs.size() >= maxCount1) {
                    break;
                }
            }
        }

        String dirSeq = this.getDirSeqId(bucket, dir);
        byte[] objStart = Bytes.toBytes(dirSeq + "_" + start);
        Scan objScan = new Scan();
        objScan.setRowPrefixFilter(Bytes.toBytes(dirSeq + "_" + keyPrefix));
        objScan.setFilter(new PageFilter(maxCount + 1));
        objScan.setStartRow(objStart);
        objScan.setMaxResultsPerColumnFamily(maxCount1);
        objScan.addFamily(OosUtil.OBJ_META_CF_BYTES);
        logger.info("scan start: " + Bytes.toString(objStart) + " - ");
        ResultScanner objScanner = HBaseService
                .scanner(connection, OosUtil.getObjTableName(bucket), objScan);
        List<OosObjectSummary> objectSummaryList = new ArrayList<>();
        Result result = null;
        while (objectSummaryList.size() < maxCount1 && (result = objScanner.next()) != null) {
            OosObjectSummary summary = this.resultToObjectSummary(result, bucket, dir);
            objectSummaryList.add(summary);
        }
        if (objScanner != null) {
            objScanner.close();
        }
        logger.info("scan complete: " + Bytes.toString(objStart) + " - ");
        if (subDirs != null && subDirs.size() > 0) {
            objectSummaryList.addAll(subDirs);
        }
        Collections.sort(objectSummaryList);
        ObjectListResult listResult = new ObjectListResult();
        OosObjectSummary nextMarkerObj =
                objectSummaryList.size() > maxCount ? objectSummaryList.get(objectSummaryList.size() - 1)
                        : null;
        if (nextMarkerObj != null) {
            listResult.setNextMarker(nextMarkerObj.getKey());
        }
        if (objectSummaryList.size() > maxCount) {
            objectSummaryList = objectSummaryList.subList(0, maxCount);
        }
        listResult.setMaxKeyNumber(maxCount);
        if (objectSummaryList.size() > 0) {
            listResult.setMinKey(objectSummaryList.get(0).getKey());
            listResult.setMaxKey(objectSummaryList.get(objectSummaryList.size() - 1).getKey());
        }
        listResult.setObjectCount(objectSummaryList.size());
        listResult.setObjectList(objectSummaryList);
        listResult.setBucket(bucket);

        return listResult;
    }

    @Override
    public OosObject getObject(String bucket, String key) throws IOException {
        if (key.endsWith("/")) {
            Result result = HBaseService
                    .getRow(connection, OosUtil.getDirTableName(bucket), key);
            if (result.isEmpty()) {
                return null;
            }
            ObjectMetaData metaData = new ObjectMetaData();
            metaData.setBucket(bucket);
            metaData.setKey(key);
            metaData.setLastModifyTime(result.rawCells()[0].getTimestamp());
            metaData.setLength(0);
            OosObject object = new OosObject();
            object.setMetaData(metaData);
            return object;
        }
        String dir = key.substring(0, key.lastIndexOf("/") + 1);
        String name = key.substring(key.lastIndexOf("/") + 1);
        String seq = this.getDirSeqId(bucket, dir);
        String objKey = seq + "_" + name;
        Result result = HBaseService
                .getRow(connection, OosUtil.getObjTableName(bucket), objKey);
        if (result.isEmpty()) {
            return null;
        }
        OosObject object = new OosObject();
        if (result.containsNonEmptyColumn(OosUtil.OBJ_CONT_CF_BYTES,
                OosUtil.OBJ_CONT_QUALIFIER)) {
            ByteArrayInputStream bas = new ByteArrayInputStream(
                    result
                            .getValue(OosUtil.OBJ_CONT_CF_BYTES,
                                    OosUtil.OBJ_CONT_QUALIFIER));
            object.setContent(bas);
        } else {
            String fileDir = OosUtil.FILE_STORE_ROOT + "/" + bucket + "/" + seq;
            InputStream inputStream = this.fileStore.openFile(fileDir, name);
            object.setContent(inputStream);
        }
        long len = Bytes.toLong(result.getValue(OosUtil.OBJ_META_CF_BYTES,
                OosUtil.OBJ_LEN_QUALIFIER));
        ObjectMetaData metaData = new ObjectMetaData();
        metaData.setBucket(bucket);
        metaData.setKey(key);
        metaData.setLastModifyTime(result.rawCells()[0].getTimestamp());
        metaData.setLength(len);
        metaData.setMediaType(Bytes.toString(result.getValue(OosUtil.OBJ_META_CF_BYTES,
                OosUtil.OBJ_MEDIATYPE_QUALIFIER)));
        byte[] b = result
                .getValue(OosUtil.OBJ_META_CF_BYTES, OosUtil.OBJ_PROPS_QUALIFIER);
        if (b != null) {
            metaData.setAttrs(JsonUtil.fromJson(Map.class, Bytes.toString(b)));
        }
        object.setMetaData(metaData);
        return object;
    }

    @Override
    public void deleteObject(String bucket, String key) throws Exception {
        if (key.endsWith("/")) {
            //check sub dir and current dir files.
            if (!isDirEmpty(bucket, key)) {
                throw new RuntimeException("dir is not empty");
            }
            InterProcessMutex lock = null;
            String lockPath = null;
            try {
                String lockey = key.replaceAll("/", "_");
                lockPath = "/mos/" + bucket + "/" + lockey;
                lock = new InterProcessMutex(this.zkClient, lockPath);
                lock.acquire();
                if (!isDirEmpty(bucket, key)) {
                    throw new RuntimeException("dir is not empty");
                }
                String dir1 = key.substring(0, key.lastIndexOf("/"));
                String name = dir1.substring(dir1.lastIndexOf("/") + 1);
                if (name.length() > 0) {
                    String parent = key.substring(0, key.lastIndexOf(name));
                    HBaseService
                            .deleteQualifier(connection, OosUtil.getDirTableName(bucket), parent,
                                    OosUtil.DIR_SUBDIR_CF, name);
                }
                HBaseService.delete(connection, OosUtil.getDirTableName(bucket), key);
                return;
            } finally {
                if (lock != null) {
                    reaper.addPath(lockPath, Reaper.Mode.REAP_UNTIL_DELETE);
                    lock.release();
                }
            }
        }
        String dir = key.substring(0, key.lastIndexOf("/") + 1);
        String name = key.substring(key.lastIndexOf("/") + 1);
        String seqId = this.getDirSeqId(bucket, dir);
        String objKey = seqId + "_" + name;
        Result result = HBaseService
                .getRow(connection, OosUtil.getObjTableName(bucket), objKey,
                        OosUtil.OBJ_META_CF_BYTES, OosUtil.OBJ_LEN_QUALIFIER);
        if (result.isEmpty()) {
            return;
        }
        long len = Bytes.toLong(result.getValue(OosUtil.OBJ_META_CF_BYTES,
                OosUtil.OBJ_LEN_QUALIFIER));
        if (len > OosUtil.FILE_STORE_THRESHOLD) {
            String fileDir = OosUtil.FILE_STORE_ROOT + "/" + bucket + "/" + seqId;
            this.fileStore.deleteFile(fileDir, name);
        }
        HBaseService.delete(connection, OosUtil.getObjTableName(bucket), objKey);
    }

    private boolean isDirEmpty(String bucket, String dir) throws IOException {
        return listDir(bucket, dir, null, 2).getObjectList().size() == 0;
    }

    @Override
    public void deleteBucketStore(String bucket) throws IOException {
        HBaseService.deleteTable(connection, OosUtil.getDirTableName(bucket));
        HBaseService.deleteTable(connection, OosUtil.getObjTableName(bucket));

        HBaseService.delete(connection, OosUtil.BUCKET_DIR_SEQ_TABLE, bucket);
        this.fileStore.deleteDir(OosUtil.FILE_STORE_ROOT + "/" + bucket);
    }

    @Override
    public void createBucketStore(String bucket) throws IOException {
        HBaseService.createTable(connection, OosUtil.getDirTableName(bucket),
                OosUtil.getDirColumnFamily());
        HBaseService.createTable(connection, OosUtil.getObjTableName(bucket),
                OosUtil.getObjColumnFamily(), OosUtil.OBJ_REGIONS);

        Put put = new Put(Bytes.toBytes(bucket));
        put.addColumn(OosUtil.BUCKET_DIR_SEQ_CF_BYTES,
                OosUtil.BUCKET_DIR_SEQ_QUALIFIER, Bytes.toBytes(0L));
        HBaseService.putRow(connection, OosUtil.BUCKET_DIR_SEQ_TABLE, put);
        this.fileStore.mikDir(OosUtil.FILE_STORE_ROOT + "/" + bucket);
    }

    private String getDirSeqId(String bucket, String dir) throws IOException {
        Result result = HBaseService.getRow(connection, OosUtil.getDirTableName(bucket), dir);
        if (result.isEmpty()) {
            return null;
        }
        String dirSeqId = Bytes.toString(result.getValue(OosUtil.DIR_META_CF_BYTES,
                OosUtil.DIR_SEQID_QUALIFIER));
        return dirSeqId;
    }

    private void getDirAllFiles(String bucket, String dir, String seqId, List<OosObjectSummary> keys,
                                String endKey) throws IOException {

        byte[] max = Bytes.createMaxByteArray(100);
        byte[] tail = Bytes.add(Bytes.toBytes(seqId), max);
        if (endKey.startsWith(dir)) {
            String endKeyLeft = endKey.replace(dir, "");
            String fileNameMax = endKeyLeft;
            if (endKeyLeft.indexOf("/") > 0) {
                fileNameMax = endKeyLeft.substring(0, endKeyLeft.indexOf("/"));
            }
            tail = Bytes.toBytes(seqId + "_" + fileNameMax);
        }

        Scan scan = new Scan(Bytes.toBytes(seqId), tail);
        scan.setFilter(OosUtil.OBJ_META_SCAN_FILTER);
        ResultScanner scanner = HBaseService
                .scanner(connection, OosUtil.getObjTableName(bucket), scan);
        Result result = null;
        while ((result = scanner.next()) != null) {
            OosObjectSummary summary = this.resultToObjectSummary(result, bucket, dir);
            keys.add(summary);
        }
        if (scanner != null) {
            scanner.close();
        }
    }

    private OosObjectSummary resultToObjectSummary(Result result, String bucket, String dir)
            throws IOException {
        OosObjectSummary summary = new OosObjectSummary();
        long timestamp = result.rawCells()[0].getTimestamp();
        summary.setLastModifyTime(timestamp);
        String id = new String(result.getRow());
        summary.setId(id);
        String name = id.split("_", 2)[1];
        String key = dir + name;
        summary.setKey(key);
        summary.setName(name);
        summary.setBucket(bucket);
        String s = Bytes.toString(result.getValue(OosUtil.OBJ_META_CF_BYTES,
                OosUtil.OBJ_PROPS_QUALIFIER));
        if (s != null) {
            summary.setAttrs(JsonUtil.fromJson(Map.class, s));
        }
        summary.setLength(Bytes.toLong(result.getValue(OosUtil.OBJ_META_CF_BYTES,
                OosUtil.OBJ_LEN_QUALIFIER)));
        summary
                .setMediaType(Bytes.toString(result.getValue(OosUtil.OBJ_META_CF_BYTES,
                        OosUtil.OBJ_MEDIATYPE_QUALIFIER)));

        return summary;
    }

    private OosObjectSummary dirObjectToSummary(Result result, String bucket, String dir) {
        OosObjectSummary summary = new OosObjectSummary();
        String id = Bytes.toString(result.getRow());
        summary.setId(id);
        summary.setAttrs(new HashMap<>(0));
        if (dir.length() > 1) {
            summary.setName(dir.substring(dir.lastIndexOf("/") + 1));
        } else {
            summary.setName("");
        }
        summary.setBucket(bucket);
        summary.setKey(dir);
        summary.setLastModifyTime(result.rawCells()[0].getTimestamp());
        summary.setLength(0);
        summary.setMediaType("");
        return summary;
    }


    private short getBucketReplication(String bucket) {
        return 2;
    }

    private String putDir(String bucket, String dir) throws Exception {
        if (dirExist(bucket, dir)) {
            return null;
        }
        InterProcessMutex lock = null;
        String lockPath = null;
        try {
            String lockey = dir.replaceAll("/", "_");
            lockPath = "/mos/" + bucket + "/" + lockey;
            lock = new InterProcessMutex(this.zkClient, lockPath);
            lock.acquire();
            String dir1 = dir.substring(0, dir.lastIndexOf("/"));
            String name = dir1.substring(dir1.lastIndexOf("/") + 1);
            if (name.length() > 0) {
                String parent = dir.substring(0, dir1.lastIndexOf("/") + 1);
                if (!this.dirExist(bucket, parent)) {
                    this.putDir(bucket, parent);
                }
                Put put = new Put(Bytes.toBytes(parent));
                put.addColumn(OosUtil.DIR_SUBDIR_CF_BYTES, Bytes.toBytes(name),
                        Bytes.toBytes('1'));
                HBaseService.putRow(connection, OosUtil.getDirTableName(bucket), put);
            }
            String seqId = this.getDirSeqId(bucket, dir);
            String hash = seqId == null ? makeDirSeqId(bucket) : seqId;
            Put hashPut = new Put(dir.getBytes());
            hashPut.addColumn(OosUtil.DIR_META_CF_BYTES,
                    OosUtil.DIR_SEQID_QUALIFIER, Bytes.toBytes(hash));
            HBaseService.putRow(connection, OosUtil.getDirTableName(bucket), hashPut);
            return hash;
        } finally {
            if (lock != null) {
                reaper.addPath(lockPath, Reaper.Mode.REAP_UNTIL_DELETE);
                lock.release();
            }
        }
    }

    private String makeDirSeqId(String bucket) throws IOException {
        long v = HBaseService
                .incrementColumnValue(connection, OosUtil.BUCKET_DIR_SEQ_TABLE, bucket,
                        OosUtil.BUCKET_DIR_SEQ_CF_BYTES, OosUtil.BUCKET_DIR_SEQ_QUALIFIER,
                        1);
        return String.format("%da%d", v % 64, v);
    }

    private boolean dirExist(String bucket, String dir) throws IOException {
        return HBaseService.existsRow(connection, OosUtil.getDirTableName(bucket), dir);
    }
}
