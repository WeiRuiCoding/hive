/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore.utils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.common.TableName;
import org.apache.hadoop.hive.metastore.ColumnType;
import org.apache.hadoop.hive.metastore.HiveMetaStore;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.Decimal;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Order;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.SkewedInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.metastore.columnstats.aggr.ColumnStatsAggregator;
import org.apache.hadoop.hive.metastore.columnstats.aggr.ColumnStatsAggregatorFactory;
import org.apache.hadoop.hive.metastore.columnstats.merge.ColumnStatsMerger;
import org.apache.hadoop.hive.metastore.columnstats.merge.ColumnStatsMergerFactory;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.partition.spec.PartitionSpecProxy;
import org.apache.hadoop.hive.metastore.security.HadoopThriftAuthBridge;
import org.apache.hadoop.security.authorize.DefaultImpersonationProvider;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.hadoop.util.MachineList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

/**
 * Utility methods used by Hive standalone metastore server.
 */
public class MetaStoreServerUtils {
  private static final Charset ENCODING = StandardCharsets.UTF_8;
  private static final Logger LOG = LoggerFactory.getLogger(MetaStoreServerUtils.class);

  /**
   * Helper function to transform Nulls to empty strings.
   */
  private static final com.google.common.base.Function<String,String> transFormNullsToEmptyString
      = new com.google.common.base.Function<String, String>() {
    @Override
    public String apply(@Nullable String string) {
      return org.apache.commons.lang.StringUtils.defaultString(string);
    }
  };

  /**
   * We have a need to sanity-check the map before conversion from persisted objects to
   * metadata thrift objects because null values in maps will cause a NPE if we send
   * across thrift. Pruning is appropriate for most cases except for databases such as
   * Oracle where Empty strings are stored as nulls, in which case we need to handle that.
   * See HIVE-8485 for motivations for this.
   */
  public static Map<String,String> trimMapNulls(
      Map<String,String> dnMap, boolean retrieveMapNullsAsEmptyStrings){
    if (dnMap == null){
      return null;
    }
    // Must be deterministic order map - see HIVE-8707
    //   => we use Maps.newLinkedHashMap instead of Maps.newHashMap
    if (retrieveMapNullsAsEmptyStrings) {
      // convert any nulls present in map values to empty strings - this is done in the case
      // of backing dbs like oracle which persist empty strings as nulls.
      return Maps.newLinkedHashMap(Maps.transformValues(dnMap, transFormNullsToEmptyString));
    } else {
      // prune any nulls present in map values - this is the typical case.
      return Maps.newLinkedHashMap(Maps.filterValues(dnMap, Predicates.notNull()));
    }
  }

  // Given a list of partStats, this function will give you an aggr stats
  public static List<ColumnStatisticsObj> aggrPartitionStats(List<ColumnStatistics> partStats,
                                                             String catName, String dbName, String tableName, List<String> partNames, List<String> colNames,
                                                             boolean areAllPartsFound, boolean useDensityFunctionForNDVEstimation, double ndvTuner)
      throws MetaException {
    Map<ColumnStatsAggregator, List<ColStatsObjWithSourceInfo>> colStatsMap =
        new HashMap<ColumnStatsAggregator, List<ColStatsObjWithSourceInfo>>();
    // Group stats by colName for each partition
    Map<String, ColumnStatsAggregator> aliasToAggregator =
        new HashMap<String, ColumnStatsAggregator>();
    for (ColumnStatistics css : partStats) {
      List<ColumnStatisticsObj> objs = css.getStatsObj();
      for (ColumnStatisticsObj obj : objs) {
        String partName = css.getStatsDesc().getPartName();
        if (aliasToAggregator.get(obj.getColName()) == null) {
          aliasToAggregator.put(obj.getColName(),
              ColumnStatsAggregatorFactory.getColumnStatsAggregator(
                  obj.getStatsData().getSetField(), useDensityFunctionForNDVEstimation, ndvTuner));
          colStatsMap.put(aliasToAggregator.get(obj.getColName()),
              new ArrayList<ColStatsObjWithSourceInfo>());
        }
        colStatsMap.get(aliasToAggregator.get(obj.getColName()))
            .add(new ColStatsObjWithSourceInfo(obj, catName, dbName, tableName, partName));
      }
    }
    if (colStatsMap.size() < 1) {
      LOG.debug("No stats data found for: tblName= {}, partNames= {}, colNames= {}",
          TableName.getQualified(catName, dbName, tableName), partNames, colNames);
      return new ArrayList<ColumnStatisticsObj>();
    }
    return aggrPartitionStats(colStatsMap, partNames, areAllPartsFound,
        useDensityFunctionForNDVEstimation, ndvTuner);
  }

  public static List<ColumnStatisticsObj> aggrPartitionStats(
      Map<ColumnStatsAggregator, List<ColStatsObjWithSourceInfo>> colStatsMap,
      final List<String> partNames, final boolean areAllPartsFound,
      final boolean useDensityFunctionForNDVEstimation, final double ndvTuner)
      throws MetaException {
    List<ColumnStatisticsObj> aggrColStatObjs = new ArrayList<ColumnStatisticsObj>();
    int numProcessors = Runtime.getRuntime().availableProcessors();
    final ExecutorService pool =
        Executors.newFixedThreadPool(Math.min(colStatsMap.size(), numProcessors),
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat("aggr-col-stats-%d").build());
    final List<Future<ColumnStatisticsObj>> futures = Lists.newLinkedList();
    LOG.debug("Aggregating column stats. Threads used: {}",
        Math.min(colStatsMap.size(), numProcessors));
    long start = System.currentTimeMillis();
    for (final Map.Entry<ColumnStatsAggregator, List<ColStatsObjWithSourceInfo>> entry : colStatsMap
        .entrySet()) {
      futures.add(pool.submit(new Callable<ColumnStatisticsObj>() {
        @Override
        public ColumnStatisticsObj call() throws MetaException {
          List<ColStatsObjWithSourceInfo> colStatWithSourceInfo = entry.getValue();
          ColumnStatsAggregator aggregator = entry.getKey();
          try {
            ColumnStatisticsObj statsObj =
                aggregator.aggregate(colStatWithSourceInfo, partNames, areAllPartsFound);
            return statsObj;
          } catch (MetaException e) {
            LOG.debug(e.getMessage());
            throw e;
          }
        }
      }));
    }
    pool.shutdown();
    if (!futures.isEmpty()) {
      for (Future<ColumnStatisticsObj> future : futures) {
        try {
          if (future.get() != null) {
            aggrColStatObjs.add(future.get());
          }
        } catch (InterruptedException | ExecutionException e) {
          LOG.debug(e.getMessage());
          pool.shutdownNow();
          throw new MetaException(e.toString());
        }

      }
    }
    LOG.debug("Time for aggr col stats in seconds: {} Threads used: {}",
        ((System.currentTimeMillis() - (double) start)) / 1000,
        Math.min(colStatsMap.size(), numProcessors));
    return aggrColStatObjs;
  }

  public static double decimalToDouble(Decimal decimal) {
    return new BigDecimal(new BigInteger(decimal.getUnscaled()), decimal.getScale()).doubleValue();
  }

  public static void validatePartitionNameCharacters(List<String> partVals,
                                                     Pattern partitionValidationPattern) throws MetaException {

    String invalidPartitionVal = getPartitionValWithInvalidCharacter(partVals, partitionValidationPattern);
    if (invalidPartitionVal != null) {
      throw new MetaException("Partition value '" + invalidPartitionVal +
          "' contains a character " + "not matched by whitelist pattern '" +
          partitionValidationPattern.toString() + "'.  " + "(configure with " +
          MetastoreConf.ConfVars.PARTITION_NAME_WHITELIST_PATTERN.getVarname() + ")");
    }
  }

  private static String getPartitionValWithInvalidCharacter(List<String> partVals,
                                                            Pattern partitionValidationPattern) {
    if (partitionValidationPattern == null) {
      return null;
    }

    for (String partVal : partVals) {
      if (!partitionValidationPattern.matcher(partVal).matches()) {
        return partVal;
      }
    }

    return null;
  }

  /**
   * Produce a hash for the storage descriptor
   * @param sd storage descriptor to hash
   * @param md message descriptor to use to generate the hash
   * @return the hash as a byte array
   */
  public static synchronized byte[] hashStorageDescriptor(StorageDescriptor sd, MessageDigest md)  {
    // Note all maps and lists have to be absolutely sorted.  Otherwise we'll produce different
    // results for hashes based on the OS or JVM being used.
    md.reset();
    // In case cols are null
    if (sd.getCols() != null) {
      for (FieldSchema fs : sd.getCols()) {
        md.update(fs.getName().getBytes(ENCODING));
        md.update(fs.getType().getBytes(ENCODING));
        if (fs.getComment() != null) {
          md.update(fs.getComment().getBytes(ENCODING));
        }
      }
    }
    if (sd.getInputFormat() != null) {
      md.update(sd.getInputFormat().getBytes(ENCODING));
    }
    if (sd.getOutputFormat() != null) {
      md.update(sd.getOutputFormat().getBytes(ENCODING));
    }
    md.update(sd.isCompressed() ? "true".getBytes(ENCODING) : "false".getBytes(ENCODING));
    md.update(Integer.toString(sd.getNumBuckets()).getBytes(ENCODING));
    if (sd.getSerdeInfo() != null) {
      SerDeInfo serde = sd.getSerdeInfo();
      if (serde.getName() != null) {
        md.update(serde.getName().getBytes(ENCODING));
      }
      if (serde.getSerializationLib() != null) {
        md.update(serde.getSerializationLib().getBytes(ENCODING));
      }
      if (serde.getParameters() != null) {
        SortedMap<String, String> params = new TreeMap<>(serde.getParameters());
        for (Map.Entry<String, String> param : params.entrySet()) {
          md.update(param.getKey().getBytes(ENCODING));
          md.update(param.getValue().getBytes(ENCODING));
        }
      }
    }
    if (sd.getBucketCols() != null) {
      List<String> bucketCols = new ArrayList<>(sd.getBucketCols());
      for (String bucket : bucketCols) {
        md.update(bucket.getBytes(ENCODING));
      }
    }
    if (sd.getSortCols() != null) {
      SortedSet<Order> orders = new TreeSet<>(sd.getSortCols());
      for (Order order : orders) {
        md.update(order.getCol().getBytes(ENCODING));
        md.update(Integer.toString(order.getOrder()).getBytes(ENCODING));
      }
    }
    if (sd.getSkewedInfo() != null) {
      SkewedInfo skewed = sd.getSkewedInfo();
      if (skewed.getSkewedColNames() != null) {
        SortedSet<String> colnames = new TreeSet<>(skewed.getSkewedColNames());
        for (String colname : colnames) {
          md.update(colname.getBytes(ENCODING));
        }
      }
      if (skewed.getSkewedColValues() != null) {
        SortedSet<String> sortedOuterList = new TreeSet<>();
        for (List<String> innerList : skewed.getSkewedColValues()) {
          SortedSet<String> sortedInnerList = new TreeSet<>(innerList);
          sortedOuterList.add(org.apache.commons.lang.StringUtils.join(sortedInnerList, "."));
        }
        for (String colval : sortedOuterList) {
          md.update(colval.getBytes(ENCODING));
        }
      }
      if (skewed.getSkewedColValueLocationMaps() != null) {
        SortedMap<String, String> sortedMap = new TreeMap<>();
        for (Map.Entry<List<String>, String> smap : skewed.getSkewedColValueLocationMaps().entrySet()) {
          SortedSet<String> sortedKey = new TreeSet<>(smap.getKey());
          sortedMap.put(org.apache.commons.lang.StringUtils.join(sortedKey, "."), smap.getValue());
        }
        for (Map.Entry<String, String> e : sortedMap.entrySet()) {
          md.update(e.getKey().getBytes(ENCODING));
          md.update(e.getValue().getBytes(ENCODING));
        }
      }
      md.update(sd.isStoredAsSubDirectories() ? "true".getBytes(ENCODING) : "false".getBytes(ENCODING));
    }

    return md.digest();
  }

  /*
   * At the Metadata level there are no restrictions on Column Names.
   */
  public static boolean validateColumnName(String name) {
    return true;
  }

  public static boolean isView(Table table) {
    if (table == null) {
      return false;
    }
    return TableType.VIRTUAL_VIEW.toString().equals(table.getTableType());
  }

  /**
   * @param partParams
   * @return True if the passed Parameters Map contains values for all "Fast Stats".
   */
  static boolean containsAllFastStats(Map<String, String> partParams) {
    for (String stat : StatsSetupConst.FAST_STATS) {
      if (!partParams.containsKey(stat)) {
        return false;
      }
    }
    return true;
  }

  public static boolean isFastStatsSame(Partition oldPart, Partition newPart) {
    // requires to calculate stats if new and old have different fast stats
    if ((oldPart != null) && (oldPart.getParameters() != null)) {
      for (String stat : StatsSetupConst.FAST_STATS) {
        if (oldPart.getParameters().containsKey(stat)) {
          Long oldStat = Long.parseLong(oldPart.getParameters().get(stat));
          Long newStat = Long.parseLong(newPart.getParameters().get(stat));
          if (!oldStat.equals(newStat)) {
            return false;
          }
        } else {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /**
   * Updates the numFiles and totalSize parameters for the passed Table by querying
   * the warehouse if the passed Table does not already have values for these parameters.
   * NOTE: This function is rather expensive since it needs to traverse the file system to get all
   * the information.
   *
   * @param newDir if true, the directory was just created and can be assumed to be empty
   * @param forceRecompute Recompute stats even if the passed Table already has
   * these parameters set
   */
  public static void updateTableStatsSlow(Database db, Table tbl, Warehouse wh,
                                          boolean newDir, boolean forceRecompute,
                                          EnvironmentContext environmentContext) throws MetaException {
    // DO_NOT_UPDATE_STATS is supposed to be a transient parameter that is only passed via RPC
    // We want to avoid this property from being persistent.
    //
    // NOTE: If this property *is* set as table property we will remove it which is incorrect but
    // we can't distinguish between these two cases
    //
    // This problem was introduced by HIVE-10228. A better approach would be to pass the property
    // via the environment context.
    Map<String,String> params = tbl.getParameters();
    boolean updateStats = true;
    if ((params != null) && params.containsKey(StatsSetupConst.DO_NOT_UPDATE_STATS)) {
      updateStats = !Boolean.valueOf(params.get(StatsSetupConst.DO_NOT_UPDATE_STATS));
      params.remove(StatsSetupConst.DO_NOT_UPDATE_STATS);
    }

    if (!updateStats || newDir || tbl.getPartitionKeysSize() != 0) {
      return;
    }

    // If stats are already present and forceRecompute isn't set, nothing to do
    if (!forceRecompute && params != null && containsAllFastStats(params)) {
      return;
    }

    // NOTE: wh.getFileStatusesForUnpartitionedTable() can be REALLY slow
    List<FileStatus> fileStatus = wh.getFileStatusesForUnpartitionedTable(db, tbl);
    if (params == null) {
      params = new HashMap<>();
      tbl.setParameters(params);
    }
    // The table location already exists and may contain data.
    // Let's try to populate those stats that don't require full scan.
    LOG.info("Updating table stats for {}", tbl.getTableName());
    populateQuickStats(fileStatus, params);
    LOG.info("Updated size of table {} to {}",
        tbl.getTableName(), params.get(StatsSetupConst.TOTAL_SIZE));
    if (environmentContext != null
        && environmentContext.isSetProperties()
        && StatsSetupConst.TASK.equals(environmentContext.getProperties().get(
        StatsSetupConst.STATS_GENERATED))) {
      StatsSetupConst.setBasicStatsState(params, StatsSetupConst.TRUE);
    } else {
      StatsSetupConst.setBasicStatsState(params, StatsSetupConst.FALSE);
    }
  }

  /** This method is invalid for MM and ACID tables unless fileStatus comes from AcidUtils. */
  public static void populateQuickStats(List<FileStatus> fileStatus, Map<String, String> params) {
    // Why is this even in metastore?
    LOG.trace("Populating quick stats based on {} files", fileStatus.size());
    int numFiles = 0;
    long tableSize = 0L;
    int numErasureCodedFiles = 0;
    for (FileStatus status : fileStatus) {
      // don't take directories into account for quick stats TODO: wtf?
      if (!status.isDir()) {
        tableSize += status.getLen();
        numFiles += 1;
        if (status.isErasureCoded()) {
          numErasureCodedFiles++;
        }
      }
    }
    params.put(StatsSetupConst.NUM_FILES, Integer.toString(numFiles));
    params.put(StatsSetupConst.TOTAL_SIZE, Long.toString(tableSize));
    params.put(StatsSetupConst.NUM_ERASURE_CODED_FILES, Integer.toString(numErasureCodedFiles));
  }

  public static void clearQuickStats(Map<String, String> params) {
    params.remove(StatsSetupConst.NUM_FILES);
    params.remove(StatsSetupConst.TOTAL_SIZE);
    params.remove(StatsSetupConst.NUM_ERASURE_CODED_FILES);
  }

  public static boolean areSameColumns(List<FieldSchema> oldCols, List<FieldSchema> newCols) {
    return ListUtils.isEqualList(oldCols, newCols);
  }

  public static void updateBasicState(EnvironmentContext environmentContext, Map<String,String>
      params) {
    if (params == null) {
      return;
    }
    if (environmentContext != null
        && environmentContext.isSetProperties()
        && StatsSetupConst.TASK.equals(environmentContext.getProperties().get(
        StatsSetupConst.STATS_GENERATED))) {
      StatsSetupConst.setBasicStatsState(params, StatsSetupConst.TRUE);
    } else {
      StatsSetupConst.setBasicStatsState(params, StatsSetupConst.FALSE);
    }
  }

  /**
   * Updates the numFiles and totalSize parameters for the passed Partition by querying
   *  the warehouse if the passed Partition does not already have values for these parameters.
   * @param part
   * @param wh
   * @param madeDir if true, the directory was just created and can be assumed to be empty
   * @param forceRecompute Recompute stats even if the passed Partition already has
   * these parameters set
   * @return true if the stats were updated, false otherwise
   */
  public static boolean updatePartitionStatsFast(Partition part, Table tbl, Warehouse wh,
      boolean madeDir, boolean forceRecompute, EnvironmentContext environmentContext,
      boolean isCreate) throws MetaException {
    return updatePartitionStatsFast(new PartitionSpecProxy.SimplePartitionWrapperIterator(part),
        tbl, wh, madeDir, forceRecompute, environmentContext, isCreate);
  }

  /**
   * Updates the numFiles and totalSize parameters for the passed Partition by querying
   *  the warehouse if the passed Partition does not already have values for these parameters.
   * @param part
   * @param wh
   * @param madeDir if true, the directory was just created and can be assumed to be empty
   * @param forceRecompute Recompute stats even if the passed Partition already has
   * these parameters set
   * @return true if the stats were updated, false otherwise
   */
  public static boolean updatePartitionStatsFast(PartitionSpecProxy.PartitionIterator part,
      Table table, Warehouse wh, boolean madeDir, boolean forceRecompute,
      EnvironmentContext environmentContext, boolean isCreate) throws MetaException {
    Map<String,String> params = part.getParameters();
    if (!forceRecompute && params != null && containsAllFastStats(params)) return false;
    if (params == null) {
      params = new HashMap<>();
    }
    if (!isCreate && isTransactionalTable(table.getParameters())) {
      // TODO: implement?
      LOG.warn("Not updating fast stats for a transactional table " + table.getTableName());
      part.setParameters(params);
      return true;
    }
    if (!madeDir) {
      // The partition location already existed and may contain data. Lets try to
      // populate those statistics that don't require a full scan of the data.
      LOG.warn("Updating partition stats fast for: " + part.getTableName());
      List<FileStatus> fileStatus = wh.getFileStatusesForLocation(part.getLocation());
      // TODO: this is invalid for ACID tables, and we cannot access AcidUtils here.
      populateQuickStats(fileStatus, params);
      LOG.warn("Updated size to " + params.get(StatsSetupConst.TOTAL_SIZE));
      updateBasicState(environmentContext, params);
    }
    part.setParameters(params);
    return true;
  }

  /*
     * This method is to check if the new column list includes all the old columns with same name and
     * type. The column comment does not count.
     */
  public static boolean columnsIncludedByNameType(List<FieldSchema> oldCols,
                                                  List<FieldSchema> newCols) {
    if (oldCols.size() > newCols.size()) {
      return false;
    }

    Map<String, String> columnNameTypePairMap = new HashMap<>(newCols.size());
    for (FieldSchema newCol : newCols) {
      columnNameTypePairMap.put(newCol.getName().toLowerCase(), newCol.getType());
    }
    for (final FieldSchema oldCol : oldCols) {
      if (!columnNameTypePairMap.containsKey(oldCol.getName())
          || !columnNameTypePairMap.get(oldCol.getName()).equalsIgnoreCase(oldCol.getType())) {
        return false;
      }
    }

    return true;
  }

  /** Duplicates AcidUtils; used in a couple places in metastore. */
  public static boolean isTransactionalTable(Map<String, String> params) {
    String transactionalProp = params.get(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL);
    return (transactionalProp != null && "true".equalsIgnoreCase(transactionalProp));
  }

  /**
   * create listener instances as per the configuration.
   *
   * @param clazz Class of the listener
   * @param conf configuration object
   * @param listenerImplList Implementation class name
   * @return instance of the listener
   * @throws MetaException if there is any failure instantiating the class
   */
  public static <T> List<T> getMetaStoreListeners(Class<T> clazz,
                                                  Configuration conf, String listenerImplList) throws MetaException {
    List<T> listeners = new ArrayList<T>();

    if (StringUtils.isBlank(listenerImplList)) {
      return listeners;
    }

    String[] listenerImpls = listenerImplList.split(",");
    for (String listenerImpl : listenerImpls) {
      try {
        T listener = (T) Class.forName(
            listenerImpl.trim(), true, JavaUtils.getClassLoader()).getConstructor(
                Configuration.class).newInstance(conf);
        listeners.add(listener);
      } catch (InvocationTargetException ie) {
        LOG.error("Got InvocationTargetException", ie);
        throw new MetaException("Failed to instantiate listener named: "+
            listenerImpl + ", reason: " + ie.getCause());
      } catch (Exception e) {
        LOG.error("Got Exception", e);
        throw new MetaException("Failed to instantiate listener named: "+
            listenerImpl + ", reason: " + e);
      }
    }

    return listeners;
  }

  public static String validateSkewedColNames(List<String> cols) {
    if (CollectionUtils.isEmpty(cols)) {
      return null;
    }
    for (String col : cols) {
      if (!validateColumnName(col)) {
        return col;
      }
    }
    return null;
  }

  public static String validateSkewedColNamesSubsetCol(List<String> skewedColNames,
                                                       List<FieldSchema> cols) {
    if (CollectionUtils.isEmpty(skewedColNames)) {
      return null;
    }
    List<String> colNames = new ArrayList<>(cols.size());
    for (FieldSchema fieldSchema : cols) {
      colNames.add(fieldSchema.getName());
    }
    // make a copy
    List<String> copySkewedColNames = new ArrayList<>(skewedColNames);
    // remove valid columns
    copySkewedColNames.removeAll(colNames);
    if (copySkewedColNames.isEmpty()) {
      return null;
    }
    return copySkewedColNames.toString();
  }

  public static boolean partitionNameHasValidCharacters(List<String> partVals,
                                                        Pattern partitionValidationPattern) {
    return getPartitionValWithInvalidCharacter(partVals, partitionValidationPattern) == null;
  }

  public static void getMergableCols(ColumnStatistics csNew, Map<String, String> parameters) {
    List<ColumnStatisticsObj> list = new ArrayList<>();
    for (int index = 0; index < csNew.getStatsObj().size(); index++) {
      ColumnStatisticsObj statsObjNew = csNew.getStatsObj().get(index);
      // canColumnStatsMerge guarantees that it is accurate before we do merge
      if (StatsSetupConst.canColumnStatsMerge(parameters, statsObjNew.getColName())) {
        list.add(statsObjNew);
      }
      // in all the other cases, we can not merge
    }
    csNew.setStatsObj(list);
  }

  // this function will merge csOld into csNew.
  public static void mergeColStats(ColumnStatistics csNew, ColumnStatistics csOld)
      throws InvalidObjectException {
    List<ColumnStatisticsObj> list = new ArrayList<>();
    if (csNew.getStatsObj().size() != csOld.getStatsObjSize()) {
      // Some of the columns' stats are missing
      // This implies partition schema has changed. We will merge columns
      // present in both, overwrite stats for columns absent in metastore and
      // leave alone columns stats missing from stats task. This last case may
      // leave stats in stale state. This will be addressed later.
      LOG.debug("New ColumnStats size is {}, but old ColumnStats size is {}",
          csNew.getStatsObj().size(), csOld.getStatsObjSize());
    }
    // In this case, we have to find out which columns can be merged.
    Map<String, ColumnStatisticsObj> map = new HashMap<>();
    // We build a hash map from colName to object for old ColumnStats.
    for (ColumnStatisticsObj obj : csOld.getStatsObj()) {
      map.put(obj.getColName(), obj);
    }
    for (int index = 0; index < csNew.getStatsObj().size(); index++) {
      ColumnStatisticsObj statsObjNew = csNew.getStatsObj().get(index);
      ColumnStatisticsObj statsObjOld = map.get(statsObjNew.getColName());
      if (statsObjOld != null) {
        // because we already confirm that the stats is accurate
        // it is impossible that the column types have been changed while the
        // column stats is still accurate.
        assert (statsObjNew.getStatsData().getSetField() == statsObjOld.getStatsData()
            .getSetField());
        // If statsObjOld is found, we can merge.
        ColumnStatsMerger merger = ColumnStatsMergerFactory.getColumnStatsMerger(statsObjNew,
            statsObjOld);
        merger.merge(statsObjNew, statsObjOld);
      }
      // If statsObjOld is not found, we just use statsObjNew as it is accurate.
      list.add(statsObjNew);
    }
    // in all the other cases, we can not merge
    csNew.setStatsObj(list);
  }

  /**
   * Verify if the user is allowed to make DB notification related calls.
   * Only the superusers defined in the Hadoop proxy user settings have the permission.
   *
   * @param user the short user name
   * @param conf that contains the proxy user settings
   * @return if the user has the permission
   */
  public static boolean checkUserHasHostProxyPrivileges(String user, Configuration conf, String ipAddress) {
    DefaultImpersonationProvider sip = ProxyUsers.getDefaultImpersonationProvider();
    // Just need to initialize the ProxyUsers for the first time, given that the conf will not change on the fly
    if (sip == null) {
      ProxyUsers.refreshSuperUserGroupsConfiguration(conf);
      sip = ProxyUsers.getDefaultImpersonationProvider();
    }
    Map<String, Collection<String>> proxyHosts = sip.getProxyHosts();
    Collection<String> hostEntries = proxyHosts.get(sip.getProxySuperuserIpConfKey(user));
    MachineList machineList = new MachineList(hostEntries);
    ipAddress = (ipAddress == null) ? StringUtils.EMPTY : ipAddress;
    return machineList.includes(ipAddress);
  }

  public static int startMetaStore() throws Exception {
    return startMetaStore(HadoopThriftAuthBridge.getBridge(), null);
  }

  public static int startMetaStore(final HadoopThriftAuthBridge bridge, Configuration conf) throws
      Exception {
    int port = findFreePort();
    startMetaStore(port, bridge, conf);
    return port;
  }

  public static int startMetaStore(Configuration conf) throws Exception {
    return startMetaStore(HadoopThriftAuthBridge.getBridge(), conf);
  }

  public static void startMetaStore(final int port, final HadoopThriftAuthBridge bridge) throws Exception {
    startMetaStore(port, bridge, null);
  }

  public static void startMetaStore(final int port,
                                    final HadoopThriftAuthBridge bridge, Configuration hiveConf)
      throws Exception{
    if (hiveConf == null) {
      hiveConf = MetastoreConf.newMetastoreConf();
    }
    final Configuration finalHiveConf = hiveConf;
    Thread thread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          HiveMetaStore.startMetaStore(port, bridge, finalHiveConf);
        } catch (Throwable e) {
          LOG.error("Metastore Thrift Server threw an exception...",e);
        }
      }
    });
    thread.setDaemon(true);
    thread.start();
    loopUntilHMSReady(port);
  }

  /**
   * A simple connect test to make sure that the metastore is up
   * @throws Exception
   */
  private static void loopUntilHMSReady(int port) throws Exception {
    int retries = 0;
    Exception exc;
    while (true) {
      try {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(port), 5000);
        socket.close();
        return;
      } catch (Exception e) {
        if (retries++ > 60) { //give up
          exc = e;
          break;
        }
        Thread.sleep(1000);
      }
    }
    // something is preventing metastore from starting
    // print the stack from all threads for debugging purposes
    LOG.error("Unable to connect to metastore server: " + exc.getMessage());
    LOG.info("Printing all thread stack traces for debugging before throwing exception.");
    LOG.info(getAllThreadStacksAsString());
    throw exc;
  }

  private static String getAllThreadStacksAsString() {
    Map<Thread, StackTraceElement[]> threadStacks = Thread.getAllStackTraces();
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<Thread, StackTraceElement[]> entry : threadStacks.entrySet()) {
      Thread t = entry.getKey();
      sb.append(System.lineSeparator());
      sb.append("Name: ").append(t.getName()).append(" State: ").append(t.getState());
      addStackString(entry.getValue(), sb);
    }
    return sb.toString();
  }

  private static void addStackString(StackTraceElement[] stackElems, StringBuilder sb) {
    sb.append(System.lineSeparator());
    for (StackTraceElement stackElem : stackElems) {
      sb.append(stackElem).append(System.lineSeparator());
    }
  }

  /**
   * Finds a free port on the machine.
   *
   * @return
   * @throws IOException
   */
  public static int findFreePort() throws IOException {
    ServerSocket socket= new ServerSocket(0);
    int port = socket.getLocalPort();
    socket.close();
    return port;
  }

  /**
   * Finds a free port on the machine, but allow the
   * ability to specify a port number to not use, no matter what.
   */
  public static int findFreePortExcepting(int portToExclude) throws IOException {
    ServerSocket socket1 = null;
    ServerSocket socket2 = null;
    try {
      socket1 = new ServerSocket(0);
      socket2 = new ServerSocket(0);
      if (socket1.getLocalPort() != portToExclude) {
        return socket1.getLocalPort();
      }
      // If we're here, then socket1.getLocalPort was the port to exclude
      // Since both sockets were open together at a point in time, we're
      // guaranteed that socket2.getLocalPort() is not the same.
      return socket2.getLocalPort();
    } finally {
      if (socket1 != null){
        socket1.close();
      }
      if (socket2 != null){
        socket2.close();
      }
    }
  }

  public static String getIndexTableName(String dbName, String baseTblName, String indexName) {
    return dbName + "__" + baseTblName + "_" + indexName + "__";
  }

  static public String validateTblColumns(List<FieldSchema> cols) {
    for (FieldSchema fieldSchema : cols) {
      // skip this, as validateColumnName always returns true
      /*
      if (!validateColumnName(fieldSchema.getName())) {
        return "name: " + fieldSchema.getName();
      }
      */
      String typeError = validateColumnType(fieldSchema.getType());
      if (typeError != null) {
        return typeError;
      }
    }
    return null;
  }

  private static String validateColumnType(String type) {
    if (type.equals(MetaStoreUtils.TYPE_FROM_DESERIALIZER)) {
      return null;
    }
    int last = 0;
    boolean lastAlphaDigit = isValidTypeChar(type.charAt(last));
    for (int i = 1; i <= type.length(); i++) {
      if (i == type.length()
          || isValidTypeChar(type.charAt(i)) != lastAlphaDigit) {
        String token = type.substring(last, i);
        last = i;
        if (!ColumnType.AllTypes.contains(token)) {
          return "type: " + type;
        }
        break;
      }
    }
    return null;
  }

  private static boolean isValidTypeChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  // ColumnStatisticsObj with info about its db, table, partition (if table is partitioned)
  public static class ColStatsObjWithSourceInfo {
    private final ColumnStatisticsObj colStatsObj;
    private final String catName;
    private final String dbName;
    private final String tblName;
    private final String partName;

    public ColStatsObjWithSourceInfo(ColumnStatisticsObj colStatsObj, String catName, String dbName, String tblName,
        String partName) {
      this.colStatsObj = colStatsObj;
      this.catName = catName;
      this.dbName = dbName;
      this.tblName = tblName;
      this.partName = partName;
    }

    public ColumnStatisticsObj getColStatsObj() {
      return colStatsObj;
    }

    public String getCatName() {
      return catName;
    }

    public String getDbName() {
      return dbName;
    }

    public String getTblName() {
      return tblName;
    }

    public String getPartName() {
      return partName;
    }
  }
}
