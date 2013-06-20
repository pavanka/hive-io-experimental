/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.hiveio.input;

import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.facebook.hiveio.common.HadoopUtils;
import com.facebook.hiveio.common.HiveTableDesc;
import com.facebook.hiveio.common.HiveUtils;
import com.facebook.hiveio.common.Writables;
import com.facebook.hiveio.hadoop.shims.api.ConfigurationShim;
import com.facebook.hiveio.hadoop.shims.api.HadoopShims;
import com.facebook.hiveio.hadoop.shims.api.InputFormatShim;
import com.facebook.hiveio.input.parser.Parsers;
import com.facebook.hiveio.input.parser.RecordParser;
import com.facebook.hiveio.record.HiveReadableRecord;
import com.facebook.hiveio.schema.HiveTableSchema;
import com.facebook.hiveio.schema.HiveTableSchemaImpl;
import com.facebook.hiveio.schema.HiveTableSchemas;
import com.google.common.collect.DiscreteDomains;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.Ranges;

import java.io.IOException;
import java.util.List;

import static com.google.common.collect.Lists.transform;

/**
 * InputFormat to read from Hive
 */
public class HiveApiInputFormat
    implements InputFormatShim<Object, HiveReadableRecord> {
  /** Default profile ID if none given */
  public static final String DEFAULT_PROFILE_ID = "input-profile";

  /** Logger */
  private static final Logger LOG = LoggerFactory.getLogger(HiveApiInputFormat.class);

  /** Which profile to lookup */
  private String myProfileId = DEFAULT_PROFILE_ID;

  /** Input observer */
  private HiveApiInputObserver observer;

  /**
   * Get profile id
   * @return integer profile id
   */
  public String getMyProfileId() {
    return myProfileId;
  }

  /**
   * Set profile ID
   * @param myProfileId integer profile id
   */
  public void setMyProfileId(String myProfileId) {
    this.myProfileId = myProfileId;
  }

  /**
   * Get observer currently registered
   * @return Observer
   */
  public HiveApiInputObserver getObserver() {
    return observer;
  }

  /**
   * Set observer that will watch operations
   * @param observer Observer to set
   */
  public void setObserver(HiveApiInputObserver observer) {
    this.observer = observer;
  }

  /**
   * Get table schema for this profile
   * @param conf Configuration to use
   * @return HiveTableSchema
   */
  public HiveTableSchema getTableSchema(ConfigurationShim conf) {
    return HiveTableSchemas.get(conf, myProfileId);
  }

  /**
   * Key for profile
   *
   * @param profileId profile
   * @return String key
   */
  private static String profileConfKey(String profileId) {
    return "hive.input." + profileId;
  }

  /**
   * Set input description for profile
   *
   * @param conf Configuration
   * @param inputDesc Hive table input description
   * @param profileId profile ID
   */
  public static void setProfileInputDesc(ConfigurationShim conf,
    HiveInputDescription inputDesc, String profileId) {
    conf.set(profileConfKey(profileId), Writables.writeToEncodedStr(inputDesc));
  }

  /**
   * Read input description for profile
   *
   * @param conf Configuration
   * @return HiveInputDescription
   */
  private HiveInputDescription readProfileInputDesc(ConfigurationShim conf)
  {
    HiveInputDescription inputDesc = new HiveInputDescription();
    Writables.readFieldsFromEncodedStr(conf.get(profileConfKey(myProfileId)), inputDesc);
    return inputDesc;
  }

  @Override
  public List<HadoopShims> getSplits(ConfigurationShim conf)
    throws IOException, InterruptedException
  {
    HiveInputDescription inputDesc = readProfileInputDesc(conf);

    ThriftHiveMetastore.Iface client;
    try {
      client = inputDesc.metastoreClient(conf);
    } catch (TException e) {
      throw new IOException(e);
    }

    return getSplits(conf, inputDesc, client);
  }

  /**
   * Get splits
   *
   * @param conf Configuration
   * @param inputDesc Hive table input description
   * @param client Metastore client
   * @return list of input splits
   * @throws IOException
   */
  public List<HadoopShims> getSplits(ConfigurationShim conf,
    HiveInputDescription inputDesc, ThriftHiveMetastore.Iface client)
    throws IOException
  {
    HiveTableDesc tableDesc = inputDesc.getTableDesc();
    Table table;
    try {
      table = client.get_table(tableDesc.getDatabaseName(), tableDesc.getTableName());
      // CHECKSTYLE: stop IllegalCatch
    } catch (Exception e) {
      // CHECKSTYLE: resume IllegalCatch
      throw new IOException(e);
    }

    final HiveTableSchema tableSchema = HiveTableSchemaImpl.fromTable(conf, table);
    HiveTableSchemas.put(conf, myProfileId, tableSchema);

    List<InputPartition> partitions = computePartitions(inputDesc, client, table);

    List<HadoopShims> splits = computeSplits(conf, inputDesc, tableSchema, partitions);

    return splits;
  }

  /**
   * Compute splits from partitions
   *
   * @param conf Configuration
   * @param inputDesc Hive table input description
   * @param tableSchema schema for table
   * @param partitions list of input partitions
   * @return list of input splits
   * @throws IOException
   */
  private List<HadoopShims> computeSplits(ConfigurationShim conf,
      HiveInputDescription inputDesc, HiveTableSchema tableSchema,
      List<InputPartition> partitions) throws IOException
  {
    int partitionNum = 0;
    List<HadoopShims> splits = Lists.newArrayList();

    int[] columnIds = computeColumnIds(inputDesc.getColumns(), tableSchema);

    for (InputPartition inputPartition : partitions) {
      org.apache.hadoop.mapred.InputFormat baseInputFormat = inputPartition.makeInputFormat(conf);
      HadoopUtils.setInputDir(conf, inputPartition.getLocation());

      org.apache.hadoop.mapred.InputSplit[] baseSplits =
          baseInputFormat.getSplits(new JobConf(conf), inputDesc.getNumSplits());
      LOG.info("Requested {} splits from partition ({} out of {}) partition values: " +
          "{}, got {} splits from inputFormat {}",
          inputDesc.getNumSplits(), partitionNum + 1, Iterables.size(partitions),
          inputPartition.getInputSplitData().getPartitionValues(), baseSplits.length,
          baseInputFormat.getClass().getCanonicalName());

      for (org.apache.hadoop.mapred.InputSplit baseSplit : baseSplits)  {
        HadoopShims split = new HInputSplit(baseInputFormat, baseSplit,
            tableSchema, columnIds, inputPartition.getInputSplitData(), conf);
        splits.add(split);
      }

      partitionNum++;
    }
    return splits;
  }

  /**
   * Compute column IDs from names
   *
   * @param columnNames names of columns
   * @param tableSchema schema for Hive table
   * @return array of column IDs
   */
  private int[] computeColumnIds(List<String> columnNames, HiveTableSchema tableSchema)
  {
    List<Integer> ints;
    if (columnNames.isEmpty()) {
      Range<Integer> range = Ranges.closedOpen(0, tableSchema.numColumns());
      ints = range.asSet(DiscreteDomains.integers()).asList();
    } else {
      ints = transform(columnNames, HiveTableSchemas
          .schemaLookupFunc(tableSchema));
    }
    int[] result = new int[ints.size()];
    for (int i = 0; i < ints.size(); ++i) {
      result[i] = ints.get(i);
    }
    return result;
  }

  /**
   * Compute partitions to query
   *
   * @param inputDesc Hive table input description
   * @param client Metastore client
   * @param table Thrift Table
   * @return list of input partitions
   * @throws IOException
   */
  private List<InputPartition> computePartitions(HiveInputDescription inputDesc,
    ThriftHiveMetastore.Iface client, Table table) throws IOException
  {
    List<InputPartition> partitions = Lists.newArrayList();

    if (table.getPartitionKeysSize() == 0) {
      // table without partitions
      partitions.add(InputPartition.newFromHiveTable(table));
    } else {
      // table with partitions, find matches to user filter.
      List<Partition> hivePartitions;
      HiveTableDesc tableDesc = inputDesc.getTableDesc();
      try {
        hivePartitions = client.get_partitions_by_filter(tableDesc.getDatabaseName(),
            tableDesc.getTableName(), inputDesc.getPartitionFilter(), (short) -1);
        // CHECKSTYLE: stop IllegalCatch
      } catch (Exception e) {
        // CHECKSTYLE: resume IllegalCatch
        throw new IOException(e);
      }
      for (Partition hivePartition : hivePartitions) {
        partitions.add(InputPartition.newFromHivePartition(hivePartition));
      }
    }
    return partitions;
  }

  @Override
  public RecordReaderImpl
  createRecordReader(HadoopShims inputSplit, TaskAttemptContext context)
    throws IOException, InterruptedException
  {
    ConfigurationShim conf = context.getConfiguration();
    JobConf jobConf = new JobConf(conf);

    HInputSplit split = (HInputSplit) inputSplit;
    split.setConf(jobConf);

    int[] columnIds = split.getColumnIds();
    HiveUtils.setReadColumnIds(jobConf, columnIds);

    // CHECKSTYLE: stop LineLength
    org.apache.hadoop.mapred.RecordReader<WritableComparable, Writable> baseRecordReader =
        split.getBaseRecordReader(jobConf, context);
    // CHECKSTYLE: resume LineLength

    RecordParser<Writable> recordParser = getParser(baseRecordReader.createValue(),
        split, columnIds, conf);

    RecordReaderImpl reader = new RecordReaderImpl(baseRecordReader, recordParser);
    reader.setObserver(observer);

    return reader;
  }

  /**
   * Get record parser for the table
   *
   * @param exampleValue An example value to use
   * @param split HInputSplit
   * @param columnIds column ids
   * @param conf Configuration
   * @return RecordParser
   */
  private RecordParser<Writable> getParser(Writable exampleValue,
    HInputSplit split, int[] columnIds, ConfigurationShim conf)
  {
    Deserializer deserializer = split.getDeserializer();
    String[] partitionValues = split.getPartitionValues();
    HiveTableSchema schema = split.getTableSchema();
    return Parsers.bestParser(deserializer, schema, columnIds,
        partitionValues, exampleValue, conf);
  }
}
