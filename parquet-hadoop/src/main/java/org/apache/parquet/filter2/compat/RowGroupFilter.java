/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.parquet.filter2.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.parquet.filter2.BloomFilter;
import org.apache.parquet.filter2.compat.FilterCompat.Filter;
import org.apache.parquet.filter2.compat.FilterCompat.NoOpFilter;
import org.apache.parquet.filter2.compat.FilterCompat.Visitor;
import org.apache.parquet.filter2.dictionarylevel.DictionaryFilter;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.filter2.predicate.SchemaCompatibilityValidator;
import org.apache.parquet.filter2.statisticslevel.StatisticsFilter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.schema.MessageType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.parquet.Preconditions.checkNotNull;

/**
 * Given a {@link Filter} applies it to a list of BlockMetaData (row groups)
 * If the Filter is an {@link org.apache.parquet.filter.UnboundRecordFilter} or the no op filter,
 * no filtering will be performed.
 */
public class RowGroupFilter implements Visitor<List<BlockMetaData>> {
  private final List<BlockMetaData> blocks;
  private final MessageType schema;
  private final List<FilterLevel> levels;
  private final ParquetFileReader reader;
  private Logger logger = LoggerFactory.getLogger(RowGroupFilter.class);

  public enum FilterLevel {
    STATISTICS,
    DICTIONARY,
    BLOOM
  }

  public static List<BlockMetaData> filterRowGroups(Filter filter, List<BlockMetaData> blocks, MessageType schema) {
    checkNotNull(filter, "filter");
    return filter.accept(new RowGroupFilter(blocks, schema));
  }

  public static List<BlockMetaData> filterRowGroups(List<FilterLevel> levels, Filter filter, List<BlockMetaData> blocks, ParquetFileReader reader) {
    checkNotNull(filter, "filter");
    return filter.accept(new RowGroupFilter(levels, blocks, reader));
  }

  @Deprecated
  private RowGroupFilter(List<BlockMetaData> blocks, MessageType schema) {
    this.blocks = checkNotNull(blocks, "blocks");
    this.schema = checkNotNull(schema, "schema");
    this.levels = Collections.singletonList(FilterLevel.STATISTICS);
    this.reader = null;
  }

  private RowGroupFilter(List<FilterLevel> levels, List<BlockMetaData> blocks, ParquetFileReader reader) {
    this.blocks = checkNotNull(blocks, "blocks");
    this.reader = checkNotNull(reader, "reader");
    this.schema = reader.getFileMetaData().getSchema();
    this.levels = levels;
  }

  @Override
  public List<BlockMetaData> visit(FilterCompat.FilterPredicateCompat filterPredicateCompat) {
    FilterPredicate filterPredicate = filterPredicateCompat.getFilterPredicate();

    // check that the schema of the filter matches the schema of the file
    SchemaCompatibilityValidator.validate(filterPredicate, schema);

    List<BlockMetaData> filteredBlocks = new ArrayList<BlockMetaData>();

    for (BlockMetaData block : blocks) {
      boolean drop = false;

      if(levels.contains(FilterLevel.STATISTICS)) {
        drop = StatisticsFilter.canDrop(filterPredicate, block.getColumns());
      }

      if(!drop && levels.contains(FilterLevel.DICTIONARY)) {
        drop = DictionaryFilter.canDrop(filterPredicate, block.getColumns(), reader.getDictionaryReader(block));
      }


      if (!drop && levels.contains(FilterLevel.BLOOM)) {
        drop = BloomFilter.canDrop(filterPredicate, block.getColumns(), reader.getBloomDataReader(block));
        if (drop) logger.info("Block drop by bloom filter ");
      }

      if(!drop) {
        filteredBlocks.add(block);
      }
    }

    return filteredBlocks;
  }

  @Override
  public List<BlockMetaData> visit(FilterCompat.UnboundRecordFilterCompat unboundRecordFilterCompat) {
    return blocks;
  }

  @Override
  public List<BlockMetaData> visit(NoOpFilter noOpFilter) {
    return blocks;
  }
}
